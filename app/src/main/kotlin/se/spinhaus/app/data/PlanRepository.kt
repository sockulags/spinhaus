package se.spinhaus.app.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import se.spinhaus.core.plan.ActivePlan
import se.spinhaus.core.plan.PlanProgress
import se.spinhaus.core.plan.PlanSlot
import se.spinhaus.core.plan.PlanState
import se.spinhaus.core.plan.SlotRecord
import se.spinhaus.core.plan.TrainingPlans
import java.time.LocalDate

/** Training plan runs, stored in scheduled_workouts (epochDay = run start, planItemId = slot key). */
class PlanRepository(private val db: SpinhausDatabase, private val profiles: ProfileRepository) {
    private val dao get() = db.plans()

    fun today(): Long = LocalDate.now().toEpochDay()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeState(profileId: Long): Flow<PlanState?> = profiles.observe(profileId).flatMapLatest { p ->
        val active = p?.settings?.activePlan
        val plan = TrainingPlans.byId(active?.planId)
        if (p == null || active == null || plan == null) flowOf(null)
        else dao.observeRun(p.id, active.startEpochDay).map { rows ->
            PlanProgress.compute(plan, active.startEpochDay, today(), rows.map { it.toRecord() })
        }
    }

    suspend fun state(profileId: Long): PlanState? {
        val p = profiles.get(profileId) ?: return null
        val active = p.settings.activePlan ?: return null
        val plan = TrainingPlans.byId(active.planId) ?: return null
        return PlanProgress.compute(plan, active.startEpochDay, today(), dao.run(p.id, active.startEpochDay).map { it.toRecord() })
    }

    suspend fun start(profileId: Long, planId: String, startEpochDay: Long) {
        val p = profiles.get(profileId) ?: return
        // A previous run with the same start day would mix records – clear it.
        dao.deleteRun(profileId, startEpochDay)
        profiles.update(p.withSettings(p.settings.copy(activePlan = ActivePlan(planId, startEpochDay))))
    }

    suspend fun end(profileId: Long) {
        val p = profiles.get(profileId) ?: return
        profiles.update(p.withSettings(p.settings.copy(activePlan = null)))
    }

    private suspend fun setRecord(profileId: Long, slot: PlanSlot, workoutId: String?, activityId: Long?) {
        val active = profiles.get(profileId)?.settings?.activePlan ?: return
        val existing = dao.run(profileId, active.startEpochDay).firstOrNull { it.planItemId == slot.key }
        dao.deleteSlot(profileId, active.startEpochDay, slot.key)
        val keepSwap = workoutId ?: existing?.workoutId?.takeIf { it.isNotEmpty() }
        dao.insertScheduled(
            ScheduledWorkoutEntity(
                profileId = profileId, epochDay = active.startEpochDay, workoutId = keepSwap ?: "",
                planItemId = slot.key, activityId = activityId,
            ),
        )
    }

    suspend fun skip(profileId: Long, slot: PlanSlot) = setRecord(profileId, slot, null, SlotRecord.SKIPPED_ACTIVITY)

    suspend fun unskip(profileId: Long, slot: PlanSlot) = setRecord(profileId, slot, null, null)

    suspend fun swap(profileId: Long, slot: PlanSlot, workoutId: String) = setRecord(profileId, slot, workoutId, null)

    suspend fun markDone(profileId: Long, slot: PlanSlot, activityId: Long) = setRecord(profileId, slot, null, activityId)

    private fun ScheduledWorkoutEntity.toRecord() = SlotRecord(
        PlanSlot.fromKey(planItemId ?: 0L),
        workoutId.takeIf { it.isNotEmpty() },
        activityId,
    )
}
