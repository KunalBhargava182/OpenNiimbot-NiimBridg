package com.muse.niimbot

import android.graphics.Bitmap

/**
 * Bitmap -> row packets.
 *
 * VALIDATED: re-encoding the two bitmaps recovered from the official app's HCI
 * capture reproduces its output BYTE-IDENTICALLY (194 and 201 packets, exact match).
 *
 *   blank rows        -> 0x84 RowEmpty    rowIdx(u16) count(u8)
 *   rows with < 6 px  -> 0x83 RowIndexed  rowIdx(u16) popcounts(3) repeat(u8) X positions(u16 each)
 *   everything else   -> 0x85 RowBitmap   rowIdx(u16) popcounts(3) repeat(u8) packed row
 *
 * All three types carry a run-length `repeat` covering identical consecutive rows.
 * The three popcount bytes are the set-bit count of each third of the row --
 * niimprint sends zeros, the official app computes them, and so do we.
 */
object ImageEncoder {

    const val MAX_WIDTH_PX = 96

    /** Rows with fewer than this many black pixels use the indexed encoding. */
    private const val INDEXED_THRESHOLD = 6

    fun encode(bmp: Bitmap, compress: Boolean = true): List<NiimbotPacket> {
        val w = bmp.width
        val h = bmp.height
        require(w <= MAX_WIDTH_PX) { "image width $w exceeds printhead ($MAX_WIDTH_PX px)" }
        require(h in 1..0xFFFF) { "image height $h out of range" }

        val rows = pack(bmp)
        val out = ArrayList<NiimbotPacket>()
        var y = 0
        while (y < h) {
            val row = rows[y]

            // run-length: how many identical rows follow?
            var run = 1
            if (compress) {
                while (y + run < h && run < 255 && rows[y + run].contentEquals(row)) run++
            }

            if (row.all { it.toInt() == 0 }) {
                out.add(emptyRow(y, run))
            } else {
                val pos = positionsOf(row, w)
                if (compress && pos.size < INDEXED_THRESHOLD) out.add(indexedRow(y, row, pos, run))
                else out.add(bitmapRow(y, row, run))
            }
            y += run
        }
        return out
    }

    /** Packs to 1bpp MSB-first. A set bit is a black (printed) pixel. */
    private fun pack(bmp: Bitmap): Array<ByteArray> {
        val w = bmp.width; val h = bmp.height
        val bytesPerRow = (w + 7) / 8
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        return Array(h) { y ->
            val row = ByteArray(bytesPerRow)
            for (x in 0 until w) {
                val p = px[y * w + x]
                val lum = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                if (lum < 128) row[x / 8] = (row[x / 8].toInt() or (0x80 ushr (x % 8))).toByte()
            }
            row
        }
    }

    /** Population count of each third of the row. At 96 px: three groups of 4 bytes. */
    internal fun popcounts(row: ByteArray): IntArray {
        val per = row.size / 3
        val c = IntArray(3)
        for (i in row.indices) {
            val third = if (per == 0) 0 else (i / per).coerceAtMost(2)
            c[third] += Integer.bitCount(row[i].toInt() and 0xFF)
        }
        return c
    }

    private fun positionsOf(row: ByteArray, w: Int): List<Int> {
        val p = ArrayList<Int>()
        for (x in 0 until w) if ((row[x / 8].toInt() shr (7 - x % 8)) and 1 == 1) p.add(x)
        return p
    }

    private fun emptyRow(idx: Int, count: Int) = NiimbotPacket(
        Opcodes.ROW_EMPTY, byteArrayOf((idx shr 8).toByte(), idx.toByte(), count.toByte())
    )

    private fun bitmapRow(idx: Int, row: ByteArray, repeat: Int): NiimbotPacket {
        val c = popcounts(row)
        val d = ByteArray(6 + row.size)
        d[0] = (idx shr 8).toByte(); d[1] = idx.toByte()
        d[2] = c[0].toByte(); d[3] = c[1].toByte(); d[4] = c[2].toByte()
        d[5] = repeat.toByte()
        System.arraycopy(row, 0, d, 6, row.size)
        return NiimbotPacket(Opcodes.ROW_BITMAP, d)
    }

    private fun indexedRow(idx: Int, row: ByteArray, pos: List<Int>, repeat: Int): NiimbotPacket {
        val c = popcounts(row)
        val d = ByteArray(6 + pos.size * 2)
        d[0] = (idx shr 8).toByte(); d[1] = idx.toByte()
        d[2] = c[0].toByte(); d[3] = c[1].toByte(); d[4] = c[2].toByte()
        d[5] = repeat.toByte()
        pos.forEachIndexed { i, x ->
            d[6 + i * 2] = (x shr 8).toByte(); d[7 + i * 2] = x.toByte()
        }
        return NiimbotPacket(Opcodes.ROW_INDEXED, d)
    }
}
