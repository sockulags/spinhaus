package se.spinhaus.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val colorArgb: Int,
    val language: String = "sv",
    val ftp: Int = 200,
    val weightKg: Double = 75.0,
    val birthYear: Int? = null,
    val maxHr: Int = 185,
    val restHr: Int = 60,
    val hrModel: String = "PERCENT_MAX",
    val hrSensorAddress: String? = null,
    val hrSensorName: String? = null,
    val settingsJson: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "ftp_history", indices = [Index("profileId")])
data class FtpEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val watts: Int,
    val dateMs: Long,
    /** TEST, MANUAL or ESTIMATE */
    val source: String,
    val activityId: Long? = null,
)

/** Custom and imported workouts. Built-in workouts live in code (WorkoutLibrary). */
@Entity(tableName = "workouts")
data class WorkoutEntity(
    @PrimaryKey val id: String,
    val json: String,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "activities", indices = [Index("profileId"), Index("startedAt")])
data class ActivityEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    /** WORKOUT, FREE or TEST */
    val type: String,
    val workoutId: String? = null,
    val title: String,
    val routeId: String? = null,
    val startedAt: Long,
    val durationS: Int = 0,
    val movingS: Int = 0,
    val distanceM: Double = 0.0,
    val elevationM: Double = 0.0,
    val avgW: Int = 0,
    val maxW: Int = 0,
    val npW: Int? = null,
    val intensityFactor: Double? = null,
    val tss: Double? = null,
    val avgHr: Int? = null,
    val maxHr: Int? = null,
    val avgCadence: Int? = null,
    val kj: Int = 0,
    val kcal: Int = 0,
    val ftpAtTime: Int,
    val best20minW: Int? = null,
    val feel: Int? = null,
    val note: String? = null,
    val summaryJson: String? = null,
    /** IN_PROGRESS or COMPLETED */
    val status: String = "IN_PROGRESS",
)

@Entity(tableName = "samples", primaryKeys = ["activityId", "t"])
data class SampleEntity(
    val activityId: Long,
    val t: Int,
    val power: Int,
    val target: Int?,
    val hr: Int?,
    val cadence: Int?,
    val speedKmh: Double?,
    val distanceM: Double,
    val altitudeM: Double?,
    val grade: Double?,
    val gear: Int?,
    val workoutT: Int?,
)

/** Remembered Bluetooth devices. type: TRAINER, CONTROLLER, HR */
@Entity(tableName = "devices")
data class KnownDeviceEntity(
    @PrimaryKey val address: String,
    val name: String,
    val type: String,
    val lastSeen: Long = System.currentTimeMillis(),
)

// --- Prepared for training plans (no UI yet) ---

@Entity(tableName = "training_plans")
data class TrainingPlanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String = "",
    val weeks: Int,
)

@Entity(tableName = "plan_items", indices = [Index("planId")])
data class PlanItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val planId: Long,
    val week: Int,
    val day: Int,
    val workoutId: String,
    val note: String? = null,
)

@Entity(tableName = "scheduled_workouts", indices = [Index("profileId")])
data class ScheduledWorkoutEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val epochDay: Long,
    val workoutId: String,
    val planItemId: Long? = null,
    val activityId: Long? = null,
)
