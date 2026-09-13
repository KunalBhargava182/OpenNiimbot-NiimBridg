package com.muse.niimbot

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue

/**
 * BLE GATT transparent-serial transport.
 *
 * UNVERIFIED FALLBACK. The official app does NOT use BLE on the D110_M -- the HCI
 * capture contains zero ATT traffic. These UUIDs come from community sources and
 * are believed correct for most NIIMBOT models. Try SPP first.
 */
@SuppressLint("MissingPermission")
class BleTransport(
    private val context: Context,
    private val device: BluetoothDevice,
    private val scope: CoroutineScope,
) : NiimbotTransport {

    companion object {
        val SERVICE: UUID = UUID.fromString("e7810a71-73ae-499d-8c15-faa9aef0c3f2")
        val CHARACTERISTIC: UUID = UUID.fromString("bef8d6c9-9c21-4c9e-b632-bd58c1009f9f")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val TARGET_MTU = 247
        private const val INTER_PACKET_DELAY_MS = 4L
    }

    private var gatt: BluetoothGatt? = null
    private var chr: BluetoothGattCharacteristic? = null
    private var mtu = 23
    private val ready = LinkedBlockingQueue<Result<Unit>>(1)
    private val writeDone = LinkedBlockingQueue<Boolean>(1)

    private val _inbound = MutableSharedFlow<ByteArray>(
        replay = 0, extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.SUSPEND
    )

    override val name = "BLE(${device.address})"
    override val isConnected get() = chr != null
    override val inbound = _inbound.asSharedFlow()

    private val cb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) g.requestMtu(TARGET_MTU)
            else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                chr = null; ready.offer(Result.failure(NiimbotException("BLE disconnected")))
            }
        }
        override fun onMtuChanged(g: BluetoothGatt, m: Int, status: Int) {
            mtu = m; g.discoverServices()
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val c = g.getService(SERVICE)?.getCharacteristic(CHARACTERISTIC)
            if (c == null) { ready.offer(Result.failure(NiimbotException("NIIMBOT GATT service not found"))); return }
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            chr = c
            g.setCharacteristicNotification(c, true)
            val d = c.getDescriptor(CCCD)
            if (d != null) {
                @Suppress("DEPRECATION")
                d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION") g.writeDescriptor(d)
            } else ready.offer(Result.success(Unit))
        }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            ready.offer(Result.success(Unit))
        }
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            c.value?.let { v -> scope.launch { _inbound.emit(v.copyOf()) } }
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            scope.launch { _inbound.emit(value.copyOf()) }
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeDone.offer(status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    /**
     * Idempotent: a second call on an already-connected transport is a no-op rather than
     * opening a second GATT connection and leaking the original `gatt` reference. See
     * `SppTransport.connect()` for why this guard matters for callers that pre-connect a
     * transport themselves (e.g. `NiimbotPrinterConnector`) before handing it to
     * [NiimbotPrinter].
     */
    override suspend fun connect() = withContext(Dispatchers.IO) {
        if (isConnected) return@withContext

        ready.clear()
        gatt = device.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
        val r = withTimeoutOrNull(15_000) { withContext(Dispatchers.IO) { ready.take() } }
            ?: throw NiimbotException("BLE connect timed out")
        r.getOrThrow()
    }

    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        val g = gatt ?: throw NiimbotException("Not connected")
        val c = chr ?: throw NiimbotException("Not connected")
        val max = (mtu - 3).coerceAtLeast(20)
        var off = 0
        while (off < bytes.size) {
            val n = minOf(max, bytes.size - off)
            val part = bytes.copyOfRange(off, off + n)
            @Suppress("DEPRECATION") run { c.value = part }
            @Suppress("DEPRECATION") g.writeCharacteristic(c)
            off += n
            delay(INTER_PACKET_DELAY_MS)
        }
    }

    override fun close() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null; chr = null
    }
}
