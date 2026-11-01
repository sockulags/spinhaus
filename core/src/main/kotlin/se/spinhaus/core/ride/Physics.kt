package se.spinhaus.core.ride

import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Simple road-cycling physics used for virtual speed, distance and virtual shifting. */
data class PhysicsParams(
    val riderKg: Double = 75.0,
    val bikeKg: Double = 9.0,
    val crr: Double = 0.004,
    val cda: Double = 0.32,
    val airDensity: Double = 1.225,
    val drivetrainLoss: Double = 0.02,
) {
    val totalKg get() = riderKg + bikeKg
    /** FTMS "wind resistance coefficient" (kg/m) = 0.5 * rho * CdA. */
    val cw get() = 0.5 * airDensity * cda
}

object Physics {
    const val G = 9.81

    /** Resistive force at speed v (m/s) on a grade (percent). */
    fun resistiveForce(v: Double, gradePercent: Double, p: PhysicsParams): Double {
        val theta = atan(gradePercent / 100.0)
        val gravity = p.totalKg * G * sin(theta)
        val rolling = p.totalKg * G * cos(theta) * p.crr
        val aero = p.cw * v * v
        return gravity + rolling + aero
    }

    /** Power needed to hold speed v on the grade. */
    fun powerForSpeed(v: Double, gradePercent: Double, p: PhysicsParams): Double =
        max(0.0, resistiveForce(v, gradePercent, p) * v / (1 - p.drivetrainLoss))

    /** Steady-state speed (m/s) for a power on a grade, solved by bisection. */
    fun steadySpeed(powerW: Double, gradePercent: Double, p: PhysicsParams): Double {
        if (powerW <= 0) return 0.0
        var lo = 0.0
        var hi = 30.0
        repeat(60) {
            val mid = (lo + hi) / 2
            if (powerForSpeed(mid, gradePercent, p) > powerW) hi = mid else lo = mid
        }
        return (lo + hi) / 2
    }

    /**
     * Integrates speed over [dt] seconds with inertia (smooth, realistic acceleration).
     * Returns the new speed in m/s.
     */
    fun step(v: Double, powerW: Double, gradePercent: Double, dt: Double, p: PhysicsParams): Double {
        var speed = v
        val n = max(1, (dt / 0.1).toInt())
        val h = dt / n
        repeat(n) {
            val propulsive = if (speed < 0.5) powerW * (1 - p.drivetrainLoss) / 0.5 else powerW * (1 - p.drivetrainLoss) / speed
            val resist = resistiveForce(speed, gradePercent, p)
            // effective mass including wheel inertia (~+2 %)
            val a = (propulsive - resist) / (p.totalKg * 1.02)
            speed = max(0.0, speed + a * h)
            if (powerW <= 0 && speed < 0.3) speed = 0.0
        }
        return speed
    }
}
