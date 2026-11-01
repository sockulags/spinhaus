package se.spinhaus.core.ble

import se.spinhaus.core.util.ByteReader

data class CyclingPowerMeasurement(
    val powerW: Int,
    val crankRevolutions: Int?,
    val lastCrankEventTime1024: Int?,
)

object CyclingPowerParser {
    fun parse(bytes: ByteArray): CyclingPowerMeasurement? {
        if (bytes.size < 4) return null
        return try {
            val r = ByteReader(bytes)
            val flags = r.u16()
            val power = r.s16()
            if (flags and 0x01 != 0) r.u8() // pedal power balance
            if (flags and 0x04 != 0) r.u16() // accumulated torque
            if (flags and 0x10 != 0) { r.u32(); r.u16() } // wheel revolution data
            var revs: Int? = null
            var time: Int? = null
            if (flags and 0x20 != 0) { revs = r.u16(); time = r.u16() }
            CyclingPowerMeasurement(power, revs, time)
        } catch (e: IndexOutOfBoundsException) {
            null
        }
    }
}

/** Derives cadence from cumulative crank revolution data (CPS). */
class CrankCadenceCalculator {
    private var lastRevs: Int? = null
    private var lastTime: Int? = null
    private var lastChangeAtMs: Long = 0
    var cadenceRpm: Double = 0.0
        private set

    fun update(revs: Int, eventTime1024: Int, nowMs: Long): Double {
        val pr = lastRevs
        val pt = lastTime
        if (pr != null && pt != null) {
            val dRevs = (revs - pr) and 0xFFFF
            val dTime = (eventTime1024 - pt) and 0xFFFF
            if (dRevs > 0 && dTime > 0) {
                cadenceRpm = dRevs * 60.0 * 1024.0 / dTime
                lastChangeAtMs = nowMs
            } else if (nowMs - lastChangeAtMs > 3000) {
                cadenceRpm = 0.0
            }
        } else {
            lastChangeAtMs = nowMs
        }
        lastRevs = revs
        lastTime = eventTime1024
        return cadenceRpm
    }
}
