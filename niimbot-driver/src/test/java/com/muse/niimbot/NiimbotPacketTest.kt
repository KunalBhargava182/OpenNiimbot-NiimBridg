package com.muse.niimbot

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NiimbotPacketTest {

    @Test
    fun `frame layout is sync, type, length, data, xor checksum, sync`() {
        val data = byteArrayOf(0x01, 0x02, 0x03)
        val bytes = NiimbotPacket(0x40, data).toBytes()

        assertEquals(0x55, bytes[0].toInt() and 0xFF)
        assertEquals(0x55, bytes[1].toInt() and 0xFF)
        assertEquals(0x40, bytes[2].toInt() and 0xFF)
        assertEquals(data.size, bytes[3].toInt() and 0xFF)

        val expectedChecksum = 0x40 xor data.size xor 0x01 xor 0x02 xor 0x03
        assertEquals(expectedChecksum, bytes[4 + data.size].toInt() and 0xFF)
        assertEquals(0xAA, bytes[5 + data.size].toInt() and 0xFF)
        assertEquals(0xAA, bytes[6 + data.size].toInt() and 0xFF)
    }

    @Test
    fun `round trips through PacketBuffer unchanged`() {
        val packet = NiimbotPacket(Opcodes.R_GET_RFID, byteArrayOf(0x01, 0x02, 0x03, 0x04))
        val buffer = PacketBuffer()
        buffer.append(packet.toBytes())
        assertEquals(listOf(packet), buffer.drain())
    }

    @Test
    fun `empty payload still produces a valid 7 byte frame`() {
        val bytes = NiimbotPacket(Opcodes.HEARTBEAT, byteArrayOf()).toBytes()
        assertEquals(7, bytes.size)
        // checksum of an empty payload is just type xor 0
        assertEquals(Opcodes.HEARTBEAT, bytes[4].toInt() and 0xFF)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `payload longer than 255 bytes is rejected`() {
        NiimbotPacket(Opcodes.ROW_BITMAP, ByteArray(256)).toBytes()
    }

    @Test
    fun `equality and hashCode are based on type and data content, not identity`() {
        val a = NiimbotPacket(0x01, byteArrayOf(1, 2, 3))
        val b = NiimbotPacket(0x01, byteArrayOf(1, 2, 3))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `toString decodes the opcode name and hex payload`() {
        val packet = NiimbotPacket(Opcodes.PRINT_START, byteArrayOf(0x00, 0x01))
        val text = packet.toString()
        assertTrue(text.contains("PrintStart"))
        assertTrue(text.contains("0001"))
    }

    @Test
    fun `toHex renders the full wire frame`() {
        val bytes = NiimbotPacket(Opcodes.HEARTBEAT, byteArrayOf(0x04)).toBytes()
        val hex = NiimbotPacket(Opcodes.HEARTBEAT, byteArrayOf(0x04)).toHex()
        assertArrayEquals(bytes, hex.split(" ").map { it.toInt(16).toByte() }.toByteArray())
    }
}
