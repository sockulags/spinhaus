package se.spinhaus.core.training

import kotlinx.serialization.Serializable
import kotlin.math.pow

object PowerAnalysis {
    /** Normalized Power from 1 Hz samples. Null when shorter than 30 s. */
    fun normalizedPower(power: IntArray): Double? {
        val win = 30
        if (power.size < win) return null
        var sum = 0.0
        var acc = 0.0
        var n = 0
        for (i in power.indices) {
            sum += power[i]
            if (i >= win) sum -= power[i - win]
            if (i >= win - 1) {
                val a = sum / win
                acc += a.pow(4)
                n++
            }
        }
        return (acc / n).pow(0.25)
    }

    /** Best average over a window of [seconds]. Null if not enough data. */
    fun bestAverage(power: IntArray, seconds: Int): Double? {
        if (seconds <= 0 || power.size < seconds) return null
        var sum = 0L
        var best = Long.MIN_VALUE
        for (i in power.indices) {
            sum += power[i]
            if (i >= seconds) sum -= power[i - seconds]
            if (i >= seconds - 1 && sum > best) best = sum
        }
        return best.toDouble() / seconds
    }

    val BEST_DURATIONS = intArrayOf(5, 15, 30, 60, 120, 300, 600, 1200, 1800, 3600)

    fun bests(power: IntArray, durations: IntArray = BEST_DURATIONS): Map<Int, Int> {
        val out = LinkedHashMap<Int, Int>()
        for (d in durations) bestAverage(power, d)?.let { out[d] = it.toInt() }
        return out
    }

    fun intensityFactor(np: Double, ftp: Int) = if (ftp > 0) np / ftp else 0.0

    fun tss(durationS: Int, np: Double, ftp: Int): Double {
        if (ftp <= 0) return 0.0
        val iF = np / ftp
        return durationS * np * iF / (ftp * 3600.0) * 100.0
    }
}

@Serializable
data class ActivitySummary(
    val durationS: Int,
    val movingS: Int,
    val distanceM: Double,
    val elevationGainM: Double,
    val avgPower: Int,
    val maxPower: Int,
    val normalizedPower: Int?,
    val intensityFactor: Double?,
    val tss: Double?,
    val avgHr: Int?,
    val maxHr: Int?,
    val avgCadence: Int?,
    val kj: Int,
    val kcal: Int,
    val powerZoneSeconds: List<Int>,
    val hrZoneSeconds: List<Int>,
    val bests: Map<Int, Int>,
    val ftp: Int,
)

/** A recorded 1 Hz sample. Nullable fields were not available. */
@Serializable
data class Sample(
    val t: Int,
    val power: Int,
    val target: Int? = null,
    val hr: Int? = null,
    val cadence: Int? = null,
    val speedKmh: Double? = null,
    val distanceM: Double = 0.0,
    val altitudeM: Double? = null,
    val grade: Double? = null,
    val gear: Int? = null,
    /** True while auto-paused (not moving). */
    val paused: Boolean = false,
    /** Workout clock (s) when recorded, for structured workouts. */
    val workoutT: Int? = null,
)

object ActivityCalculator {
    fun summarize(
        samples: List<Sample>,
        ftp: Int,
        maxHr: Int,
        restHr: Int,
        hrModel: HrZoneModel,
    ): ActivitySummary {
        val moving = samples.filter { !it.paused }
        val power = IntArray(moving.size) { moving[it].power }
        val np = PowerAnalysis.normalizedPower(power)
        val hrs = moving.mapNotNull { it.hr?.takeIf { h -> h > 0 } }
        val cads = moving.mapNotNull { it.cadence?.takeIf { c -> c > 0 } }
        val pz = IntArray(PowerZones.COUNT)
        val hz = IntArray(HeartRateZones.COUNT)
        for (s in moving) {
            pz[PowerZones.zoneOf(s.power.toDouble(), ftp) - 1]++
            val h = s.hr
            if (h != null && h > 0) hz[HeartRateZones.zoneOf(h, maxHr, restHr, hrModel) - 1]++
        }
        var gain = 0.0
        var prevAlt: Double? = null
        for (s in samples) {
            val a = s.altitudeM ?: continue
            val p = prevAlt
            if (p != null && a > p) gain += a - p
            prevAlt = a
        }
        val kj = (power.sumOf { it.toLong() } / 1000.0)
        return ActivitySummary(
            durationS = samples.size,
            movingS = moving.size,
            distanceM = samples.lastOrNull()?.distanceM ?: 0.0,
            elevationGainM = gain,
            avgPower = if (power.isNotEmpty()) power.average().toInt() else 0,
            maxPower = power.maxOrNull() ?: 0,
            normalizedPower = np?.toInt(),
            intensityFactor = np?.let { PowerAnalysis.intensityFactor(it, ftp) },
            tss = np?.let { PowerAnalysis.tss(moving.size, it, ftp) },
            avgHr = if (hrs.isNotEmpty()) hrs.average().toInt() else null,
            maxHr = hrs.maxOrNull(),
            avgCadence = if (cads.isNotEmpty()) cads.average().toInt() else null,
            kj = kj.toInt(),
            // ~24 % gross efficiency: kcal ≈ kJ
            kcal = kj.toInt(),
            powerZoneSeconds = pz.toList(),
            hrZoneSeconds = hz.toList(),
            bests = PowerAnalysis.bests(power),
            ftp = ftp,
        )
    }
}

/** Fitness (CTL), fatigue (ATL) and form (TSB) from daily TSS. */
object FitnessModel {
    data class Day(val epochDay: Long, val ctl: Double, val atl: Double, val tsb: Double, val tss: Double)

    fun compute(dailyTss: Map<Long, Double>, fromDay: Long, toDay: Long): List<Day> {
        val out = ArrayList<Day>()
        var ctl = 0.0
        var atl = 0.0
        val first = minOf(fromDay, dailyTss.keys.minOrNull() ?: fromDay)
        for (d in first..toDay) {
            val tss = dailyTss[d] ?: 0.0
            val tsb = ctl - atl
            ctl += (tss - ctl) / 42.0
            atl += (tss - atl) / 7.0
            if (d >= fromDay) out.add(Day(d, ctl, atl, tsb, tss))
        }
        return out
    }
}

object FtpEstimator {
    /**
     * Suggests a new FTP from recent best 20-minute power (95 %).
     * Returns null unless it is at least 2 % above the current FTP.
     */
    fun suggest(recentBest20MinW: Int?, currentFtp: Int): Int? {
        val b = recentBest20MinW ?: return null
        val est = (b * 0.95).toInt()
        return if (est >= currentFtp * 1.02) est else null
    }
}
