package com.muse.niimbot

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Golden tests against the fixed examples decoded from the HCI capture in
 * docs/PROTOCOL.md section 5 -- these bytes are not invented, they are what the
 * official app put on the wire.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class ImageEncoderTest {

    private fun hex(s: String) = ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    private fun blankLabel(): Bitmap =
        Bitmap.createBitmap(ImageEncoder.MAX_WIDTH_PX, 320, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }

    private fun paintPackedRow(bmp: Bitmap, y: Int, packedRow: ByteArray) {
        for (x in 0 until bmp.width) {
            val bit = (packedRow[x / 8].toInt() ushr (7 - x % 8)) and 1
            if (bit == 1) bmp.setPixel(x, y, Color.BLACK)
        }
    }

    @Test
    fun `popcounts of the captured row 199 example is 4, 6, 12`() {
        val row = hex("0003c00007c00001fff00000")
        assertArrayEquals(intArrayOf(4, 6, 12), ImageEncoder.popcounts(row))
    }

    @Test
    fun `golden test - row 199 encodes byte-identically to the capture`() {
        val row199 = hex("0003c00007c00001fff00000")
        val bmp = blankLabel()
        paintPackedRow(bmp, 199, row199)

        val packets = ImageEncoder.encode(bmp)

        assertEquals(3, packets.size)

        assertEquals(Opcodes.ROW_EMPTY, packets[0].type)
        assertArrayEquals(byteArrayOf(0x00, 0x00, 199.toByte()), packets[0].data)

        assertEquals(Opcodes.ROW_BITMAP, packets[1].type)
        val expectedRowPayload = byteArrayOf(0x00, 0xC7.toByte(), 0x04, 0x06, 0x0C, 0x01) + row199
        assertArrayEquals(expectedRowPayload, packets[1].data)

        assertEquals(Opcodes.ROW_EMPTY, packets[2].type)
        assertArrayEquals(byteArrayOf(0x00, 200.toByte(), 120.toByte()), packets[2].data)
    }

    @Test
    fun `golden test - a five pixel row matches the row 70 indexed capture`() {
        val bmp = blankLabel()
        for (x in 56..60) bmp.setPixel(x, 70, Color.BLACK)

        val packets = ImageEncoder.encode(bmp)
        val row = packets.first { it.type == Opcodes.ROW_INDEXED }

        val expected = byteArrayOf(
            0x00, 70,                   // rowIndex = 70
            0x00, 0x05, 0x00,           // popcounts: all 5 pixels fall in the middle third
            0x01,                       // repeat
            0x00, 0x38, 0x00, 0x39, 0x00, 0x3A, 0x00, 0x3B, 0x00, 0x3C, // x = 56..60
        )
        assertArrayEquals(expected, row.data)
    }

    @Test
    fun `an entirely blank label collapses into RowEmpty runs no longer than 255`() {
        val packets = ImageEncoder.encode(blankLabel())

        assertEquals(2, packets.size)
        assertEquals(Opcodes.ROW_EMPTY, packets[0].type)
        assertArrayEquals(byteArrayOf(0x00, 0x00, 0xFF.toByte()), packets[0].data)
        assertEquals(Opcodes.ROW_EMPTY, packets[1].type)
        assertArrayEquals(byteArrayOf(0x00, 0xFF.toByte(), 65), packets[1].data)
    }

    @Test
    fun `six or more black pixels use the bitmap encoding, not indexed`() {
        val bmp = blankLabel()
        for (x in 0 until 6) bmp.setPixel(x, 0, Color.BLACK)

        val row = ImageEncoder.encode(bmp).first { it.data.size >= 2 && (((it.data[0].toInt() and 0xFF) shl 8) or (it.data[1].toInt() and 0xFF)) == 0 }

        assertEquals(Opcodes.ROW_BITMAP, row.type)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a bitmap wider than the 96px printhead`() {
        ImageEncoder.encode(Bitmap.createBitmap(97, 10, Bitmap.Config.ARGB_8888))
    }
}
