package se.spinhaus.app.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import androidx.room.withTransaction
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

@Serializable
data class BackupFile(
    val version: Int = 1,
    val createdAt: Long,
    val profiles: List<BProfile>,
    val ftp: List<BFtp>,
    val workouts: List<BWorkout>,
    val activities: List<BActivity>,
    val samples: List<BSample>,
)

@Serializable
data class BProfile(
    val id: Long, val name: String, val colorArgb: Int, val language: String, val ftp: Int, val weightKg: Double,
    val birthYear: Int?, val maxHr: Int, val restHr: Int, val hrModel: String, val hrSensorAddress: String?,
    val hrSensorName: String?, val settingsJson: String, val createdAt: Long,
)

@Serializable
data class BFtp(val id: Long, val profileId: Long, val watts: Int, val dateMs: Long, val source: String, val activityId: Long?)

@Serializable
data class BWorkout(val id: String, val json: String, val updatedAt: Long)

@Serializable
data class BActivity(
    val id: Long, val profileId: Long, val type: String, val workoutId: String?, val title: String, val routeId: String?,
    val startedAt: Long, val durationS: Int, val movingS: Int, val distanceM: Double, val elevationM: Double, val avgW: Int,
    val maxW: Int, val npW: Int?, val intensityFactor: Double?, val tss: Double?, val avgHr: Int?, val maxHr: Int?,
    val avgCadence: Int?, val kj: Int, val kcal: Int, val ftpAtTime: Int, val best20minW: Int?, val feel: Int?,
    val note: String?, val summaryJson: String?, val status: String,
)

@Serializable
data class BSample(
    val a: Long, val t: Int, val p: Int, val tg: Int? = null, val hr: Int? = null, val c: Int? = null, val s: Double? = null,
    val d: Double = 0.0, val al: Double? = null, val g: Double? = null, val gr: Int? = null, val wt: Int? = null,
)

/** Exports/imports the whole database as a gzipped JSON file. */
class BackupManager(private val context: Context, private val db: SpinhausDatabase) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    suspend fun export(uri: Uri) = withContext(Dispatchers.IO) {
        val b = BackupFile(
            createdAt = System.currentTimeMillis(),
            profiles = db.profiles().all().map {
                BProfile(it.id, it.name, it.colorArgb, it.language, it.ftp, it.weightKg, it.birthYear, it.maxHr, it.restHr,
                    it.hrModel, it.hrSensorAddress, it.hrSensorName, it.settingsJson, it.createdAt)
            },
            ftp = db.ftp().all().map { BFtp(it.id, it.profileId, it.watts, it.dateMs, it.source, it.activityId) },
            workouts = db.workouts().all().map { BWorkout(it.id, it.json, it.updatedAt) },
            activities = db.activities().all().map {
                BActivity(it.id, it.profileId, it.type, it.workoutId, it.title, it.routeId, it.startedAt, it.durationS, it.movingS,
                    it.distanceM, it.elevationM, it.avgW, it.maxW, it.npW, it.intensityFactor, it.tss, it.avgHr, it.maxHr,
                    it.avgCadence, it.kj, it.kcal, it.ftpAtTime, it.best20minW, it.feel, it.note, it.summaryJson, it.status)
            },
            samples = db.activities().allSamples().map {
                BSample(it.activityId, it.t, it.power, it.target, it.hr, it.cadence, it.speedKmh, it.distanceM, it.altitudeM, it.grade, it.gear, it.workoutT)
            },
        )
        context.contentResolver.openOutputStream(uri)?.use { os ->
            GZIPOutputStream(os).bufferedWriter().use { it.write(json.encodeToString(BackupFile.serializer(), b)) }
        } ?: error("Cannot open file")
    }

    /** Replaces all data with the backup's content. */
    suspend fun import(uri: Uri): Int = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.use { GZIPInputStream(it).bufferedReader().readText() }
            ?: error("Cannot open file")
        val b = json.decodeFromString(BackupFile.serializer(), text)
        db.withTransaction {
        for (t in listOf("samples", "activities", "ftp_history", "workouts", "profiles", "plan_items", "training_plans", "scheduled_workouts")) {
            db.openHelper.writableDatabase.execSQL("DELETE FROM $t")
        }
        for (p in b.profiles) db.profiles().insert(
            ProfileEntity(p.id, p.name, p.colorArgb, p.language, p.ftp, p.weightKg, p.birthYear, p.maxHr, p.restHr, p.hrModel,
                p.hrSensorAddress, p.hrSensorName, p.settingsJson, p.createdAt),
        )
        for (f in b.ftp) db.ftp().insert(FtpEntryEntity(f.id, f.profileId, f.watts, f.dateMs, f.source, f.activityId))
        for (w in b.workouts) db.workouts().upsert(WorkoutEntity(w.id, w.json, w.updatedAt))
        for (a in b.activities) db.activities().insert(
            ActivityEntity(a.id, a.profileId, a.type, a.workoutId, a.title, a.routeId, a.startedAt, a.durationS, a.movingS, a.distanceM,
                a.elevationM, a.avgW, a.maxW, a.npW, a.intensityFactor, a.tss, a.avgHr, a.maxHr, a.avgCadence, a.kj, a.kcal,
                a.ftpAtTime, a.best20minW, a.feel, a.note, a.summaryJson, a.status),
        )
        b.samples.chunked(2000).forEach { chunk ->
            db.activities().insertSamples(chunk.map { SampleEntity(it.a, it.t, it.p, it.tg, it.hr, it.c, it.s, it.d, it.al, it.g, it.gr, it.wt) })
        }
        }
        b.activities.size
    }
}
