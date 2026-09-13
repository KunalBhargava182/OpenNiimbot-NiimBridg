package com.muse.niimbot

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Bluetooth Classic RFCOMM / SPP.
 *
 * This is the PRIMARY transport: the HCI capture proves the official NIIMBOT app
 * drives the D110_M entirely over Classic SPP, with no BLE traffic at all.
 *
 * Requires the printer to already be bonded (paired in system Bluetooth settings).
 *
 * Public since 1.1 — part of the headless API. [connect] throws [NiimbotException]
 * (not a bare IO exception) on every failure path: unbonded device, socket creation
 * failure, or a failed RFCOMM handshake — catch that one type. For device selection
 * with automatic fallback across multiple paired printers, prefer
 * [NiimbotPrinterConnector] over constructing this directly.
 */
@SuppressLint("MissingPermission")
class SppTransport(
    private val adapter: BluetoothAdapter,
    private val device: BluetoothDevice,
    private val scope: CoroutineScope,
) : NiimbotTransport {

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val CHUNK = 512
        private const val INTER_CHUNK_DELAY_MS = 2L
    }

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var reader: Job? = null

    private val _inbound = MutableSharedFlow<ByteArray>(
        replay = 0, extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.SUSPEND
    )

    override val name = "SPP(${device.address})"
    override val isConnected get() = socket?.isConnected == true
    override val inbound = _inbound.asSharedFlow()

    /**
     * Idempotent: a second call on an already-connected transport is a no-op rather than
     * opening a second `BluetoothSocket` to the same device. This matters for callers like
     * `NiimbotPrinterConnector`, which pre-connects a candidate transport itself to test
     * reachability before handing it to [NiimbotPrinter] -- without this guard,
     * `NiimbotPrinter.connect()` calling this a second time on that same, already-connected
     * transport would silently open a second socket the printer's SPP server rejects,
     * while leaking the first (only ever reclaimed by the finalizer).
     */
    override suspend fun connect() = withContext(Dispatchers.IO) {
        if (isConnected) return@withContext

        if (device.bondState != BluetoothDevice.BOND_BONDED)
            throw NiimbotException("Printer is not paired. Pair it in Android Bluetooth settings first.")

        adapter.cancelDiscovery()   // discovery cripples RFCOMM connect

        val s = try {
            device.createRfcommSocketToServiceRecord(SPP_UUID)
        } catch (e: Exception) {
            throw NiimbotException("Could not create RFCOMM socket", e)
        }
        try {
            s.connect()
        } catch (e: Exception) {
            runCatching { s.close() }
            throw NiimbotException("RFCOMM connect failed to ${device.address}", e)
        }
        socket = s
        input = s.inputStream
        output = s.outputStream

        reader = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(2048)
            try {
                while (isActive) {
                    val n = input?.read(buf) ?: break
                    if (n <= 0) break
                    _inbound.emit(buf.copyOf(n))
                }
            } catch (_: Exception) { /* socket closed */ }
        }
    }

    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        val out = output ?: throw NiimbotException("Not connected")
        var off = 0
        while (off < bytes.size) {
            val n = minOf(CHUNK, bytes.size - off)
            out.write(bytes, off, n)
            off += n
            if (off < bytes.size) delay(INTER_CHUNK_DELAY_MS)
        }
        out.flush()
    }

    override fun close() {
        reader?.cancel()
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        socket = null; input = null; output = null
    }
}
