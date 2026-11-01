package se.spinhaus.core.training

import kotlinx.serialization.Serializable

/** Power zones (Coggan 7-zone model), bounds as fraction of FTP. */
object PowerZones {
    /** Upper bounds (exclusive) for zones 1..6; zone 7 is everything above. */
    private val upper = doubleArrayOf(0.55, 0.75, 0.90, 1.05, 1.20, 1.50)

    const val COUNT = 7

    /** 1-based zone for a power value. */
    fun zoneOf(watts: Double, ftp: Int): Int {
        if (ftp <= 0) return 1
        val f = watts / ftp
        for (i in upper.indices) if (f < upper[i]) return i + 1
        return 7
    }

    fun zoneOfFraction(f: Double): Int {
        for (i in upper.indices) if (f < upper[i]) return i + 1
        return 7
    }

    /** Lower bound (fraction of FTP) of a zone. */
    fun lowerBound(zone: Int): Double = if (zone <= 1) 0.0 else upper[zone - 2]
    fun upperBound(zone: Int): Double? = if (zone >= 7) null else upper[zone - 1]
}

@Serializable
enum class HrZoneModel { PERCENT_MAX, KARVONEN }

/** Five heart-rate zones. */
object HeartRateZones {
    private val bounds = doubleArrayOf(0.60, 0.70, 0.80, 0.90)
    const val COUNT = 5

    fun zoneOf(bpm: Int, maxHr: Int, restHr: Int, model: HrZoneModel): Int {
        if (maxHr <= 0) return 1
        val f = when (model) {
            HrZoneModel.PERCENT_MAX -> bpm.toDouble() / maxHr
            HrZoneModel.KARVONEN -> if (maxHr > restHr) (bpm - restHr).toDouble() / (maxHr - restHr) else bpm.toDouble() / maxHr
        }
        for (i in bounds.indices) if (f < bounds[i]) return i + 1
        return 5
    }

    /** Lower bpm bound of each zone, for display. */
    fun lowerBpm(zone: Int, maxHr: Int, restHr: Int, model: HrZoneModel): Int {
        val f = if (zone <= 1) 0.5 else bounds[zone - 2]
        return when (model) {
            HrZoneModel.PERCENT_MAX -> (maxHr * f).toInt()
            HrZoneModel.KARVONEN -> (restHr + (maxHr - restHr) * f).toInt()
        }
    }
}
