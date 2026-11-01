package se.spinhaus.core.ride

import kotlin.math.roundToInt

/**
 * Wraps the workout target with Zwift-like ERG behaviour:
 * - low-cadence protection (prevents the "death spiral"),
 * - smooth recovery back to target.
 */
class ErgController(
    private val lowCadence: Double = 40.0,
    private val lowCadenceSeconds: Double = 3.0,
    private val recoverCadence: Double = 60.0,
    private val recoverSeconds: Double = 5.0,
    private val protectFactor: Double = 0.5,
) {
    enum class State { NORMAL, PROTECTED, RECOVERING }

    var state = State.NORMAL
        private set
    private var lowFor = 0.0
    private var recoverProgress = 0.0
    private var lastOutput: Double = 0.0

    fun reset() {
        state = State.NORMAL; lowFor = 0.0; recoverProgress = 0.0
    }

    fun update(targetW: Int, cadence: Double, dt: Double, pedalling: Boolean): Int {
        when (state) {
            State.NORMAL -> {
                if (pedalling && cadence < lowCadence) lowFor += dt else lowFor = 0.0
                if (lowFor >= lowCadenceSeconds) {
                    state = State.PROTECTED
                }
            }
            State.PROTECTED -> if (cadence >= recoverCadence) {
                state = State.RECOVERING
                recoverProgress = 0.0
            }
            State.RECOVERING -> {
                if (cadence < lowCadence) {
                    state = State.PROTECTED
                } else {
                    recoverProgress += dt / recoverSeconds
                    if (recoverProgress >= 1.0) {
                        state = State.NORMAL
                        lowFor = 0.0
                    }
                }
            }
        }
        val out = when (state) {
            State.NORMAL -> targetW.toDouble()
            State.PROTECTED -> targetW * protectFactor
            State.RECOVERING -> targetW * (protectFactor + (1 - protectFactor) * recoverProgress.coerceIn(0.0, 1.0))
        }
        lastOutput = out
        return out.roundToInt()
    }

    val isProtecting get() = state != State.NORMAL
}
