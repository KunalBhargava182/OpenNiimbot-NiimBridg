package com.muse.niimbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PacketBufferTest {

    @Test
    fun `reassembles a frame whose trailing sync word arrives in a separate read`() {
        // The real fragmentation pattern observed in the capture: everything up to
        // (not including) the trailing 0xAA 0xAA arrives first, "aaaa" arrives next.
        val packet = NiimbotPacket(Opcodes.GET_INFO, byteArrayOf(0x08))
        val bytes = packet.toBytes()
        val buffer = PacketBuffer()

        buffer.append(bytes.copyOfRange(0, bytes.size - 2))
        assertTrue("must not emit a partial frame", buffer.drain().isEmpty())

        buffer.append(bytes.copyOfRange(bytes.size - 2, bytes.size))
        assertEquals(listOf(packet), buffer.drain())
    }

    @Test
    fun `reassembles a frame fragmented one byte at a time`() {
        val packet = NiimbotPacket(Opcodes.GET_RFID, byteArrayOf(0x01))
        val bytes = packet.toBytes()
        val buffer = PacketBuffer()

        for (i in 0 until bytes.size - 1) {
            buffer.append(byteArrayOf(bytes[i]))
            assertTrue(buffer.drain().isEmpty())
        }
        buffer.append(byteArrayOf(bytes.last()))
        assertEquals(listOf(packet), buffer.drain())
    }

    @Test
    fun `drains multiple back-to-back frames delivered in a single read`() {
        val a = NiimbotPacket(Opcodes.HEARTBEAT, byteArrayOf(0x04))
        val b = NiimbotPacket(Opcodes.PRINT_STATUS, byteArrayOf(0x01))
        val c = NiimbotPacket(Opcodes.PAGE_END, byteArrayOf(0x01))
        val buffer = PacketBuffer()

        buffer.append(a.toBytes() + b.toBytes() + c.toBytes())

        assertEquals(listOf(a, b, c), buffer.drain())
    }

    @Test
    fun `resynchronises past a corrupted frame to recover the next good one`() {
        val bad = NiimbotPacket(Opcodes.GET_RFID, byteArrayOf(0x01)).toBytes()
        val checksumIndex = bad.size - 3
        bad[checksumIndex] = (bad[checksumIndex] + 1).toByte() // corrupt the checksum only
        val good = NiimbotPacket(Opcodes.PRINT_END, byteArrayOf(0x01))
        val buffer = PacketBuffer()

        buffer.append(bad + good.toBytes())

        assertEquals(listOf(good), buffer.drain())
    }

    @Test
    fun `interleaved partial appends across many small chunks reassemble in order`() {
        val a = NiimbotPacket(Opcodes.SET_DENSITY, byteArrayOf(0x03))
        val b = NiimbotPacket(Opcodes.SET_LABEL_TYPE, byteArrayOf(0x01))
        val stream = a.toBytes() + b.toBytes()
        val buffer = PacketBuffer()

        val drained = ArrayList<NiimbotPacket>()
        for (byte in stream) {
            buffer.append(byteArrayOf(byte))
            drained += buffer.drain()
        }

        assertEquals(listOf(a, b), drained)
    }

    @Test
    fun `clear discards any partially buffered frame`() {
        val packet = NiimbotPacket(Opcodes.HEARTBEAT, byteArrayOf(0x04))
        val buffer = PacketBuffer()
        buffer.append(packet.toBytes().copyOfRange(0, 3))

        buffer.clear()
        buffer.append(packet.toBytes())

        assertEquals(listOf(packet), buffer.drain())
    }

    @Test
    fun `leading garbage before the first sync word is discarded`() {
        val packet = NiimbotPacket(Opcodes.HEARTBEAT, byteArrayOf(0x04))
        val buffer = PacketBuffer()

        buffer.append(byteArrayOf(0x00, 0x11, 0x22) + packet.toBytes())

        assertEquals(listOf(packet), buffer.drain())
    }
}
