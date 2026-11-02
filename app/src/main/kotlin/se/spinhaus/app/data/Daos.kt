package se.spinhaus.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY createdAt")
    fun observeAll(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles ORDER BY createdAt")
    suspend fun all(): List<ProfileEntity>

    @Query("SELECT * FROM profiles WHERE id = :id")
    fun observe(id: Long): Flow<ProfileEntity?>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun get(id: Long): ProfileEntity?

    @Insert
    suspend fun insert(p: ProfileEntity): Long

    @Update
    suspend fun update(p: ProfileEntity)

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface FtpDao {
    @Query("SELECT * FROM ftp_history WHERE profileId = :profileId ORDER BY dateMs")
    fun observeFor(profileId: Long): Flow<List<FtpEntryEntity>>

    @Query("SELECT * FROM ftp_history")
    suspend fun all(): List<FtpEntryEntity>

    @Insert
    suspend fun insert(e: FtpEntryEntity): Long

    @Query("DELETE FROM ftp_history WHERE profileId = :profileId")
    suspend fun deleteFor(profileId: Long)
}

@Dao
interface WorkoutDao {
    @Query("SELECT * FROM workouts ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<WorkoutEntity>>

    @Query("SELECT * FROM workouts")
    suspend fun all(): List<WorkoutEntity>

    @Query("SELECT * FROM workouts WHERE id = :id")
    suspend fun get(id: String): WorkoutEntity?

    @Upsert
    suspend fun upsert(w: WorkoutEntity)

    @Query("DELETE FROM workouts WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ActivityDao {
    @Query("SELECT * FROM activities WHERE status = 'COMPLETED' ORDER BY startedAt DESC")
    fun observeCompleted(): Flow<List<ActivityEntity>>

    @Query("SELECT * FROM activities WHERE status = 'COMPLETED' AND profileId = :profileId ORDER BY startedAt DESC")
    fun observeCompletedFor(profileId: Long): Flow<List<ActivityEntity>>

    @Query("SELECT * FROM activities WHERE status = 'COMPLETED' AND profileId = :profileId AND startedAt >= :fromMs ORDER BY startedAt")
    suspend fun completedSince(profileId: Long, fromMs: Long): List<ActivityEntity>

    @Query("SELECT * FROM activities WHERE status = 'IN_PROGRESS'")
    suspend fun inProgress(): List<ActivityEntity>

    @Query("SELECT * FROM activities")
    suspend fun all(): List<ActivityEntity>

    @Query("SELECT * FROM activities WHERE id = :id")
    fun observe(id: Long): Flow<ActivityEntity?>

    @Query("SELECT * FROM activities WHERE id = :id")
    suspend fun get(id: Long): ActivityEntity?

    @Insert
    suspend fun insert(a: ActivityEntity): Long

    @Update
    suspend fun update(a: ActivityEntity)

    @Query("DELETE FROM activities WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSamples(samples: List<SampleEntity>)

    @Query("SELECT * FROM samples WHERE activityId = :activityId ORDER BY t")
    suspend fun samples(activityId: Long): List<SampleEntity>

    @Query("SELECT * FROM samples")
    suspend fun allSamples(): List<SampleEntity>

    @Query("DELETE FROM samples WHERE activityId = :activityId")
    suspend fun deleteSamples(activityId: Long)
}

@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices ORDER BY lastSeen DESC")
    fun observeAll(): Flow<List<KnownDeviceEntity>>

    @Query("SELECT * FROM devices")
    suspend fun all(): List<KnownDeviceEntity>

    @Upsert
    suspend fun upsert(d: KnownDeviceEntity)

    @Query("DELETE FROM devices WHERE address = :address")
    suspend fun delete(address: String)

    @Query("DELETE FROM devices WHERE type = :type")
    suspend fun deleteType(type: String)
}

@Dao
interface PlanDao {
    @Query("SELECT * FROM training_plans")
    fun observePlans(): Flow<List<TrainingPlanEntity>>

    @Query("SELECT * FROM plan_items WHERE planId = :planId ORDER BY week, day")
    suspend fun items(planId: Long): List<PlanItemEntity>

    @Query("SELECT * FROM scheduled_workouts WHERE profileId = :profileId ORDER BY epochDay")
    fun observeScheduled(profileId: Long): Flow<List<ScheduledWorkoutEntity>>

    /** Records of one plan run (identified by its start day). planItemId holds the slot key. */
    @Query("SELECT * FROM scheduled_workouts WHERE profileId = :profileId AND epochDay = :startDay")
    fun observeRun(profileId: Long, startDay: Long): Flow<List<ScheduledWorkoutEntity>>

    @Query("SELECT * FROM scheduled_workouts WHERE profileId = :profileId AND epochDay = :startDay")
    suspend fun run(profileId: Long, startDay: Long): List<ScheduledWorkoutEntity>

    @Query("DELETE FROM scheduled_workouts WHERE profileId = :profileId AND epochDay = :startDay AND planItemId = :slotKey")
    suspend fun deleteSlot(profileId: Long, startDay: Long, slotKey: Long)

    @Query("DELETE FROM scheduled_workouts WHERE profileId = :profileId AND epochDay = :startDay")
    suspend fun deleteRun(profileId: Long, startDay: Long)

    @Insert
    suspend fun insertScheduled(e: ScheduledWorkoutEntity): Long
}
