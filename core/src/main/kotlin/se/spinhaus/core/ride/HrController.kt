package se.spinhaus.core.ride

import kotlin.math.abs
import kotlin.math.exp

/**
 * Heart-rate ERG: slowly adjusts the trainer's target power so the rider's heart rate settles on
 * a target. Heart rate reacts with a lag of ~30–60 s, so adjustments are small and infrequent.
 */
class HrController(
    private val minW: Int,
    private val maxW: Int,
    private val intervalS: Double = 15.0,
    private val gainWPerBpm: Double = 0.6,
    private val maxUpW: Double = 8.0,
    private val maxDownW: Double = 12.0,
    private val deadbandBpm: Double = 2.0,
    /** During this many seconds at the start of a step, power may only rise slowly. */
    private val settleS: Double = 120.0,
) {
    var powerW: Double = minW.toDouble()
        private set
    private var hrAvg: Double? = null
    private var sinceAdjust = 0.0
    private var elapsed = 0.0
    var hrMissing = false
        private set

    fun start(initialW: Int, keepCurrent: Boolean = false) {
        if (!keepCurrent) powerW = initialW.toDouble().coerceIn(minW.toDouble(), maxW.toDouble())
        sinceAdjust = 0.0
        elapsed = 0.0
    }

    /** @return target power in watts. */
    fun update(targetBpm: Int, hr: Int?, dt: Double): Int {
        elapsed += dt
        sinceAdjust += dt
        if (hr == null || hr <= 0) {
            hrMissing = true
            return powerW.toInt()
        }
        hrMissing = false
        val a = 1 - exp(-dt / 10.0)
        hrAvg = hrAvg?.let { it + (hr - it) * a } ?: hr.toDouble()
        if (sinceAdjust >= intervalS) {
            sinceAdjust = 0.0
            val error = targetBpm - hrAvg!!
            if (abs(error) > deadbandBpm) {
                var delta = (error * gainWPerBpm).coerceIn(-maxDownW, maxUpW)
                // While heart rate is still rising after a change, don't push up hard.
                if (elapsed < settleS && delta > 0) delta = delta.coerceAtMost(maxUpW / 2)
                powerW = (powerW + delta).coerceIn(minW.toDouble(), maxW.toDouble())
            }
        }
        return powerW.toInt()
    }
}
