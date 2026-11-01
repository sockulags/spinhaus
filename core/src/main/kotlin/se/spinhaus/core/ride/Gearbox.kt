package se.spinhaus.core.ride

import kotlinx.serialization.Serializable
import kotlin.math.pow

/**
 * Virtual gearbox. The Zwift Ride has no physical cassette, so shifting is emulated by
 * scaling the simulation parameters we send to the trainer.
 *
 * Trainer physics: F(v) = m·g·(crr + grade) + cw·v², with v derived from the flywheel
 * (fixed real ratio). For virtual ratio rv vs reference ratio rr: k = rv / rr,
 * virtual speed = k·v and the force felt at the pedals must be k·F(k·v). We therefore send
 * grade·k, crr·k and cw·k³.
 */
@Serializable
data class GearTable(
    val chainrings: List<Int> = listOf(34, 50),
    val ratios: List<Double> = DEFAULT_RATIOS,
    /** 0-based index of the gear that feels like the trainer's native ratio. */
    val neutralIndex: Int = 11,
    /** Extra overall resistance scaling (1.0 = neutral). */
    val feel: Double = 1.0,
) {
    companion object {
        /** 24 gears from ~34×34 to ~53×11. */
        val DEFAULT_RATIOS: List<Double> = listOf(
            1.00, 1.08, 1.17, 1.26, 1.36, 1.46, 1.57, 1.69, 1.82, 1.96, 2.11, 2.27,
            2.44, 2.62, 2.81, 3.01, 3.22, 3.44, 3.67, 3.91, 4.16, 4.42, 4.68, 4.95,
        )
    }
}

class VirtualGearbox(var table: GearTable = GearTable()) {
    var index: Int = table.neutralIndex.coerceIn(0, table.ratios.lastIndex)
        private set

    val gearNumber get() = index + 1
    val count get() = table.ratios.size
    val ratio get() = table.ratios[index]
    val scale get() = ratio / table.ratios[table.neutralIndex.coerceIn(0, table.ratios.lastIndex)] * table.feel

    fun shiftUp(): Boolean {
        if (index >= table.ratios.lastIndex) return false
        index++; return true
    }

    fun shiftDown(): Boolean {
        if (index <= 0) return false
        index--; return true
    }

    fun setGear(i: Int) {
        index = i.coerceIn(0, table.ratios.lastIndex)
    }

    /** Simulation parameters to send to the trainer for the current gear. */
    fun simulationFor(gradePercent: Double, physics: PhysicsParams): SimParams {
        val k = scale
        // Downhill grades are not scaled up as strongly, keeps descents rideable.
        val g = if (gradePercent >= 0) gradePercent * k else gradePercent / k
        // Light gears on the flat need *some* resistance – crr carries that.
        return SimParams(
            gradePercent = g.coerceIn(-15.0, 20.0),
            crr = (physics.crr * k).coerceIn(0.001, 0.0255),
            cw = (physics.cw * k.pow(3)).coerceIn(0.05, 2.55),
        )
    }

    /** Virtual wheel speed (km/h) from cadence for display in free ride when power is 0. */
    fun speedFromCadence(cadenceRpm: Double, wheelCircM: Double = 2.105): Double = cadenceRpm * ratio * wheelCircM * 60 / 1000
}

data class SimParams(val gradePercent: Double, val crr: Double, val cw: Double, val windMs: Double = 0.0)
