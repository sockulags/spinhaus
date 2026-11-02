package se.spinhaus.core

import se.spinhaus.core.plan.PlanProgress
import se.spinhaus.core.plan.PlanSlot
import se.spinhaus.core.plan.SlotRecord
import se.spinhaus.core.plan.SlotStatus
import se.spinhaus.core.plan.TrainingPlans
import se.spinhaus.core.workout.WorkoutLibrary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlanTest {
    private val plan = TrainingPlans.ftpBuilder
    private val start = 1000L

    @Test
    fun allPlanWorkoutsExist() {
        for (p in TrainingPlans.all) for (w in 1..p.weekCount) for (i in p.weeks[w - 1].indices) {
            val id = p.workoutId(PlanSlot(w, i))!!
            assertNotNull(WorkoutLibrary.byId(id), "${p.id} week $w: $id missing")
        }
    }

    @Test
    fun firstDayNextIsFirstWorkout() {
        val s = PlanProgress.compute(plan, start, start, emptyList())
        assertEquals(1, s.currentWeek)
        assertEquals(PlanSlot(1, 0), s.next!!.slot)
        assertEquals(SlotStatus.AVAILABLE, s.slots[1].status)
        assertEquals(SlotStatus.UPCOMING, s.slots[3].status)
        assertEquals(7, s.daysLeftInWeek(start))
    }

    @Test
    fun doneAndSkippedAdvanceNext() {
        val recs = listOf(SlotRecord(PlanSlot(1, 0), null, 5), SlotRecord(PlanSlot(1, 1), null, SlotRecord.SKIPPED_ACTIVITY))
        val s = PlanProgress.compute(plan, start, start + 2, recs)
        assertEquals(PlanSlot(1, 2), s.next!!.slot)
        assertEquals(1, s.currentWeekDone)
    }

    @Test
    fun weekPassesMarksMissedAndMovesOn() {
        val recs = listOf(SlotRecord(PlanSlot(1, 0), null, 5))
        val s = PlanProgress.compute(plan, start, start + 8, recs)
        assertEquals(2, s.currentWeek)
        assertEquals(SlotStatus.MISSED, s.slots[1].status)
        assertEquals(SlotStatus.MISSED, s.slots[2].status)
        assertEquals(PlanSlot(2, 0), s.next!!.slot)
        assertEquals(6, s.daysLeftInWeek(start + 8))
    }

    @Test
    fun missedCanBeDoneLater() {
        val recs = listOf(SlotRecord(PlanSlot(1, 1), null, 9))
        val s = PlanProgress.compute(plan, start, start + 10, recs)
        assertEquals(SlotStatus.DONE, s.slots[1].status)
    }

    @Test
    fun swapChangesWorkout() {
        val recs = listOf(SlotRecord(PlanSlot(1, 0), "builtin-lunch-30", null))
        val s = PlanProgress.compute(plan, start, start, recs)
        assertEquals("builtin-lunch-30", s.next!!.workoutId)
        assertTrue(s.next!!.swapped)
    }

    @Test
    fun finishedAfterLastWeek() {
        val s = PlanProgress.compute(plan, start, start + 7 * 6, emptyList())
        assertTrue(s.finished)
        assertNull(s.next)
    }

    @Test
    fun rideCountsForNextIfSameWorkout() {
        val s = PlanProgress.compute(plan, start, start, emptyList())
        assertEquals(PlanSlot(1, 0), PlanProgress.slotForRide(s, "builtin-ss-3x10", null))
        assertNull(PlanProgress.slotForRide(s, "builtin-vo2-4x4", null))
        assertEquals(PlanSlot(1, 2), PlanProgress.slotForRide(s, "x", PlanSlot(1, 2)))
    }
}
