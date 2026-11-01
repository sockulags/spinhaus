package se.spinhaus.core.workout

import se.spinhaus.core.workout.WorkoutCategory.*

/** Small DSL for building workouts. Durations in minutes unless suffixed with S. */
class WorkoutBuilder {
    val segments = ArrayList<Segment>()
    private fun s(min: Double) = (min * 60).toInt()

    fun warmup(min: Double = 10.0, from: Double = 0.45, to: Double = 0.70) { segments += Segment.Ramp(s(min), from, to) }
    fun cooldown(min: Double = 8.0, from: Double = 0.65, to: Double = 0.40) { segments += Segment.Ramp(s(min), from, to) }
    fun steady(min: Double, p: Double, cadence: Int? = null, text: String? = null) {
        segments += Segment.Steady(s(min), p, cadence, text?.let { listOf(TextEvent(0, it)) } ?: emptyList())
    }
    fun steadyS(sec: Int, p: Double, cadence: Int? = null) { segments += Segment.Steady(sec, p, cadence) }
    fun ramp(min: Double, from: Double, to: Double) { segments += Segment.Ramp(s(min), from, to) }
    fun intervals(repeat: Int, onS: Int, onP: Double, offS: Int, offP: Double, onCad: Int? = null, offCad: Int? = null, text: String? = null) {
        segments += Segment.Intervals(repeat, onS, onP, offS, offP, onCad, offCad, text?.let { listOf(TextEvent(0, it)) } ?: emptyList())
    }
    fun free(min: Double) { segments += Segment.Free(s(min)) }
    fun max(sec: Int, guide: Double? = null) { segments += Segment.MaxEffort(sec, guide) }
}

private fun workout(
    id: String, name: String, sv: String, cat: WorkoutCategory,
    desc: String, descSv: String, build: WorkoutBuilder.() -> Unit,
): Workout {
    val b = WorkoutBuilder().apply(build)
    return Workout(
        id = "builtin-$id", name = name, description = desc, category = cat, segments = b.segments,
        source = WorkoutSource.BUILT_IN, author = "Spinhaus",
        localizedNames = mapOf("sv" to sv), localizedDescriptions = mapOf("sv" to descSv),
    )
}

object WorkoutLibrary {
    val all: List<Workout> by lazy { regular + FtpTests.all }

    fun byId(id: String): Workout? = all.firstOrNull { it.id == id }

    val regular: List<Workout> = listOf(
        // --- Recovery
        workout("easy-spin", "Easy Spin", "Lätt rull", RECOVERY,
            "30 minutes of very easy pedalling to recover.", "30 minuter mycket lätt trampande för återhämtning.") {
            ramp(5.0, 0.40, 0.50); steady(20.0, 0.52); ramp(5.0, 0.50, 0.40)
        },
        workout("recovery-flush", "Recovery Flush", "Återhämtningsrull", RECOVERY,
            "Easy riding with short, light cadence pick-ups to flush the legs.", "Lugn cykling med korta, lätta kadenshöjningar för att mjuka upp benen.") {
            ramp(5.0, 0.40, 0.50)
            repeat(5) { steady(5.0, 0.50, 85); steadyS(60, 0.55, 105) }
            ramp(5.0, 0.50, 0.40)
        },
        // --- Endurance
        workout("endurance-45", "Endurance 45", "Uthållighet 45", ENDURANCE,
            "Steady zone 2 riding.", "Jämn cykling i zon 2.") {
            warmup(8.0, 0.45, 0.65); steady(32.0, 0.68); cooldown(5.0, 0.60, 0.45)
        },
        workout("endurance-60", "Endurance 60", "Uthållighet 60", ENDURANCE,
            "An hour of aerobic base building.", "En timme grundträning.") {
            warmup(10.0, 0.45, 0.65); steady(20.0, 0.68); steady(15.0, 0.72); steady(10.0, 0.68); cooldown(5.0, 0.60, 0.45)
        },
        workout("endurance-90", "Endurance 90", "Uthållighet 90", ENDURANCE,
            "Long aerobic ride with gentle variation.", "Långt distanspass med lätt variation.") {
            warmup(10.0, 0.45, 0.65)
            repeat(3) { steady(20.0, 0.68); steady(5.0, 0.75) }
            cooldown(5.0, 0.60, 0.45)
        },
        workout("endurance-builder", "Endurance Builder", "Uthållighetsbygge", ENDURANCE,
            "Zone 2 with three upper-endurance blocks.", "Zon 2 med tre block i övre distansfart.") {
            warmup(10.0, 0.45, 0.65)
            repeat(3) { steady(10.0, 0.66); steady(10.0, 0.76) }
            steady(5.0, 0.65); cooldown(5.0, 0.60, 0.45)
        },
        // --- Tempo
        workout("tempo-3x10", "Tempo 3×10", "Tempo 3×10", TEMPO,
            "Three 10-minute tempo blocks.", "Tre tempoblock på 10 minuter.") {
            warmup(); intervals(3, 600, 0.82, 300, 0.60); cooldown()
        },
        workout("tempo-2x20", "Tempo 2×20", "Tempo 2×20", TEMPO,
            "Two long tempo efforts.", "Två långa tempointervaller.") {
            warmup(); intervals(2, 1200, 0.80, 300, 0.60); cooldown()
        },
        workout("tempo-blocks", "Tempo Blocks", "Tempoblock", TEMPO,
            "Rising tempo blocks: 76, 80, 84 % FTP.", "Stigande tempoblock: 76, 80, 84 % av FTP.") {
            warmup(); steady(10.0, 0.76); steady(3.0, 0.60); steady(10.0, 0.80); steady(3.0, 0.60); steady(10.0, 0.84); cooldown()
        },
        // --- Sweet spot
        workout("ss-3x10", "Sweet Spot 3×10", "Sweet spot 3×10", SWEET_SPOT,
            "Classic sweet spot intro.", "Klassisk introduktion till sweet spot.") {
            warmup(); intervals(3, 600, 0.90, 300, 0.55); cooldown()
        },
        workout("ss-2x15", "Sweet Spot 2×15", "Sweet spot 2×15", SWEET_SPOT,
            "Two 15-minute sweet spot intervals.", "Två intervaller på 15 minuter i sweet spot.") {
            warmup(); intervals(2, 900, 0.90, 360, 0.55); cooldown()
        },
        workout("ss-3x15", "Sweet Spot 3×15", "Sweet spot 3×15", SWEET_SPOT,
            "Three 15-minute sweet spot intervals.", "Tre intervaller på 15 minuter i sweet spot.") {
            warmup(); intervals(3, 900, 0.90, 300, 0.55); cooldown()
        },
        workout("ss-2x20", "Sweet Spot 2×20", "Sweet spot 2×20", SWEET_SPOT,
            "Two 20-minute sweet spot intervals.", "Två intervaller på 20 minuter i sweet spot.") {
            warmup(); intervals(2, 1200, 0.90, 300, 0.55); cooldown()
        },
        workout("ss-over-under", "Sweet Spot Over-Unders", "Sweet spot över/under", SWEET_SPOT,
            "Sweet spot with surges just over threshold.", "Sweet spot med korta lyft strax över tröskeln.") {
            warmup()
            repeat(3) {
                repeat(3) { steady(3.0, 0.88); steady(1.0, 1.02) }
                steady(4.0, 0.55)
            }
            cooldown()
        },
        // --- Threshold
        workout("thr-4x8", "Threshold 4×8", "Tröskel 4×8", THRESHOLD,
            "Four 8-minute efforts at FTP.", "Fyra 8-minutersintervaller på FTP.") {
            warmup(); intervals(4, 480, 1.00, 240, 0.55); cooldown()
        },
        workout("thr-3x12", "Threshold 3×12", "Tröskel 3×12", THRESHOLD,
            "Three 12-minute efforts just below FTP.", "Tre 12-minutersintervaller strax under FTP.") {
            warmup(); intervals(3, 720, 0.97, 300, 0.55); cooldown()
        },
        workout("thr-2x20", "Threshold 2×20", "Tröskel 2×20", THRESHOLD,
            "The classic 2×20 at threshold.", "Klassiska 2×20 på tröskel.") {
            warmup(12.0); intervals(2, 1200, 0.96, 480, 0.55); cooldown()
        },
        workout("thr-over-under", "Over-Unders 3×9", "Över/under 3×9", THRESHOLD,
            "Alternate 2 min under and 1 min over FTP.", "Växla 2 minuter under och 1 minut över FTP.") {
            warmup()
            repeat(3) {
                repeat(3) { steady(2.0, 0.95); steady(1.0, 1.06) }
                steady(5.0, 0.55)
            }
            cooldown()
        },
        workout("thr-ladder", "Threshold Ladder", "Tröskelstege", THRESHOLD,
            "4-6-8-6-4 minutes at threshold.", "4-6-8-6-4 minuter på tröskel.") {
            warmup()
            for (m in listOf(4.0, 6.0, 8.0, 6.0, 4.0)) { steady(m, 1.0); steady(3.0, 0.55) }
            cooldown()
        },
        // --- VO2max
        workout("vo2-5x3", "VO2max 5×3", "VO2max 5×3", VO2MAX,
            "Five 3-minute efforts at 115 % FTP.", "Fem 3-minutersintervaller på 115 % av FTP.") {
            warmup(12.0); intervals(5, 180, 1.15, 180, 0.50); cooldown()
        },
        workout("vo2-4x4", "VO2max 4×4", "VO2max 4×4", VO2MAX,
            "Four 4-minute efforts at 112 % FTP.", "Fyra 4-minutersintervaller på 112 % av FTP.") {
            warmup(12.0); intervals(4, 240, 1.12, 240, 0.50); cooldown()
        },
        workout("vo2-30-30", "30/30s", "30/30", VO2MAX,
            "Three sets of 10×30 s hard / 30 s easy.", "Tre set med 10×30 s hårt / 30 s lätt.") {
            warmup(12.0)
            repeat(3) { intervals(10, 30, 1.25, 30, 0.50); steady(5.0, 0.50) }
            cooldown()
        },
        workout("vo2-40-20", "40/20s", "40/20", VO2MAX,
            "Two sets of 8×40 s hard / 20 s easy.", "Två set med 8×40 s hårt / 20 s lätt.") {
            warmup(12.0)
            repeat(2) { intervals(8, 40, 1.20, 20, 0.50); steady(6.0, 0.50) }
            cooldown()
        },
        workout("vo2-6x2", "VO2max 6×2", "VO2max 6×2", VO2MAX,
            "Six 2-minute efforts at 120 % FTP.", "Sex 2-minutersintervaller på 120 % av FTP.") {
            warmup(12.0); intervals(6, 120, 1.20, 120, 0.50); cooldown()
        },
        workout("vo2-pyramid", "VO2max Pyramid", "VO2max-pyramid", VO2MAX,
            "1-2-3-2-1 minutes at 118 % FTP.", "1-2-3-2-1 minuter på 118 % av FTP.") {
            warmup(12.0)
            for (m in listOf(1.0, 2.0, 3.0, 2.0, 1.0)) { steady(m, 1.18); steady(m.coerceAtLeast(2.0), 0.50) }
            cooldown()
        },
        // --- Anaerobic / sprint
        workout("sprint-sets", "Sprint Sets", "Spurtset", ANAEROBIC,
            "Eight all-out 15 s sprints (ERG off) with full recovery.", "Åtta maximala 15-sekundersspurter (ERG av) med full vila.") {
            warmup(12.0)
            repeat(8) { max(15); steady(2.75, 0.50) }
            cooldown()
        },
        workout("anaerobic-6x1", "Anaerobic 6×1", "Anaerob 6×1", ANAEROBIC,
            "Six 1-minute efforts at 135 % FTP.", "Sex 1-minutersintervaller på 135 % av FTP.") {
            warmup(12.0); intervals(6, 60, 1.35, 180, 0.50); cooldown()
        },
        workout("tabata", "Tabata", "Tabata", ANAEROBIC,
            "Two Tabata sets: 8×20 s very hard / 10 s easy.", "Två tabataset: 8×20 s mycket hårt / 10 s lätt.") {
            warmup(12.0)
            repeat(2) { intervals(8, 20, 1.60, 10, 0.40); steady(6.0, 0.50) }
            cooldown()
        },
        workout("neuromuscular", "Neuromuscular", "Snabbhet", ANAEROBIC,
            "Ten 10-second max sprints.", "Tio maximala 10-sekundersspurter.") {
            warmup(12.0)
            repeat(10) { max(10); steady(1.83, 0.55) }
            cooldown()
        },
        // --- Cadence
        workout("cadence-pyramid", "Cadence Pyramid", "Kadenspyramid", CADENCE,
            "Easy power with stepping cadence targets.", "Lätt effekt med stegvisa kadensmål.") {
            warmup(8.0)
            repeat(2) { for (c in listOf(80, 90, 100, 110, 100, 90)) steady(2.0, 0.65, c) }
            cooldown(6.0)
        },
        workout("high-cadence", "High Cadence Drills", "Hög kadens", CADENCE,
            "1-minute spin-ups at 110+ rpm.", "1-minuters kadenslyft på 110+ varv/min.") {
            warmup(8.0); intervals(8, 60, 0.70, 120, 0.60, 115, 90); cooldown(6.0)
        },
        workout("low-cadence", "Low Cadence Strength", "Styrka låg kadens", CADENCE,
            "Five 5-minute efforts at 60 rpm.", "Fem 5-minutersintervaller på 60 varv/min.") {
            warmup(); intervals(5, 300, 0.85, 180, 0.55, 60, 90); cooldown()
        },
        // --- Short
        workout("quick-20", "Quick 20", "Snabba 20", SHORT,
            "Short and sharp: 4×2 min at 105 %.", "Kort och vasst: 4×2 min på 105 %.") {
            warmup(5.0); intervals(4, 120, 1.05, 60, 0.55); cooldown(3.0)
        },
        workout("lunch-30", "Lunch Break 30", "Lunchpass 30", SHORT,
            "30 minutes with 2×8 min sweet spot.", "30 minuter med 2×8 min sweet spot.") {
            warmup(6.0); intervals(2, 480, 0.90, 180, 0.55); cooldown(2.0)
        },
        workout("express-vo2", "Express VO2", "Express-VO2", SHORT,
            "25 minutes with 3×2 min VO2max.", "25 minuter med 3×2 min VO2max.") {
            warmup(7.0); intervals(3, 120, 1.18, 120, 0.50); cooldown(6.0)
        },
        workout("coffee-30", "Coffee Ride 30", "Fikarunda 30", SHORT,
            "Relaxed 30-minute endurance spin.", "Avslappnad distansrunda på 30 minuter.") {
            warmup(5.0, 0.45, 0.62); steady(22.0, 0.66); cooldown(3.0, 0.55, 0.45)
        },
        workout("spin-ups-20", "Spin-ups 20", "Kadenslyft 20", SHORT,
            "Quick leg opener with cadence spin-ups.", "Snabb benöppnare med kadenslyft.") {
            warmup(5.0); intervals(6, 30, 0.75, 90, 0.55, 120, 90); steady(3.0, 0.90); cooldown(3.0)
        },
    )
}
