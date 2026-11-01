package se.spinhaus.core.workout

import kotlin.math.roundToInt

enum class StepKind { STEADY, RAMP, FREE, MAX }

/** A flattened step of a workout with absolute start time. */
data class Step(
    val index: Int,
    val startS: Int,
    val durationS: Int,
    val kind: StepKind,
    /** Target at start (fraction of FTP or watts if absolute). Null for FREE. */
    val from: Double?,
    /** Target at end. Equal to [from] unless RAMP. */
    val to: Double?,
    val cadence: Int?,
    val segmentIndex: Int,
    /** 1-based repeat number for intervals, null otherwise. */
    val repeat: Int?,
    val repeatCount: Int?,
    val isWork: Boolean,
    val texts: List<TextEvent>,
) {
    val endS get() = startS + durationS
    val ergControlled get() = kind == StepKind.STEADY || kind == StepKind.RAMP

    /** Target fraction at [t] seconds into the step. */
    fun targetAt(t: Double): Double? {
        val f = from ?: return null
        val e = to ?: return f
        if (durationS <= 0) return f
        val p = (t / durationS).coerceIn(0.0, 1.0)
        return f + (e - f) * p
    }
}

class ExpandedWorkout(val workout: Workout, val steps: List<Step>) {
    val totalDurationS: Int = steps.lastOrNull()?.endS ?: 0

    fun stepAt(t: Double): Step? {
        if (steps.isEmpty()) return null
        if (t >= totalDurationS) return null
        // binary search
        var lo = 0
        var hi = steps.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (steps[mid].startS <= t) lo = mid else hi = mid - 1
        }
        return steps[lo]
    }

    /** Target in watts at time t for the given FTP. */
    fun targetWattsAt(t: Double, ftp: Int): Int? {
        val s = stepAt(t) ?: return null
        val v = s.targetAt(t - s.startS) ?: return null
        return toWatts(v, ftp)
    }

    fun toWatts(value: Double, ftp: Int): Int =
        if (workout.absolutePower) value.roundToInt() else (value * ftp).roundToInt()

    companion object {
        fun of(workout: Workout): ExpandedWorkout {
            val steps = ArrayList<Step>()
            var t = 0
            fun add(
                d: Int, k: StepKind, from: Double?, to: Double?, cad: Int?, seg: Int,
                rep: Int?, repCount: Int?, work: Boolean, texts: List<TextEvent>,
            ) {
                if (d <= 0) return
                steps.add(Step(steps.size, t, d, k, from, to, cad, seg, rep, repCount, work, texts))
                t += d
            }
            workout.segments.forEachIndexed { i, s ->
                when (s) {
                    is Segment.Steady -> add(s.durationS, StepKind.STEADY, s.power, s.power, s.cadence, i, null, null, s.power >= 0.88, s.texts)
                    is Segment.Ramp -> add(s.durationS, StepKind.RAMP, s.from, s.to, s.cadence, i, null, null, false, s.texts)
                    is Segment.Free -> add(s.durationS, StepKind.FREE, null, null, s.cadence, i, null, null, false, s.texts)
                    is Segment.MaxEffort -> add(s.durationS, StepKind.MAX, s.guide, s.guide, null, i, null, null, true, s.texts)
                    is Segment.Intervals -> {
                        for (r in 1..s.repeat) {
                            add(s.onS, StepKind.STEADY, s.onPower, s.onPower, s.onCadence, i, r, s.repeat, true,
                                if (r == 1) s.texts else emptyList())
                            add(s.offS, StepKind.STEADY, s.offPower, s.offPower, s.offCadence, i, r, s.repeat, false, emptyList())
                        }
                    }
                }
            }
            return ExpandedWorkout(workout, steps)
        }
    }
}

/** Training stress estimate of a workout plan (not of a ride). */
object WorkoutStats {
    data class Estimate(val durationS: Int, val intensityFactor: Double, val tss: Double, val averageFraction: Double)

    fun estimate(workout: Workout): Estimate {
        val ew = ExpandedWorkout.of(workout)
        val n = ew.totalDurationS
        if (n == 0 || workout.absolutePower) return Estimate(n, 0.0, 0.0, 0.0)
        val values = DoubleArray(n) { t ->
            val s = ew.stepAt(t.toDouble())!!
            s.targetAt(t - s.startS + 0.5) ?: when (s.kind) {
                StepKind.FREE -> 0.6
                else -> 1.0
            }
        }
        val avg = values.average()
        // normalized "power" in FTP fractions with 30 s rolling average
        val win = 30
        var sum = 0.0
        var acc4 = 0.0
        var count = 0
        for (i in values.indices) {
            sum += values[i]
            if (i >= win) sum -= values[i - win]
            if (i >= win - 1) {
                val a = sum / win
                acc4 += a * a * a * a
                count++
            }
        }
        val np = if (count > 0) Math.pow(acc4 / count, 0.25) else avg
        val tss = n * np * np / 3600.0 * 100.0
        return Estimate(n, np, tss, avg)
    }
}
