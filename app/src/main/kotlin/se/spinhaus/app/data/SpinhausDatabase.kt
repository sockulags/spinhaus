package se.spinhaus.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ProfileEntity::class, FtpEntryEntity::class, WorkoutEntity::class, ActivityEntity::class,
        SampleEntity::class, KnownDeviceEntity::class, TrainingPlanEntity::class, PlanItemEntity::class,
        ScheduledWorkoutEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class SpinhausDatabase : RoomDatabase() {
    abstract fun profiles(): ProfileDao
    abstract fun ftp(): FtpDao
    abstract fun workouts(): WorkoutDao
    abstract fun activities(): ActivityDao
    abstract fun devices(): DeviceDao
    abstract fun plans(): PlanDao

    companion object {
        const val NAME = "spinhaus.db"

        fun create(context: Context): SpinhausDatabase =
            Room.databaseBuilder(context, SpinhausDatabase::class.java, NAME)
                // Add Migration objects here when the schema changes. Never use destructive migration:
                // the history must survive updates.
                .build()
    }
}
