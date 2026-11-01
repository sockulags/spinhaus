package se.spinhaus.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import se.spinhaus.core.training.ActivityCalculator
import se.spinhaus.core.training.ActivitySummary
import se.spinhaus.core.training.HrZoneModel
import se.spinhaus.core.training.Sample
import se.spinhaus.core.workout.Workout
import se.spinhaus.core.workout.WorkoutJson
import se.spinhaus.core.workout.WorkoutLibrary
import kotlinx.serialization.json.Json

class ProfileRepository(private val db: SpinhausDatabase) {
    fun observeAll() = db.profiles().observeAll()
    fun observe(id: Long) = db.profiles().observe(id)
    suspend fun get(id: Long) = db.profiles().get(id)
    suspend fun all() = db.profiles().all()

    suspend fun create(p: ProfileEntity): Long {
        val id = db.profiles().insert(p)
        db.ftp().insert(FtpEntryEntity(profileId = id, watts = p.ftp, dateMs = System.currentTimeMillis(), source = "MANUAL"))
        return id
    }

    suspend fun update(p: ProfileEntity) {
        val old = db.profiles().get(p.id)
        db.profiles().update(p)
        if (old != null && old.ftp != p.ftp) {
            db.ftp().insert(FtpEntryEntity(profileId = p.id, watts = p.ftp, dateMs = System.currentTimeMillis(), source = "MANUAL"))
        }
    }

    suspend fun setFtp(profileId: Long, watts: Int, source: String, activityId: Long? = null) {
        val p = db.profiles().get(profileId) ?: return
        db.profiles().update(p.copy(ftp = watts))
        db.ftp().insert(FtpEntryEntity(profileId = profileId, watts = watts, dateMs = System.currentTimeMillis(), source = source, activityId = activityId))
    }

    suspend fun delete(id: Long) {
        db.ftp().deleteFor(id)
        db.profiles().delete(id)
    }

    fun ftpHistory(profileId: Long) = db.ftp().observeFor(profileId)
}

class WorkoutRepository(private val db: SpinhausDatabase) {
    /** Built-in + custom workouts. */
    fun observeAll(): Flow<List<Workout>> = db.workouts().observeAll().map { list ->
        list.mapNotNull { runCatching { WorkoutJson.decode(it.json) }.getOrNull() } + WorkoutLibrary.all
    }

    suspend fun get(id: String): Workout? =
        WorkoutLibrary.byId(id) ?: db.workouts().get(id)?.let { runCatching { WorkoutJson.decode(it.json) }.getOrNull() }

    suspend fun save(w: Workout) = db.workouts().upsert(WorkoutEntity(w.id, WorkoutJson.encode(w)))
    suspend fun delete(id: String) = db.workouts().delete(id)
}

class DeviceRepository(private val db: SpinhausDatabase) {
    fun observeAll() = db.devices().observeAll()
    suspend fun all() = db.devices().all()
    suspend fun remember(address: String, name: String, type: String) = db.devices().upsert(KnownDeviceEntity(address, name, type))
    suspend fun forget(address: String) = db.devices().delete(address)
    suspend fun forgetType(type: String) = db.devices().deleteType(type)
}

class ActivityRepository(private val db: SpinhausDatabase) {
    private val json = Json { ignoreUnknownKeys = true }

    fun observeCompleted() = db.activities().observeCompleted()
    fun observeCompletedFor(profileId: Long) = db.activities().observeCompletedFor(profileId)
    fun observe(id: Long) = db.activities().observe(id)
    suspend fun get(id: Long) = db.activities().get(id)
    suspend fun completedSince(profileId: Long, fromMs: Long) = db.activities().completedSince(profileId, fromMs)

    suspend fun start(a: ActivityEntity): Long = db.activities().insert(a)

    suspend fun appendSamples(activityId: Long, samples: List<Sample>) {
        if (samples.isEmpty()) return
        db.activities().insertSamples(samples.map { it.toEntity(activityId) })
    }

    suspend fun samples(activityId: Long): List<Sample> = db.activities().samples(activityId).map { it.toSample() }

    suspend fun complete(activityId: Long, profile: ProfileEntity?, title: String? = null): ActivityEntity? {
        val a = db.activities().get(activityId) ?: return null
        val samples = samples(activityId)
        if (samples.size < 30) {
            delete(activityId)
            return null
        }
        val summary = ActivityCalculator.summarize(
            samples, a.ftpAtTime, profile?.maxHr ?: 185, profile?.restHr ?: 60,
            runCatching { HrZoneModel.valueOf(profile?.hrModel ?: "PERCENT_MAX") }.getOrDefault(HrZoneModel.PERCENT_MAX),
        )
        val done = a.copy(
            title = title ?: a.title,
            durationS = summary.durationS, movingS = summary.movingS, distanceM = summary.distanceM,
            elevationM = summary.elevationGainM, avgW = summary.avgPower, maxW = summary.maxPower,
            npW = summary.normalizedPower, intensityFactor = summary.intensityFactor, tss = summary.tss,
            avgHr = summary.avgHr, maxHr = summary.maxHr, avgCadence = summary.avgCadence, kj = summary.kj,
            kcal = summary.kcal, best20minW = summary.bests[1200],
            summaryJson = json.encodeToString(ActivitySummary.serializer(), summary), status = "COMPLETED",
        )
        db.activities().update(done)
        return done
    }

    fun summaryOf(a: ActivityEntity): ActivitySummary? =
        a.summaryJson?.let { runCatching { json.decodeFromString(ActivitySummary.serializer(), it) }.getOrNull() }

    suspend fun update(a: ActivityEntity) = db.activities().update(a)

    suspend fun delete(activityId: Long) {
        db.activities().deleteSamples(activityId)
        db.activities().delete(activityId)
    }

    /** Finalises rides that were interrupted (app killed / crash). */
    suspend fun recoverInterrupted(profiles: ProfileRepository): Int {
        var n = 0
        for (a in db.activities().inProgress()) {
            if (complete(a.id, profiles.get(a.profileId)) != null) n++
        }
        return n
    }
}

fun Sample.toEntity(activityId: Long) = SampleEntity(
    activityId, t, power, target, hr, cadence, speedKmh, distanceM, altitudeM, grade, gear, workoutT,
)

fun SampleEntity.toSample() = Sample(
    t = t, power = power, target = target, hr = hr, cadence = cadence, speedKmh = speedKmh,
    distanceM = distanceM, altitudeM = altitudeM, grade = grade, gear = gear, workoutT = workoutT,
)
