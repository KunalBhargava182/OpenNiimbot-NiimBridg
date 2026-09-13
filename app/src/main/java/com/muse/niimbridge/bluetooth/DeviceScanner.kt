package com.muse.niimbridge.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Parcelable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Bonded devices plus a live discovery scan, per PROTOCOL.md's "never hardcode a MAC" guidance. */
@SuppressLint("MissingPermission")
class DeviceScanner(private val context: Context, private val adapter: BluetoothAdapter) {

    private val _discovered = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val discovered: StateFlow<List<BluetoothDevice>> = _discovered.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private var receiver: BroadcastReceiver? = null

    fun bondedDevices(): List<BluetoothDevice> = adapter.bondedDevices.toList()

    fun startDiscovery() {
        if (receiver == null) {
            val r = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    when (intent.action) {
                        BluetoothDevice.ACTION_FOUND -> {
                            val device = intent.getDeviceExtra() ?: return
                            if (_discovered.value.none { it.address == device.address }) {
                                _discovered.value = _discovered.value + device
                            }
                        }
                        BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> _isDiscovering.value = false
                    }
                }
            }
            context.registerReceiver(
                r,
                IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_FOUND)
                    addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                },
            )
            receiver = r
        }
        _discovered.value = emptyList()
        adapter.cancelDiscovery()
        adapter.startDiscovery()
        _isDiscovering.value = true
    }

    fun stopDiscovery() {
        if (adapter.isDiscovering) adapter.cancelDiscovery()
        _isDiscovering.value = false
    }

    fun dispose() {
        stopDiscovery()
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }

    private fun Intent.getDeviceExtra(): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra<Parcelable>(BluetoothDevice.EXTRA_DEVICE) as? BluetoothDevice
        }
}
