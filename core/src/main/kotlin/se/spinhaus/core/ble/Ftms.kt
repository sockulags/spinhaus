package se.spinhaus.core.ble

import se.spinhaus.core.util.ByteReader
import se.spinhaus.core.util.ByteWriter
import kotlin.math.roundToInt

/** Parsed FTMS Indoor Bike Data (0x2AD2). Null fields were not present in the packet. */
data class IndoorBikeData(
    val speedKmh: Double? = null,
    val averageSpeedKmh: Double? = null,
    val cadenceRpm: Double? = null,
    val averageCadenceRpm: Double? = null,
    val totalDistanceM: Int? = null,
    val resistanceLevel: Int? = null,
    val powerW: Int? = null,
    val averagePowerW: Int? = null,
    val totalEnergyKcal: Int? = null,
    val heartRateBpm: Int? = null,
    val elapsedTimeS: Int? = null,
    val remainingTimeS: Int? = null,
)

object FtmsParser {
    fun parseIndoorBikeData(bytes: ByteArray): IndoorBikeData? {
        if (bytes.size < 2) return null
        return try {
            val r = ByteReader(bytes)
            val flags = r.u16()
            fun f(bit: Int) = flags and (1 shl bit) != 0
            // Bit 0 "More Data": when 0, Instantaneous Speed IS present.
            val speed = if (!f(0)) r.u16() / 100.0 else null
            val avgSpeed = if (f(1)) r.u16() / 100.0 else null
            val cadence = if (f(2)) r.u16() / 2.0 else null
            val avgCadence = if (f(3)) r.u16() / 2.0 else null
            val distance = if (f(4)) r.u24() else null
            val resistance = if (f(5)) r.s16() else null
            val power = if (f(6)) r.s16() else null
            val avgPower = if (f(7)) r.s16() else null
            var energy: Int? = null
            if (f(8)) {
                energy = r.u16(); r.u16(); r.u8()
            }
            val hr = if (f(9)) r.u8() else null
            if (f(10)) r.u8()
            val elapsed = if (f(11)) r.u16() else null
            val remaining = if (f(12)) r.u16() else null
            IndoorBikeData(speed, avgSpeed, cadence, avgCadence, distance, resistance, power, avgPower, energy, hr, elapsed, remaining)
        } catch (e: IndexOutOfBoundsException) {
            null
        }
    }

    data class Features(val machine: Long, val targets: Long) {
        val supportsPowerTarget get() = targets and (1L shl 3) != 0L
        val supportsResistanceTarget get() = targets and (1L shl 2) != 0L
        val supportsSimulation get() = targets and (1L shl 13) != 0L
        val reportsCadence get() = machine and (1L shl 1) != 0L
        val reportsPower get() = machine and (1L shl 14) != 0L
    }

    fun parseFeatures(bytes: ByteArray): Features? {
        if (bytes.size < 8) return null
        val r = ByteReader(bytes)
        return Features(r.u32(), r.u32())
    }

    data class PowerRange(val minW: Int, val maxW: Int, val incrementW: Int)

    fun parsePowerRange(bytes: ByteArray): PowerRange? {
        if (bytes.size < 6) return null
        val r = ByteReader(bytes)
        return PowerRange(r.s16(), r.s16(), r.u16())
    }

    data class ControlPointResponse(val requestOpCode: Int, val result: Int) {
        val success get() = result == FtmsControl.RESULT_SUCCESS
    }

    fun parseControlPointResponse(bytes: ByteArray): ControlPointResponse? {
        if (bytes.size < 3 || (bytes[0].toInt() and 0xFF) != FtmsControl.OP_RESPONSE) return null
        return ControlPointResponse(bytes[1].toInt() and 0xFF, bytes[2].toInt() and 0xFF)
    }
}

/** Encoders for the FTMS Control Point (0x2AD9). */
object FtmsControl {
    const val OP_REQUEST_CONTROL = 0x00
    const val OP_RESET = 0x01
    const val OP_SET_TARGET_RESISTANCE = 0x04
    const val OP_SET_TARGET_POWER = 0x05
    const val OP_START_RESUME = 0x07
    const val OP_STOP_PAUSE = 0x08
    const val OP_SET_SIMULATION = 0x11
    const val OP_RESPONSE = 0x80

    const val RESULT_SUCCESS = 0x01
    const val RESULT_NOT_SUPPORTED = 0x02
    const val RESULT_INVALID_PARAMETER = 0x03
    const val RESULT_FAILED = 0x04
    const val RESULT_CONTROL_NOT_PERMITTED = 0x05

    fun requestControl() = byteArrayOf(OP_REQUEST_CONTROL.toByte())
    fun reset() = byteArrayOf(OP_RESET.toByte())
    fun start() = byteArrayOf(OP_START_RESUME.toByte())
    fun stop() = byteArrayOf(OP_STOP_PAUSE.toByte(), 0x01)
    fun pause() = byteArrayOf(OP_STOP_PAUSE.toByte(), 0x02)

    fun setTargetPower(watts: Int): ByteArray =
        ByteWriter().u8(OP_SET_TARGET_POWER).s16(watts.coerceIn(0, 2000)).toByteArray()

    /** Resistance level in percent 0..100 (FTMS unit 0.1). */
    fun setTargetResistance(percent: Double): ByteArray =
        ByteWriter().u8(OP_SET_TARGET_RESISTANCE).u8((percent * 10).roundToInt().coerceIn(0, 255)).toByteArray()

    /**
     * Indoor bike simulation parameters.
     * @param gradePercent grade in percent (e.g. 5.0 = 5 %)
     * @param crr rolling resistance coefficient (e.g. 0.004)
     * @param cw wind resistance coefficient in kg/m (0.5 * rho * CdA, e.g. 0.51)
     * @param windSpeedMs wind speed in m/s
     */
    fun setSimulation(gradePercent: Double, crr: Double, cw: Double, windSpeedMs: Double = 0.0): ByteArray =
        ByteWriter()
            .u8(OP_SET_SIMULATION)
            .s16((windSpeedMs * 1000).roundToInt().coerceIn(-32768, 32767))
            .s16((gradePercent * 100).roundToInt().coerceIn(-4000, 4000))
            .u8((crr * 10000).roundToInt().coerceIn(0, 255))
            .u8((cw * 100).roundToInt().coerceIn(0, 255))
            .toByteArray()
}
