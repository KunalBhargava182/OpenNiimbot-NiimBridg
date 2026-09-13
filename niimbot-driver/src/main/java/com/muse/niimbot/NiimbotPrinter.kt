package com.muse.niimbot

import android.graphics.Bitmap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * D110_M printer client. Sequence and byte layouts verified against an HCI snoop
 * capture of the official app -- see docs/PROTOCOL.md.
 *
 * Public since 1.1 — this is the headless API: everything a custom print screen needs,
 * with no bundled UI. The packaged NiimbotPrintSdk screen is built on exactly this same
 * class; it has no special access you don't also have. [log] is the same hook the
 * packaged screen uses to feed its Packet Console -- wire it to your own logging if you
 * want equivalent visibility.
 *
 * Not thread-confined to any particular dispatcher, but every suspend function here
 * suspends for real IO (Bluetooth round-trips) -- call these from a coroutine, not
 * from a UI thread expecting an immediate return.
 */
class NiimbotPrinter(
    private val transport: NiimbotTransport,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
) {
    private val buffer = PacketBuffer()
    private val packets = MutableSharedFlow<NiimbotPacket>(replay = 0, extraBufferCapacity = 512)
    private val txLock = Mutex()
    private var pump: Job? = null

    private val _state = MutableStateFlow<PrinterState>(PrinterState.Disconnected)

    /** Live connection/print state. Collect this to drive a custom screen's UI. */
    val state: StateFlow<PrinterState> = _state.asStateFlow()

    // ---------------------------------------------------------------- lifecycle

    /**
     * Connects [transport], starts the inbound packet pump, and reads [DeviceInfo] to
     * confirm the model and log firmware version (the likeliest source of future
     * breakage -- see `docs/PROTOCOL.md`). [state] transitions Connecting -> Ready (or
     * Failed) as this runs.
     *
     * Throws [NiimbotException] on any failure; [state] is set to [PrinterState.Failed]
     * before the throw, so a collector never needs to also catch this to know a connect
     * attempt failed.
     */
    suspend fun connect(): DeviceInfo {
        _state.value = PrinterState.Connecting
        try {
            transport.connect()
            pump = scope.launch {
                transport.inbound.collect { chunk ->
                    buffer.append(chunk)
                    buffer.drain().forEach { p ->
                        log("RX  $p")
                        when (p.type) {
                            Opcodes.R_ERROR -> log("RX  *** PRINTER ERROR ***")
                            Opcodes.U_LINE_PROGRESS -> { /* unsolicited, ignore */ }
                        }
                        packets.emit(p)
                    }
                }
            }
            val info = readDeviceInfo()
            if (!info.isD110M) log("WARN  unexpected model id ${info.modelId} (expected ${Opcodes.MODEL_ID_D110_M})")
            log("Connected: ${info.modelName} sn=${info.serial} fw=${info.softVersion} batt=${info.battery}")
            _state.value = PrinterState.Ready(info)
            return info
        } catch (e: Exception) {
            _state.value = PrinterState.Failed(e.message ?: "connect failed")
            throw e
        }
    }

    /** Stops the packet pump, closes [transport], and resets [state] to [PrinterState.Disconnected]. */
    fun disconnect() {
        pump?.cancel(); transport.close(); buffer.clear()
        _state.value = PrinterState.Disconnected
    }

    // ---------------------------------------------------------------- queries

    /**
     * Reads model, firmware/hardware version, serial, and battery via `GetInfo` (0x40).
     * Called once automatically inside [connect]; call it again yourself for a manual
     * refresh (e.g. an "Info" screen's pull-to-refresh) without reconnecting.
     */
    suspend fun readDeviceInfo(): DeviceInfo {
        val model = getInfoInt(Opcodes.Info.DEVICE_TYPE)
        val soft = getInfoInt(Opcodes.Info.SOFT_VERSION)
        val hard = getInfoInt(Opcodes.Info.HARD_VERSION)
        val batt = getInfoInt(Opcodes.Info.BATTERY)
        val serialBytes = getInfoRaw(Opcodes.Info.DEVICE_SERIAL)
        // D110_M returns ASCII here, unlike older models which return raw hex.
        val serial = if (serialBytes.all { it in 0x20..0x7E }) String(serialBytes, Charsets.US_ASCII)
                     else serialBytes.joinToString("") { "%02x".format(it) }
        return DeviceInfo(
            modelId = model,
            modelName = if (model == Opcodes.MODEL_ID_D110_M) "NIIMBOT D110_M" else "NIIMBOT (id $model)",
            serial = serial,
            softVersion = "%.2f".format(soft / 100.0),
            hardVersion = "%.2f".format(hard / 100.0),
            battery = batt,
        )
    }

    /**
     * Reads the roll's RFID info via `GetRfid` (0x1A) -- barcode, serial, and the label
     * counts behind [RfidInfo.remaining]. Returns `null` when the printer reports no tag
     * (empty/unreadable roll), not when the call fails -- a failed call still throws
     * [NiimbotException]. Call this before a batch print to confirm the roll can finish it.
     */
    suspend fun readRfid(): RfidInfo? {
        val p = transceive(NiimbotPacket(Opcodes.GET_RFID, byteArrayOf(0x01)), Opcodes.R_GET_RFID)
        val d = p.data
        if (d.isEmpty() || d[0].toInt() == 0) return null
        var i = 8
        val uuid = d.copyOfRange(0, 8).joinToString("") { "%02x".format(it) }
        val bLen = d[i].toInt() and 0xFF; i++
        val barcode = String(d, i, bLen, Charsets.US_ASCII); i += bLen
        val sLen = d[i].toInt() and 0xFF; i++
        val serial = String(d, i, sLen, Charsets.US_ASCII); i += sLen
        val total = be16(d, i); val used = be16(d, i + 2); val type = d[i + 4].toInt() and 0xFF
        return RfidInfo(uuid, barcode, serial, total, used, type)
    }

    /** Optional; makes printer-side logs carry real timestamps. */
    suspend fun setClock(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int) {
        val d = byteArrayOf(0x01, (year shr 8).toByte(), year.toByte(),
            month.toByte(), day.toByte(), hour.toByte(), minute.toByte(), second.toByte())
        runCatching { transceive(NiimbotPacket(Opcodes.SET_RTC, d), Opcodes.R_SET_RTC) }
    }

    // ---------------------------------------------------------------- printing

    /**
     * Prints [bitmap] [copies] times. Returns only once the printer's own page
     * counter confirms completion -- a timeout is a FAILURE, never a silent success.
     *
     * ### What `copies` means
     * `copies` is sent once, in `SetPageSize`'s copies field (`docs/PROTOCOL.md` section
     * 4) -- the printer treats the whole run as **one hardware transaction** and
     * [PrintResult.complete] only turns `true` when its page counter confirms all
     * `copies` were printed. **This is the recommended, validated way to print N
     * identical copies**: one `printBitmap(bitmap, copies = N)` call.
     *
     * The alternative -- calling this N times with `copies = 1` -- is a real, supported
     * option when you need **per-copy granularity**: e.g. reporting "4 of 6 succeeded,
     * retry the other 2" instead of one all-or-nothing result, or checking
     * [NiimbotPrinter.readRfid]'s remaining count between copies. The cost is N separate
     * hardware round-trips (roughly 0.9s of wire time plus ~3s of physical printing each,
     * per `docs/PROTOCOL.md`) instead of one, and N separate opportunities for a mid-batch
     * failure (paper door, out of range) to interrupt the run. Pick single-call `copies =
     * N` unless you specifically need that per-copy retry/audit granularity.
     */
    suspend fun printBitmap(bitmap: Bitmap, density: Int = 3, labelType: Int = 1, copies: Int = 1): PrintResult {
        require(density in 1..5) { "density must be 1..5" }
        require(copies in 1..0xFFFF) { "copies out of range" }
        val started = System.currentTimeMillis()
        val rows = bitmap.height
        val cols = bitmap.width
        require(cols <= ImageEncoder.MAX_WIDTH_PX) { "width $cols exceeds ${ImageEncoder.MAX_WIDTH_PX} px printhead" }

        val encoded = ImageEncoder.encode(bitmap)
        log("Encoding ${cols}x${rows} -> ${encoded.size} row packets, density=$density copies=$copies")

        transceive(NiimbotPacket(Opcodes.SET_LABEL_TYPE, byteArrayOf(labelType.toByte())), Opcodes.R_SET_LABEL)
        transceive(NiimbotPacket(Opcodes.SET_DENSITY, byteArrayOf(density.toByte())), Opcodes.R_SET_DENSITY)

        // PrintStart: totalPages(u16 BE) + 00 00 00 00 00 01 00
        val ps = byteArrayOf((copies shr 8).toByte(), copies.toByte(), 0, 0, 0, 0, 0, 1, 0)
        transceive(NiimbotPacket(Opcodes.PRINT_START, ps), Opcodes.R_PRINT_START)

        // fire-and-forget status ping, exactly as the official app does
        runCatching { withTimeout(400) { transceive(NiimbotPacket(Opcodes.PRINT_STATUS, byteArrayOf(1)), Opcodes.R_PRINT_STATUS) } }

        // SetPageSize: rows(u16) cols(u16) copies(u16) + 7 zero bytes
        val sz = byteArrayOf(
            (rows shr 8).toByte(), rows.toByte(),
            (cols shr 8).toByte(), cols.toByte(),
            (copies shr 8).toByte(), copies.toByte(),
            0, 0, 0, 0, 0, 0, 0,
        )
        transceive(NiimbotPacket(Opcodes.SET_PAGE_SIZE, sz), Opcodes.R_SET_PAGE_SIZE)

        for (p in encoded) send(p)                        // rows are never acknowledged

        transceive(NiimbotPacket(Opcodes.PAGE_END, byteArrayOf(1)), Opcodes.R_PAGE_END)

        val confirmed = pollUntilComplete(copies)

        transceive(NiimbotPacket(Opcodes.PRINT_END, byteArrayOf(1)), Opcodes.R_PRINT_END)

        val result = PrintResult(copies, confirmed, System.currentTimeMillis() - started)
        if (!result.complete)
            throw NiimbotException("Print incomplete: printer confirmed $confirmed of $copies page(s)")
        (state.value as? PrinterState.Ready)?.let { _state.value = it }
        return result
    }

    /**
     * Completion is pagesPrinted == totalPages. The progress bytes are NOT usable:
     * the capture shows seven consecutive polls reading 100/100 while the page
     * counter was still 0.
     */
    private suspend fun pollUntilComplete(totalPages: Int, timeoutMs: Long = 30_000L * 1): Int {
        val deadline = System.currentTimeMillis() + timeoutMs * totalPages
        var last = 0
        while (System.currentTimeMillis() < deadline) {
            val st = readStatus()
            last = st.pagesPrinted
            _state.value = PrinterState.Printing(st.pagesPrinted, totalPages, st.progressFeed)
            if (st.pagesPrinted >= totalPages) return st.pagesPrinted
            delay(90)
        }
        return last
    }

    suspend fun readStatus(): PrintStatus {
        val p = transceive(NiimbotPacket(Opcodes.PRINT_STATUS, byteArrayOf(1)), Opcodes.R_PRINT_STATUS)
        val d = p.data
        if (d.size < 8) throw NiimbotException("short status payload (${d.size} bytes)")
        return PrintStatus(be16(d, 0), d[2].toInt() and 0xFF, d[3].toInt() and 0xFF, be16(d, 4), d[7].toInt() != 0, d)
    }

    // ---------------------------------------------------------------- plumbing

    private suspend fun getInfoRaw(key: Int): ByteArray =
        transceive(NiimbotPacket(Opcodes.GET_INFO, byteArrayOf(key.toByte())), Opcodes.GET_INFO + key).data

    private suspend fun getInfoInt(key: Int): Int {
        val d = getInfoRaw(key)
        var v = 0
        for (b in d) v = (v shl 8) or (b.toInt() and 0xFF)
        return v
    }

    private suspend fun send(p: NiimbotPacket) = txLock.withLock {
        log("TX  $p")
        transport.write(p.toBytes())
    }

    private suspend fun transceive(req: NiimbotPacket, expect: Int, timeoutMs: Long = 4000): NiimbotPacket {
        val waiter = scope.async {
            packets.filter { it.type == expect || it.type == Opcodes.R_ERROR }.first()
        }
        send(req)
        val resp = withTimeoutOrNull(timeoutMs) { waiter.await() }
        waiter.cancel()
        if (resp == null) throw NiimbotException("timeout waiting for ${Opcodes.name(expect)} after ${req.name}")
        if (resp.type == Opcodes.R_ERROR) throw NiimbotException("printer returned error after ${req.name}")
        return resp
    }

    private fun be16(d: ByteArray, i: Int) = ((d[i].toInt() and 0xFF) shl 8) or (d[i + 1].toInt() and 0xFF)
}
