package se.spinhaus.core.ble

import se.spinhaus.core.util.ByteReader

data class HeartRateMeasurement(
    val bpm: Int,
    val contactDetected: Boolean?,
    val rrIntervalsMs: List<Int>,
)

object HeartRateParser {
    fun parse(bytes: ByteArray): HeartRateMeasurement? {
        if (bytes.size < 2) return null
        return try {
            val r = ByteReader(bytes)
            val flags = r.u8()
            val bpm = if (flags and 0x01 != 0) r.u16() else r.u8()
            val contactSupported = flags and 0x04 != 0
            val contact = if (contactSupported) flags and 0x02 != 0 else null
            if (flags and 0x08 != 0) r.u16()
            val rr = ArrayList<Int>()
            if (flags and 0x10 != 0) {
                while (r.has(2)) rr.add((r.u16() * 1000.0 / 1024.0).toInt())
            }
            HeartRateMeasurement(bpm, contact, rr)
        } catch (e: IndexOutOfBoundsException) {
            null
        }
    }
}
