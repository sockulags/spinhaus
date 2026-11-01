package se.spinhaus.core.util

/** Little-endian reader for BLE characteristic payloads. */
class ByteReader(private val bytes: ByteArray, start: Int = 0) {
    var pos: Int = start
        private set

    val remaining: Int get() = bytes.size - pos

    fun has(n: Int): Boolean = remaining >= n

    fun u8(): Int = bytes[pos++].toInt() and 0xFF

    fun s8(): Int = bytes[pos++].toInt()

    fun u16(): Int {
        val v = (bytes[pos].toInt() and 0xFF) or ((bytes[pos + 1].toInt() and 0xFF) shl 8)
        pos += 2
        return v
    }

    fun s16(): Int = u16().toShort().toInt()

    fun u24(): Int {
        val v = (bytes[pos].toInt() and 0xFF) or
            ((bytes[pos + 1].toInt() and 0xFF) shl 8) or
            ((bytes[pos + 2].toInt() and 0xFF) shl 16)
        pos += 3
        return v
    }

    fun u32(): Long {
        val v = (bytes[pos].toLong() and 0xFF) or
            ((bytes[pos + 1].toLong() and 0xFF) shl 8) or
            ((bytes[pos + 2].toLong() and 0xFF) shl 16) or
            ((bytes[pos + 3].toLong() and 0xFF) shl 24)
        pos += 4
        return v
    }

    fun skip(n: Int) {
        pos += n
    }
}

fun ByteArray.toHex(): String = joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }

fun hexToBytes(hex: String): ByteArray {
    val clean = hex.replace(" ", "").replace(":", "")
    require(clean.length % 2 == 0) { "Odd hex length" }
    return ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}

/** Little-endian writer. */
class ByteWriter {
    private val out = ArrayList<Byte>()
    fun u8(v: Int) = apply { out.add((v and 0xFF).toByte()) }
    fun u16(v: Int) = apply { u8(v); u8(v shr 8) }
    fun s16(v: Int) = u16(v)
    fun bytes(b: ByteArray) = apply { b.forEach { out.add(it) } }
    fun toByteArray(): ByteArray = out.toByteArray()
}
