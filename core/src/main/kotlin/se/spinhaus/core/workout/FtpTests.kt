package se.spinhaus.core.workout

import se.spinhaus.core.training.PowerAnalysis
import se.spinhaus.core.training.Sample

object FtpTests {
    const val RAMP_ID = "builtin-ftp-ramp"
    const val RAMP_SHORT_ID = "builtin-ftp-ramp-short"
    const val TWENTY_ID = "builtin-ftp-20"
    const val TWO_BY_EIGHT_ID = "builtin-ftp-2x8"

    /** Ramp: absolute watts, 1-minute steps until failure. */
    private fun ramp(id: String, kind: FtpTestKind, startW: Int, stepW: Int, name: String, sv: String, desc: String, descSv: String): Workout {
        val segs = ArrayList<Segment>()
        segs += Segment.Ramp(300, 60.0, startW * 0.9)
        segs += Segment.Steady(120, startW * 0.6)
        var w = startW
        var first = true
        while (w <= 700) {
            segs += Segment.Steady(60, w.toDouble(), texts = if (first) listOf(TextEvent(0, "Ramp starts – keep going until you can't!")) else emptyList())
            first = false
            w += stepW
        }
        segs += Segment.Ramp(300, startW * 0.8, startW * 0.5)
        return Workout(
            id = id, name = name, description = desc, category = WorkoutCategory.FTP_TEST, segments = segs,
            source = WorkoutSource.BUILT_IN, absolutePower = true, ftpTest = kind, author = "Spinhaus",
            localizedNames = mapOf("sv" to sv), localizedDescriptions = mapOf("sv" to descSv),
        )
    }

    val ramp = ramp(
        RAMP_ID, FtpTestKind.RAMP, 100, 20, "Ramp Test", "Ramptest",
        "Power rises 20 W every minute until you can't hold it. FTP = 75 % of your best minute.",
        "Effekten höjs 20 W varje minut tills du inte orkar. FTP = 75 % av din bästa minut.",
    )

    val rampShort = ramp(
        RAMP_SHORT_ID, FtpTestKind.RAMP_SHORT, 120, 25, "Short Ramp Test", "Kort ramptest",
        "Faster ramp: +25 W per minute. FTP = 75 % of your best minute.",
        "Snabbare ramp: +25 W per minut. FTP = 75 % av din bästa minut.",
    )

    val twentyMin = Workout(
        id = TWENTY_ID, name = "20-Minute FTP Test", description =
            "Warm-up with a 5-minute opener, then a 20-minute all-out effort (ERG off, use the gears). FTP = 95 % of the 20-minute average.",
        category = WorkoutCategory.FTP_TEST, source = WorkoutSource.BUILT_IN, ftpTest = FtpTestKind.TWENTY_MIN, author = "Spinhaus",
        localizedNames = mapOf("sv" to "FTP-test 20 min"),
        localizedDescriptions = mapOf("sv" to "Uppvärmning med 5 minuters öppnare, sedan 20 minuter maximalt (ERG av, använd växlarna). FTP = 95 % av snittet under 20 minuter."),
        segments = listOf(
            Segment.Ramp(600, 0.45, 0.75),
            Segment.Intervals(3, 60, 1.0, 60, 0.55),
            Segment.MaxEffort(300, 1.05, listOf(TextEvent(0, "5 min hard opener"))),
            Segment.Steady(600, 0.50),
            Segment.MaxEffort(1200, 1.0, listOf(TextEvent(0, "20 minutes all-out. Pace yourself!"))),
            Segment.Ramp(600, 0.60, 0.40),
        ),
    )

    val twoByEight = Workout(
        id = TWO_BY_EIGHT_ID, name = "2×8-Minute FTP Test", description =
            "Two 8-minute all-out efforts with 10 minutes recovery. FTP = 90 % of the average of both.",
        category = WorkoutCategory.FTP_TEST, source = WorkoutSource.BUILT_IN, ftpTest = FtpTestKind.TWO_BY_EIGHT, author = "Spinhaus",
        localizedNames = mapOf("sv" to "FTP-test 2×8 min"),
        localizedDescriptions = mapOf("sv" to "Två maximala 8-minutersintervaller med 10 minuters vila. FTP = 90 % av snittet av båda."),
        segments = listOf(
            Segment.Ramp(600, 0.45, 0.75),
            Segment.Intervals(3, 60, 1.0, 60, 0.55),
            Segment.Steady(180, 0.55),
            Segment.MaxEffort(480, 1.05, listOf(TextEvent(0, "8 minutes all-out #1"))),
            Segment.Steady(600, 0.50),
            Segment.MaxEffort(480, 1.05, listOf(TextEvent(0, "8 minutes all-out #2"))),
            Segment.Ramp(600, 0.60, 0.40),
        ),
    )

    val all = listOf(ramp, rampShort, twentyMin, twoByEight)

    /** Ramp tests end when cadence falls below this for [RAMP_FAIL_SECONDS]. */
    const val RAMP_FAIL_CADENCE = 50.0
    const val RAMP_FAIL_SECONDS = 5

    /**
     * Calculates FTP from a completed test.
     * @param samples recorded 1 Hz samples (with workout clock).
     */
    fun calculateFtp(workout: Workout, samples: List<Sample>): Int? {
        val power = IntArray(samples.size) { samples[it].power }
        val kind = workout.ftpTest ?: return null
        val ew = ExpandedWorkout.of(workout)
        return when (kind) {
            FtpTestKind.RAMP, FtpTestKind.RAMP_SHORT -> {
                val best = PowerAnalysis.bestAverage(power, 60) ?: return null
                (best * 0.75).toInt()
            }
            FtpTestKind.TWENTY_MIN -> {
                val step = ew.steps.lastOrNull { it.kind == StepKind.MAX && it.durationS >= 1200 } ?: return null
                val avg = averageOf(samples, step.startS, step.endS) ?: return null
                (avg * 0.95).toInt()
            }
            FtpTestKind.TWO_BY_EIGHT -> {
                val steps = ew.steps.filter { it.kind == StepKind.MAX && it.durationS == 480 }
                val avgs = steps.mapNotNull { averageOf(samples, it.startS, it.endS) }
                if (avgs.isEmpty()) return null
                (avgs.average() * 0.90).toInt()
            }
        }
    }

    private fun averageOf(samples: List<Sample>, from: Int, to: Int): Double? {
        val inStep = samples.filter { val t = it.workoutT; t != null && t >= from && t < to }
        if (inStep.size < 60) return null
        return inStep.sumOf { it.power }.toDouble() / inStep.size
    }
}
