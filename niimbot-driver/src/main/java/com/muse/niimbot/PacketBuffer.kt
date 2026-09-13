package com.muse.niimbot

/**
 * Fragmentation-safe accumulator.
 *
 * Frames arrive split across RFCOMM/L2CAP boundaries -- the capture shows
 * `5555...16` and `aaaa` delivered as two separate reads. Feed every inbound
 * chunk here and drain whatever complete frames are available.
 *
 * NOTE: the Python reference (niimprint `_recv`) does not break out of its parse
 * loop on a partial packet and spins forever. This does.
 */
class PacketBuffer {

    private val buf = ArrayDeque<Byte>()
    private val scratch = ArrayList<Byte>()

    @Synchronized
    fun append(chunk: ByteArray) { for (b in chunk) buf.addLast(b) }

    /** Returns every complete, checksum-valid frame currently in the buffer. */
    @Synchronized
    fun drain(): List<NiimbotPacket> {
        val out = ArrayList<NiimbotPacket>()
        while (true) {
            // resync to the next 0x55 0x55
            while (buf.size >= 2 && !(peek(0) == 0x55 && peek(1) == 0x55)) buf.removeFirst()
            if (buf.size < 7) break

            val type = peek(2)
            val len = peek(3)
            val total = len + 7
            if (buf.size < total) break                 // incomplete -- wait for more

            scratch.clear()
            var cks = type xor len
            for (i in 0 until len) {
                val b = peek(4 + i)
                scratch.add(b.toByte())
                cks = cks xor b
            }
            val ok = cks == peek(4 + len) && peek(5 + len) == 0xAA && peek(6 + len) == 0xAA
            if (ok) {
                out.add(NiimbotPacket(type, scratch.toByteArray()))
                repeat(total) { buf.removeFirst() }
            } else {
                // bad frame -- drop the sync word and rescan
                buf.removeFirst(); buf.removeFirst()
            }
        }
        return out
    }

    @Synchronized fun clear() = buf.clear()

    private fun peek(i: Int): Int = buf.elementAt(i).toInt() and 0xFF
}
