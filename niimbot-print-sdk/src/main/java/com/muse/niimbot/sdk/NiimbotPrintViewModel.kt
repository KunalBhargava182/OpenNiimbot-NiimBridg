package com.muse.niimbot.sdk

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.muse.niimbot.NiimbotException
import com.muse.niimbot.NiimbotPrinter
import com.muse.niimbot.PrinterState
import com.muse.niimbot.SppTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Fixed print parameters for this SDK's single locked-down flow. */
internal object FixedPrintSettings {
    const val DENSITY = 4
    const val LABEL_TYPE = 1
}

internal class NiimbotPrintViewModel(app: Application) : AndroidViewModel(app) {

    sealed class PrintOutcome {
        data class Success(val elapsedMs: Long) : PrintOutcome()
        data class Failure(val message: String) : PrintOutcome()
    }

    private val prefs = app.getSharedPreferences("niimbot_print_sdk", Context.MODE_PRIVATE)

    val bluetoothAdapter: BluetoothAdapter? =
        (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    var lastDeviceAddress: String?
        get() = prefs.getString(KEY_DEVICE, null)
        private set(value) {
            prefs.edit().putString(KEY_DEVICE, value).apply()
        }

    private var printer: NiimbotPrinter? = null

    private val _printerState = MutableStateFlow<PrinterState>(PrinterState.Disconnected)
    val printerState: StateFlow<PrinterState> = _printerState.asStateFlow()

    private val _lastResult = MutableStateFlow<PrintOutcome?>(null)
    val lastResult: StateFlow<PrintOutcome?> = _lastResult.asStateFlow()

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        viewModelScope.launch {
            printer?.disconnect()
            try {
                val adapter = bluetoothAdapter ?: throw NiimbotException("Bluetooth is not available on this device")
                val transport = SppTransport(adapter, device, viewModelScope)
                val p = NiimbotPrinter(transport, viewModelScope)
                printer = p
                viewModelScope.launch { p.state.collect { _printerState.value = it } }
                p.connect()
                lastDeviceAddress = device.address
            } catch (e: Exception) {
                printer = null
                _printerState.value = PrinterState.Failed(e.message ?: "Connect failed")
            }
        }
    }

    fun print(studyId: String, emirId: String, copies: Int) {
        val p = printer
        if (p == null) {
            _lastResult.value = PrintOutcome.Failure("Not connected")
            return
        }
        viewModelScope.launch {
            _lastResult.value = null
            try {
                val bitmap = StickerRenderer.render(studyId, emirId)
                val result = p.printBitmap(
                    bitmap,
                    density = FixedPrintSettings.DENSITY,
                    labelType = FixedPrintSettings.LABEL_TYPE,
                    copies = copies,
                )
                _lastResult.value = PrintOutcome.Success(result.elapsedMs)
            } catch (e: Exception) {
                _lastResult.value = PrintOutcome.Failure(e.message ?: "Print failed")
            }
        }
    }

    fun disconnect() {
        printer?.disconnect()
        printer = null
        _printerState.value = PrinterState.Disconnected
    }

    override fun onCleared() {
        printer?.disconnect()
    }

    companion object {
        private const val KEY_DEVICE = "last_device_address"
    }
}
