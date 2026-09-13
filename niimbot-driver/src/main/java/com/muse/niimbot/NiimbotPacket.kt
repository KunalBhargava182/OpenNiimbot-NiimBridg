package com.muse.niimbot

/**
 * NIIMBOT wire frame.
 *
 *   0x55 0x55 | type(u8) | len(u8) | data[len] | checksum(u8) | 0xAA 0xAA
 *   checksum = type XOR len XOR every data byte
 *
 * Verified against an HCI snoop capture of the official app driving a D110_M.
 */
data class NiimbotPacket(val type: Int, val data: ByteArray) {

    fun toBytes(): ByteArray {
        val len = data.size
        require(len <= 0xFF) { "packet payload too long: $len" }
        var cks = type xor len
        for (b in data) cks = cks xor (b.toInt() and 0xFF)
        val out = ByteArray(len + 7)
        out[0] = 0x55; out[1] = 0x55
        out[2] = type.toByte(); out[3] = len.toByte()
        System.arraycopy(data, 0, out, 4, len)
        out[4 + len] = cks.toByte()
        out[5 + len] = 0xAA.toByte(); out[6 + len] = 0xAA.toByte()
        return out
    }

    val name: String get() = Opcodes.name(type)

    fun toHex(): String = toBytes().joinToString(" ") { "%02X".format(it) }

    override fun toString() = "$name(0x%02X) [${data.size}] ${data.joinToString("") { "%02x".format(it) }}".format(type)

    override fun equals(other: Any?): Boolean =
        other is NiimbotPacket && other.type == type && other.data.contentEquals(data)

    override fun hashCode(): Int = 31 * type + data.contentHashCode()
}
