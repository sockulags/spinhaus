package se.spinhaus.core.plan

import kotlinx.serialization.Serializable

/** A built-in multi-week plan. [weeks] holds workout ids (without the "builtin-" prefix) in order. */
data class TrainingPlan(
    val id: String,
    val name: String,
    val nameSv: String,
    val description: String,
    val descriptionSv: String,
    val weeks: List<List<String>>,
    val easyWeeks: Set<Int> = emptySet(),
) {
    val weekCount get() = weeks.size
    val workoutCount get() = weeks.sumOf { it.size }
    fun workoutId(slot: PlanSlot): String? = weeks.getOrNull(slot.week - 1)?.getOrNull(slot.index)?.let { "builtin-$it" }
    fun displayName(lang: String) = if (lang == "sv") nameSv else name
    fun displayDescription(lang: String) = if (lang == "sv") descriptionSv else description
}

/** Position in a plan: 1-based week, 0-based index within the week. */
@Serializable
data class PlanSlot(val week: Int, val index: Int) {
    /** Compact integer key for storage. */
    val key: Long get() = week * 100L + index

    companion object {
        fun fromKey(key: Long) = PlanSlot((key / 100).toInt(), (key % 100).toInt())
    }
}

/** The active plan of a profile (stored in profile settings). */
@Serializable
data class ActivePlan(val planId: String, val startEpochDay: Long)

object TrainingPlans {
    val ftpBuilder = TrainingPlan(
        "ftp-builder", "FTP Builder", "FTP-bygge",
        "6 weeks of sweet spot and threshold to raise your FTP. Ends with a ramp test.",
        "6 veckor sweet spot och tröskel för att höja din FTP. Avslutas med ett ramptest.",
        listOf(
            listOf("ss-3x10", "endurance-60", "tempo-3x10"),
            listOf("ss-2x15", "endurance-60", "thr-4x8"),
            listOf("ss-3x15", "endurance-90", "thr-over-under"),
            listOf("easy-spin", "ss-3x10", "endurance-45"),
            listOf("ss-2x20", "endurance-90", "thr-3x12"),
            listOf("thr-2x20", "easy-spin", "ftp-ramp"),
        ),
        easyWeeks = setOf(4),
    )

    val base = TrainingPlan(
        "base", "Base Training", "Grundträning",
        "8 weeks of mostly zone 2 with some tempo – builds endurance. Ends with a ramp test.",
        "8 veckor mest zon 2 med lite tempo – bygger uthållighet. Avslutas med ett ramptest.",
        listOf(
            listOf("endurance-45", "cadence-pyramid", "endurance-60"),
            listOf("endurance-60", "high-cadence", "endurance-builder"),
            listOf("endurance-60", "tempo-blocks", "endurance-90"),
            listOf("easy-spin", "endurance-45", "coffee-30"),
            listOf("endurance-builder", "tempo-3x10", "endurance-90"),
            listOf("endurance-60", "low-cadence", "endurance-90"),
            listOf("tempo-2x20", "endurance-60", "endurance-90"),
            listOf("easy-spin", "endurance-45", "ftp-ramp"),
        ),
        easyWeeks = setOf(4, 8),
    )

    val vo2 = TrainingPlan(
        "vo2-block", "VO2max Block", "VO2max-block",
        "4 weeks of short, hard intervals for top-end power. Ends with a ramp test.",
        "4 veckor korta, hårda intervaller för toppfart. Avslutas med ett ramptest.",
        listOf(
            listOf("vo2-5x3", "endurance-60", "vo2-30-30"),
            listOf("vo2-4x4", "endurance-60", "vo2-40-20"),
            listOf("vo2-pyramid", "endurance-45", "vo2-6x2"),
            listOf("easy-spin", "endurance-45", "ftp-ramp"),
        ),
        easyWeeks = setOf(4),
    )

    val gettingStarted = TrainingPlan(
        "getting-started", "Getting Started", "Komma igång",
        "4 gentle weeks with 2–3 short rides per week. Perfect after a break. Ends with a ramp test.",
        "4 lugna veckor med 2–3 korta pass i veckan. Perfekt efter ett uppehåll. Avslutas med ett ramptest.",
        listOf(
            listOf("coffee-30", "easy-spin"),
            listOf("coffee-30", "spin-ups-20", "endurance-45"),
            listOf("quick-20", "endurance-45", "lunch-30"),
            listOf("lunch-30", "endurance-45", "ftp-ramp"),
        ),
    )

    val all = listOf(gettingStarted, base, ftpBuilder, vo2)

    fun byId(id: String?): TrainingPlan? = all.firstOrNull { it.id == id }
}

enum class SlotStatus { DONE, SKIPPED, MISSED, NEXT, AVAILABLE, UPCOMING }

/** Recorded state of a slot. activityId > 0 = done, SKIPPED_ACTIVITY = skipped; [workoutId] set when swapped. */
data class SlotRecord(val slot: PlanSlot, val workoutId: String?, val activityId: Long?) {
    val done get() = activityId != null && activityId > 0
    val skipped get() = activityId == SKIPPED_ACTIVITY

    companion object {
        const val SKIPPED_ACTIVITY = -1L
    }
}

data class SlotView(val slot: PlanSlot, val workoutId: String, val status: SlotStatus, val swapped: Boolean, val activityId: Long?)

data class PlanState(
    val plan: TrainingPlan,
    val startEpochDay: Long,
    /** 1-based current week; > weekCount when the plan period is over. */
    val currentWeek: Int,
    val slots: List<SlotView>,
    /** Next workout by plan and schedule (in the current week), or null. */
    val next: SlotView?,
    val finished: Boolean,
) {
    fun week(w: Int) = slots.filter { it.slot.week == w }
    val doneCount get() = slots.count { it.status == SlotStatus.DONE }
    val missedCount get() = slots.count { it.status == SlotStatus.MISSED || it.status == SlotStatus.SKIPPED }
    val currentWeekDone get() = week(currentWeek).count { it.status == SlotStatus.DONE }
    val currentWeekTotal get() = week(currentWeek).size
    /** Days left in the current plan week (1..7). */
    fun daysLeftInWeek(todayEpochDay: Long): Int = (7 - ((todayEpochDay - startEpochDay) % 7)).toInt().coerceIn(1, 7)
}

object PlanProgress {
    /**
     * Computes slot statuses. Plan weeks are 7-day blocks from [startEpochDay]. Workouts are done in
     * order within the week; when a week passes, its unfinished workouts become MISSED (they can still
     * be done later and then count as DONE).
     */
    fun compute(plan: TrainingPlan, startEpochDay: Long, todayEpochDay: Long, records: List<SlotRecord>): PlanState {
        val byKey = records.associateBy { it.slot.key }
        val currentWeek = (((todayEpochDay - startEpochDay).coerceAtLeast(0)) / 7 + 1).toInt()
        val views = ArrayList<SlotView>()
        var next: SlotView? = null
        plan.weeks.forEachIndexed { wi, ids ->
            val week = wi + 1
            ids.forEachIndexed { i, _ ->
                val slot = PlanSlot(week, i)
                val rec = byKey[slot.key]
                val workoutId = rec?.workoutId ?: plan.workoutId(slot)!!
                val status = when {
                    rec?.done == true -> SlotStatus.DONE
                    rec?.skipped == true -> SlotStatus.SKIPPED
                    week < currentWeek -> SlotStatus.MISSED
                    week > currentWeek -> SlotStatus.UPCOMING
                    next == null -> SlotStatus.NEXT
                    else -> SlotStatus.AVAILABLE
                }
                val v = SlotView(slot, workoutId, status, rec?.workoutId != null, rec?.activityId?.takeIf { it > 0 })
                if (status == SlotStatus.NEXT) next = v
                views.add(v)
            }
        }
        val allHandled = views.none { it.status == SlotStatus.NEXT || it.status == SlotStatus.AVAILABLE || it.status == SlotStatus.UPCOMING }
        val finished = currentWeek > plan.weekCount || allHandled
        return PlanState(plan, startEpochDay, currentWeek, views, next, finished)
    }

    /**
     * Which slot a finished ride of [workoutId] should count for: the explicitly chosen [chosen] slot,
     * otherwise the NEXT slot if it is the same workout.
     */
    fun slotForRide(state: PlanState, workoutId: String, chosen: PlanSlot?): PlanSlot? {
        if (chosen != null) return chosen
        val n = state.next ?: return null
        return if (n.workoutId == workoutId) n.slot else null
    }
}
