package com.muse.niimbot

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** In-memory transport: decodes what NiimbotPrinter writes and lets the test script canned replies. */
private class FakeTransport(private val scope: CoroutineScope) : NiimbotTransport {
    val sent = mutableListOf<NiimbotPacket>()
    var onSend: (NiimbotPacket) -> NiimbotPacket? = { null }

    private val _inbound = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val name = "fake"
    override val isConnected = true
    override val inbound = _inbound.asSharedFlow()

    override suspend fun connect() {}

    override suspend fun write(bytes: ByteArray) {
        val buf = PacketBuffer()
        buf.append(bytes)
        for (p in buf.drain()) {
            sent += p
            onSend(p)?.let { reply -> scope.launch { _inbound.emit(reply.toBytes()) } }
        }
    }

    override fun close() {}
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class NiimbotPrinterTest {

    @Test
    fun `a 1-copy job sends commands in protocol order and only confirms success on the final poll`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val transport = FakeTransport(scope)
        val printer = NiimbotPrinter(transport, scope)
        var statusCalls = 0

        transport.onSend = { req ->
            when (req.type) {
                Opcodes.GET_INFO -> when (req.data[0].toInt()) {
                    Opcodes.Info.DEVICE_TYPE -> NiimbotPacket(Opcodes.GET_INFO + Opcodes.Info.DEVICE_TYPE, byteArrayOf(0x09, 0x10))
                    Opcodes.Info.SOFT_VERSION -> NiimbotPacket(Opcodes.GET_INFO + Opcodes.Info.SOFT_VERSION, byteArrayOf(0x01, 0x2C))
                    Opcodes.Info.HARD_VERSION -> NiimbotPacket(Opcodes.GET_INFO + Opcodes.Info.HARD_VERSION, byteArrayOf(0x00, 0x64))
                    Opcodes.Info.BATTERY -> NiimbotPacket(Opcodes.GET_INFO + Opcodes.Info.BATTERY, byteArrayOf(0x03))
                    Opcodes.Info.DEVICE_SERIAL -> NiimbotPacket(
                        Opcodes.GET_INFO + Opcodes.Info.DEVICE_SERIAL, "TESTSN0001".toByteArray(Charsets.US_ASCII)
                    )
                    else -> NiimbotPacket(Opcodes.GET_INFO + req.data[0].toInt(), byteArrayOf(0x00))
                }
                Opcodes.SET_LABEL_TYPE -> NiimbotPacket(Opcodes.R_SET_LABEL, byteArrayOf(0x01))
                Opcodes.SET_DENSITY -> NiimbotPacket(Opcodes.R_SET_DENSITY, byteArrayOf(0x01))
                Opcodes.PRINT_START -> NiimbotPacket(Opcodes.R_PRINT_START, byteArrayOf(0x01))
                Opcodes.SET_PAGE_SIZE -> NiimbotPacket(Opcodes.R_SET_PAGE_SIZE, byteArrayOf(0x01, 0x00))
                Opcodes.PAGE_END -> NiimbotPacket(Opcodes.R_PAGE_END, byteArrayOf(0x01))
                Opcodes.PRINT_END -> NiimbotPacket(Opcodes.R_PRINT_END, byteArrayOf(0x01))
                Opcodes.PRINT_STATUS -> {
                    // The real capture: pagesPrinted stays 0 through seven consecutive
                    // 100/100 progress polls, then jumps straight to 1 with no warning.
                    statusCalls++
                    val pagesPrinted = if (statusCalls <= 7) 0 else 1
                    NiimbotPacket(
                        Opcodes.R_PRINT_STATUS,
                        byteArrayOf(0x00, pagesPrinted.toByte(), 100.toByte(), 100.toByte(), 0x22, 0x65, 0x00, 0x01),
                    )
                }
                else -> null
            }
        }

        try {
            printer.connect()
            val bitmap = Bitmap.createBitmap(96, 320, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            val result = printer.printBitmap(bitmap, density = 3, labelType = 1, copies = 1)

            assertTrue("print must confirm completion via the page counter", result.complete)
            assertEquals(1, result.pagesConfirmed)
            assertEquals("exactly 7 zero polls then the final confirming poll", 8, statusCalls)

            val printOrder = transport.sent.map { it.type }.dropWhile { it != Opcodes.SET_LABEL_TYPE }
            assertEquals(
                listOf(
                    Opcodes.SET_LABEL_TYPE, Opcodes.SET_DENSITY, Opcodes.PRINT_START,
                    Opcodes.PRINT_STATUS, Opcodes.SET_PAGE_SIZE,
                ),
                printOrder.take(5),
            )
            val lastRowIndex = printOrder.indexOfLast { it == Opcodes.ROW_EMPTY || it == Opcodes.ROW_BITMAP || it == Opcodes.ROW_INDEXED }
            assertEquals(Opcodes.PAGE_END, printOrder[lastRowIndex + 1])
            assertEquals(Opcodes.PRINT_END, printOrder.last())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a poll timeout is reported as failure, never an optimistic success`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val transport = FakeTransport(scope)
        val printer = NiimbotPrinter(transport, scope)

        transport.onSend = { req ->
            when (req.type) {
                Opcodes.GET_INFO -> NiimbotPacket(Opcodes.GET_INFO + req.data[0].toInt(), byteArrayOf(0x00))
                Opcodes.SET_LABEL_TYPE -> NiimbotPacket(Opcodes.R_SET_LABEL, byteArrayOf(0x01))
                Opcodes.SET_DENSITY -> NiimbotPacket(Opcodes.R_SET_DENSITY, byteArrayOf(0x01))
                Opcodes.PRINT_START -> NiimbotPacket(Opcodes.R_PRINT_START, byteArrayOf(0x01))
                Opcodes.SET_PAGE_SIZE -> NiimbotPacket(Opcodes.R_SET_PAGE_SIZE, byteArrayOf(0x01, 0x00))
                Opcodes.PAGE_END -> NiimbotPacket(Opcodes.R_PAGE_END, byteArrayOf(0x01))
                // Every status poll comes back a printer error -- simulates the paper door
                // opening mid-print. Modeled as an immediate error rather than a real
                // multi-second timeout so the test stays fast; both must fail the job.
                Opcodes.PRINT_STATUS -> NiimbotPacket(Opcodes.R_ERROR, byteArrayOf())
                else -> null
            }
        }

        try {
            printer.connect()
            val bitmap = Bitmap.createBitmap(96, 320, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            var threw = false
            try {
                printer.printBitmap(bitmap, copies = 1)
            } catch (e: NiimbotException) {
                threw = true
            }
            assertTrue("an unanswered status poll must fail the job, not succeed silently", threw)
        } finally {
            scope.cancel()
        }
    }
}
