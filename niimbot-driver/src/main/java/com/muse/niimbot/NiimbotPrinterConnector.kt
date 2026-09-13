package com.muse.niimbot

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import kotlinx.coroutines.CoroutineScope

/**
 * Finds and connects to a paired NIIMBOT printer over SPP, with the fallback behavior
 * [BluetoothAdapter.getBondedDevices] alone doesn't give you: that call reflects pairing,
 * not reachability, so a paired-but-powered-off printer still shows up in it. This tries
 * the last successfully-connected device first, then falls through every other paired
 * NIIMBOT-looking device in turn, skipping ones that fail to connect instead of giving up
 * on the first candidate.
 *
 * Public since 1.1 -- part of the headless API. Used internally by `NiimbotPrintSession`
 * (in niimbot-print-sdk) and available directly here if you need finer control over
 * device selection than that wrapper gives you.
 */
class NiimbotPrinterConnector(
    private val context: Context,
    private val store: LastDeviceStore = SharedPreferencesLastDeviceStore(context),
) {

    /**
     * Pluggable persistence for the last-connected device's address. Swap this for your
     * own store (a database, a sync-backed preference, etc.) if `SharedPreferences`
     * doesn't fit; [SharedPreferencesLastDeviceStore] covers the common case.
     */
    interface LastDeviceStore {
        var lastDeviceAddress: String?
    }

    /** Default [LastDeviceStore]: one `SharedPreferences` file, private to this app. */
    class SharedPreferencesLastDeviceStore(context: Context) : LastDeviceStore {
        private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        override var lastDeviceAddress: String?
            get() = prefs.getString(KEY_ADDRESS, null)
            set(value) {
                prefs.edit().putString(KEY_ADDRESS, value).apply()
            }

        private companion object {
            const val PREFS_NAME = "niimbot_connector"
            const val KEY_ADDRESS = "last_device_address"
        }
    }

    /** The device this connector last successfully connected to, if any. */
    val lastDeviceAddress: String? get() = store.lastDeviceAddress

    /**
     * Bonded devices that look like a NIIMBOT printer (name contains "niimbot" or "d110",
     * case-insensitive), sorted with the last-used device first when it's among them.
     *
     * This reflects pairing, not reachability -- a powered-off printer is still in this
     * list. Use [connect] to actually find one that responds right now.
     */
    @SuppressLint("MissingPermission")
    fun bondedCandidates(): List<BluetoothDevice> {
        val adapter = bluetoothAdapter() ?: return emptyList()
        val remembered = store.lastDeviceAddress
        return adapter.bondedDevices
            .filter { it.looksLikeNiimbot() }
            .sortedByDescending { it.address == remembered }
    }

    /**
     * Tries [bondedCandidates] in order -- last-used first -- and returns the first
     * [NiimbotTransport] that actually connects. A candidate that fails to connect
     * (powered off, out of range) is skipped, not treated as a fatal error, so one
     * unreachable paired printer never prevents this from finding another that works.
     *
     * Throws [NiimbotException] only when every candidate failed, Bluetooth is
     * unavailable/disabled, or no NIIMBOT-looking device is paired at all. On success,
     * the connected device's address is persisted via [LastDeviceStore] so the next call
     * tries it first.
     */
    @SuppressLint("MissingPermission")
    suspend fun connect(scope: CoroutineScope): NiimbotTransport {
        val adapter = bluetoothAdapter() ?: throw NiimbotException("Bluetooth is not available on this device")
        if (!adapter.isEnabled) throw NiimbotException("Bluetooth is turned off")

        val candidates = bondedCandidates()
        if (candidates.isEmpty()) {
            throw NiimbotException("No paired NIIMBOT printer found -- pair it in Android Bluetooth settings first")
        }

        var lastError: Exception? = null
        for (device in candidates) {
            val transport = SppTransport(adapter, device, scope)
            try {
                transport.connect()
                store.lastDeviceAddress = device.address
                return transport
            } catch (e: Exception) {
                lastError = e
                runCatching { transport.close() }
            }
        }
        throw NiimbotException(
            "No paired NIIMBOT printer could be reached (tried ${candidates.size} device(s))",
            lastError,
        )
    }

    private fun bluetoothAdapter(): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun BluetoothDevice.looksLikeNiimbot(): Boolean {
        val n = name?.lowercase().orEmpty()
        return n.contains("niimbot") || n.contains("d110")
    }
}
