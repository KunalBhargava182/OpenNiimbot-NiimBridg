package com.muse.niimbridge.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.muse.niimbot.BleTransport
import com.muse.niimbot.DeviceInfo
import com.muse.niimbot.NiimbotException
import com.muse.niimbot.NiimbotPrinter
import com.muse.niimbot.NiimbotTransport
import com.muse.niimbot.PrinterState
import com.muse.niimbot.RfidInfo
import com.muse.niimbot.SppTransport
import com.muse.niimbridge.logging.LogEntry
import com.muse.niimbridge.logging.PacketLog
import com.muse.niimbridge.render.LabelRenderer
import com.muse.niimbridge.render.LabelStyle
import com.muse.niimbridge.render.StickerData
import com.muse.niimbridge.render.StickerType
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Calendar

/** Shared, activity-scoped: every screen reads/drives the same printer connection and log. */
class PrinterViewModel(app: Application) : AndroidViewModel(app) {

    enum class TransportKind { SPP, BLE }

    data class LabelForm(
        val stickerType: StickerType = StickerType.ICF,
        val studyId: String = "TREBLE-2194",
        val emirId: String = "AIGG.84815912",
        val studyName: String = "TreBle Respire Study",
        val rotationDegrees: Int = 90,
        val style: LabelStyle = LabelStyle(),
    )

    data class PrintSettings(
        val density: Int = 3,
        val labelType: Int = 1,
        val copies: Int = 1,
    )

    sealed class PrintOutcome {
        data class Success(val copies: Int, val elapsedMs: Long) : PrintOutcome()
        data class Failure(val message: String) : PrintOutcome()
    }

    /** Progress marker for a "print all 6" batch: (index printed so far, total). Null when idle. */
    data class BatchProgress(val done: Int, val total: Int, val currentType: StickerType?)

    private val prefs = app.getSharedPreferences("niimbridge", Context.MODE_PRIVATE)
    val packetLog = PacketLog()

    val bluetoothAdapter: BluetoothAdapter? =
        (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val _transportKind = MutableStateFlow(
        runCatching { TransportKind.valueOf(prefs.getString(KEY_TRANSPORT, null) ?: "") }
            .getOrDefault(TransportKind.SPP),
    )
    val transportKind: StateFlow<TransportKind> = _transportKind.asStateFlow()

    fun setTransportKind(kind: TransportKind) {
        _transportKind.value = kind
        prefs.edit().putString(KEY_TRANSPORT, kind.name).apply()
    }

    var lastDeviceAddress: String?
        get() = prefs.getString(KEY_DEVICE, null)
        set(value) {
            prefs.edit().putString(KEY_DEVICE, value).apply()
        }

    var printer: NiimbotPrinter? = null
        private set

    /** Exposed only so the Packet Console can send arbitrary raw frames for debugging. */
    var transport: NiimbotTransport? = null
        private set

    private val _printerState = MutableStateFlow<PrinterState>(PrinterState.Disconnected)
    val printerState: StateFlow<PrinterState> = _printerState.asStateFlow()

    private val _deviceInfo = MutableStateFlow<DeviceInfo?>(null)
    val deviceInfo: StateFlow<DeviceInfo?> = _deviceInfo.asStateFlow()

    private val _rfidInfo = MutableStateFlow<RfidInfo?>(null)
    val rfidInfo: StateFlow<RfidInfo?> = _rfidInfo.asStateFlow()

    private val _connectError = MutableStateFlow<String?>(null)
    val connectError: StateFlow<String?> = _connectError.asStateFlow()

    private val _labelForm = MutableStateFlow(LabelForm())
    val labelForm: StateFlow<LabelForm> = _labelForm.asStateFlow()

    private val _printSettings = MutableStateFlow(PrintSettings())
    val printSettings: StateFlow<PrintSettings> = _printSettings.asStateFlow()

    private val _lastResult = MutableStateFlow<PrintOutcome?>(null)
    val lastResult: StateFlow<PrintOutcome?> = _lastResult.asStateFlow()

    private val _batchProgress = MutableStateFlow<BatchProgress?>(null)
    val batchProgress: StateFlow<BatchProgress?> = _batchProgress.asStateFlow()

    private var stateJob: Job? = null

    fun updateLabelForm(transform: (LabelForm) -> LabelForm) {
        _labelForm.value = transform(_labelForm.value)
    }

    fun updatePrintSettings(transform: (PrintSettings) -> PrintSettings) {
        _printSettings.value = transform(_printSettings.value)
    }

    fun currentStickerData(): StickerData = _labelForm.value.let {
        StickerData(it.stickerType, it.studyId, it.emirId, it.studyName)
    }

    @SuppressLint("MissingPermission")
    fun connect(kind: TransportKind, device: BluetoothDevice) {
        viewModelScope.launch {
            _connectError.value = null
            printer?.disconnect()
            lastDeviceAddress = device.address
            setTransportKind(kind)

            try {
                val t: NiimbotTransport = when (kind) {
                    TransportKind.SPP -> {
                        val adapter = bluetoothAdapter ?: throw NiimbotException("Bluetooth is not available on this device")
                        SppTransport(adapter, device, viewModelScope)
                    }
                    TransportKind.BLE -> BleTransport(getApplication(), device, viewModelScope)
                }
                transport = t
                val p = NiimbotPrinter(t, viewModelScope) { line -> packetLog.appendPrinterLine(line) }
                printer = p
                stateJob?.cancel()
                stateJob = viewModelScope.launch { p.state.collect { _printerState.value = it } }

                val info = p.connect()
                _deviceInfo.value = info

                val now = Calendar.getInstance()
                runCatching {
                    p.setClock(
                        now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH),
                        now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), now.get(Calendar.SECOND),
                    )
                }
                refreshRfid()
            } catch (e: Exception) {
                _connectError.value = e.message ?: "Connect failed"
                printer = null
                transport = null
                _printerState.value = PrinterState.Failed(e.message ?: "Connect failed")
            }
        }
    }

    fun disconnect() {
        stateJob?.cancel()
        printer?.disconnect()
        printer = null
        transport = null
        _printerState.value = PrinterState.Disconnected
        _deviceInfo.value = null
        _rfidInfo.value = null
    }

    /** Parses free-form hex ("55 55 40 01 08" or "555540 0108") and writes it straight to the transport. */
    fun sendRawHex(hex: String) {
        val clean = hex.trim().replace(Regex("[^0-9a-fA-F]"), "")
        if (clean.isEmpty()) return
        if (clean.length % 2 != 0) {
            packetLog.append(LogEntry.Direction.INFO, "Invalid hex: odd number of digits")
            return
        }
        val bytes = ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        val t = transport
        if (t == null) {
            packetLog.append(LogEntry.Direction.INFO, "Not connected")
            return
        }
        viewModelScope.launch {
            packetLog.append(LogEntry.Direction.TX, "RAW " + bytes.joinToString(" ") { "%02X".format(it) })
            runCatching { t.write(bytes) }
                .onFailure { packetLog.append(LogEntry.Direction.INFO, "send failed: ${it.message}") }
        }
    }

    fun refreshDeviceInfo() {
        viewModelScope.launch {
            runCatching { printer?.readDeviceInfo() }.getOrNull()?.let { _deviceInfo.value = it }
        }
    }

    fun refreshRfid() {
        viewModelScope.launch {
            runCatching { printer?.readRfid() }.getOrNull()?.let { _rfidInfo.value = it }
        }
    }

    fun printCurrent() {
        val p = printer
        if (p == null) {
            _lastResult.value = PrintOutcome.Failure("Not connected")
            return
        }
        viewModelScope.launch {
            _lastResult.value = null
            val settings = _printSettings.value
            val form = _labelForm.value
            val bitmap = LabelRenderer.render(currentStickerData(), form.style, form.rotationDegrees)
            try {
                val result = p.printBitmap(bitmap, settings.density, settings.labelType, settings.copies)
                _lastResult.value = PrintOutcome.Success(result.pagesConfirmed, result.elapsedMs)
                refreshRfid()
            } catch (e: Exception) {
                _lastResult.value = PrintOutcome.Failure(e.message ?: "Print failed")
            }
        }
    }

    fun printAllSix() {
        val p = printer
        if (p == null) {
            _lastResult.value = PrintOutcome.Failure("Not connected")
            return
        }
        viewModelScope.launch {
            _lastResult.value = null
            val settings = _printSettings.value
            val types = StickerType.entries
            val started = System.currentTimeMillis()
            for ((index, type) in types.withIndex()) {
                _batchProgress.value = BatchProgress(index, types.size, type)
                val sticker = currentStickerData().copy(stickerType = type)
                val form = _labelForm.value
                val bitmap = LabelRenderer.render(sticker, form.style, form.rotationDegrees)
                try {
                    p.printBitmap(bitmap, settings.density, settings.labelType, settings.copies)
                } catch (e: Exception) {
                    _batchProgress.value = null
                    _lastResult.value = PrintOutcome.Failure("Sticker $type (${index + 1}/${types.size}): ${e.message}")
                    return@launch
                }
            }
            _batchProgress.value = null
            _lastResult.value = PrintOutcome.Success(types.size, System.currentTimeMillis() - started)
            refreshRfid()
        }
    }

    override fun onCleared() {
        printer?.disconnect()
    }

    companion object {
        private const val KEY_TRANSPORT = "transport_kind"
        private const val KEY_DEVICE = "last_device_address"
    }
}
