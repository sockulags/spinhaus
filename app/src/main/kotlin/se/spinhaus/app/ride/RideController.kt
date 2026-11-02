package se.spinhaus.app.ride

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import se.spinhaus.app.ble.DeviceHub
import se.spinhaus.app.ble.DeviceStatus
import se.spinhaus.app.data.ActivityEntity
import se.spinhaus.app.data.ActivityRepository
import se.spinhaus.app.data.ProfileEntity
import se.spinhaus.app.data.ProfileRepository
import se.spinhaus.app.data.settings
import se.spinhaus.app.ui.tr
import se.spinhaus.core.ble.ButtonEdgeTracker
import se.spinhaus.core.ble.RideButton
import se.spinhaus.core.ride.ButtonBinding
import se.spinhaus.core.ride.ControlAction
import se.spinhaus.core.ride.ControlInterpreter
import se.spinhaus.core.ride.PhysicsParams
import se.spinhaus.core.ride.RideConfig
import se.spinhaus.core.ride.RideEngine
import se.spinhaus.core.ride.RideEvent
import se.spinhaus.core.ride.RideMode
import se.spinhaus.core.ride.RideSnapshot
import se.spinhaus.core.ride.RideStatus
import se.spinhaus.core.training.FtpEstimator
import se.spinhaus.core.training.HrZoneModel
import se.spinhaus.core.training.Sample
import se.spinhaus.core.workout.ExpandedWorkout
import se.spinhaus.core.workout.FtpTests

data class RideSession(
    val profile: ProfileEntity,
    val mode: RideMode,
    val title: String,
    val type: String,
    val activityId: Long,
    val expanded: ExpandedWorkout?,
    /** Plan slot this ride was started for, if any. */
    val planSlot: se.spinhaus.core.plan.PlanSlot? = null,
)

data class RideResult(
    val activityId: Long?,
    val testFtp: Int? = null,
    val estimatedFtp: Int? = null,
)

/**
 * Runs a ride: feeds Bluetooth data into the [RideEngine], sends trainer commands, plays cues,
 * and persists samples. Lives in the Application so it survives screen changes.
 */
class RideController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val hub: DeviceHub,
    private val activities: ActivityRepository,
    private val profiles: ProfileRepository,
    private val plans: se.spinhaus.app.data.PlanRepository,
) {
    private var engine: RideEngine? = null
    private var tickJob: Job? = null
    private var buttonJob: Job? = null
    private val audio = AudioCues(context)
    private val mutex = Mutex()
    private var savedSamples = 0
    private val trackers = HashMap<String, ButtonEdgeTracker>()
    private val interpreter = ControlInterpreter()
    private val lastPress = HashMap<RideButton, Pair<String, Long>>()

    private val _session = MutableStateFlow<RideSession?>(null)
    val session: StateFlow<RideSession?> = _session
    private val _snapshot = MutableStateFlow<RideSnapshot?>(null)
    val snapshot: StateFlow<RideSnapshot?> = _snapshot
    private val _samplesVersion = MutableStateFlow(0)
    val samplesVersion: StateFlow<Int> = _samplesVersion
    private val _events = MutableSharedFlow<RideEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<RideEvent> = _events

    val active: Boolean get() = _session.value != null

    fun samples(): List<Sample> = engine?.samples()?.toList() ?: emptyList()

    suspend fun start(
        profile: ProfileEntity, mode: RideMode, title: String, type: String,
        planSlot: se.spinhaus.core.plan.PlanSlot? = null,
    ): Boolean = mutex.withLock {
        if (_session.value != null) return false
        val s = profile.settings
        val trainerCaps = hub.trainer.value?.capabilities?.value
        val config = RideConfig(
            mode = mode,
            ftp = profile.ftp,
            physics = PhysicsParams(riderKg = profile.weightKg),
            gears = s.gears,
            maxHr = profile.maxHr,
            restHr = profile.restHr,
            hrModel = runCatching { HrZoneModel.valueOf(profile.hrModel) }.getOrDefault(HrZoneModel.PERCENT_MAX),
            powerRange = trainerCaps?.powerRange ?: 0..2000,
        )
        val e = RideEngine(config)
        val workout = (mode as? RideMode.Structured)?.workout
        val id = activities.start(
            ActivityEntity(
                profileId = profile.id, type = type, workoutId = workout?.id, title = title,
                routeId = (mode as? RideMode.Free)?.route?.id, startedAt = System.currentTimeMillis(), ftpAtTime = profile.ftp,
            ),
        )
        engine = e
        savedSamples = 0
        trackers.clear()
        _session.value = RideSession(profile, mode, title, type, id, workout?.let { ExpandedWorkout.of(it) }, planSlot)
        audio.ftp = profile.ftp
        audio.absolute = workout?.absolutePower == true
        audio.maxHr = profile.maxHr
        audio.prepare(s.sound, profile.language)
        RideService.start(context)
        if (senderJob?.isActive != true) senderJob = scope.launch { senderLoop() }
        tickJob = scope.launch { tickLoop(e) }
        buttonJob = scope.launch { buttonLoop(e) }
        true
    }

    fun action(a: ControlAction) {
        val e = engine ?: return
        e.onAction(a)
        afterInput(e)
    }

    fun setManualGrade(g: Double) {
        val e = engine ?: return
        e.setManualGrade(g)
        afterInput(e)
    }

    private fun afterInput(e: RideEngine) {
        flushCommand(e)
        dispatchEvents(e)
        _snapshot.value = e.snapshot(System.currentTimeMillis())
    }

    /** Latest command wins; a single sender coroutine talks to the trainer so the tick loop never blocks. */
    private val commands = kotlinx.coroutines.channels.Channel<Pair<RideEngine, se.spinhaus.core.ride.TrainerCommand>>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private var senderJob: Job? = null

    private fun flushCommand(e: RideEngine) {
        val cmd = e.takeCommand() ?: return
        commands.trySend(e to cmd)
    }

    private suspend fun senderLoop() {
        for ((e, cmd) in commands) {
            if (engine !== e) continue
            val ok = hub.trainer.value?.send(cmd) ?: false
            if (!ok) e.invalidateTrainer()
        }
    }

    private val audioManager = context.getSystemService(android.media.AudioManager::class.java)

    private fun mediaKey(code: Int) {
        runCatching {
            audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
            audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
        }
    }

    /** Film control: only when another app is in front (film mode). */
    private fun filmMode() = !appVisibleProvider()
    var appVisibleProvider: () -> Boolean = { true }

    private fun dispatchEvents(e: RideEngine) {
        for (ev in e.drainEvents()) {
            val film = _session.value?.profile?.settings?.film
            when {
                ev is RideEvent.UiAction && ev.action == ControlAction.MEDIA_PLAY_PAUSE -> mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                ev is RideEvent.Paused && film?.pauseWithRide == true && filmMode() -> mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE)
                ev is RideEvent.Resumed && film?.pauseWithRide == true && filmMode() -> mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
            }
            audio.handle(ev)
            if (ev is RideEvent.Shifted && _session.value?.profile?.settings?.vibrateOnShift == true) hub.vibrateControllers()
            _events.tryEmit(ev)
        }
    }

    private suspend fun tickLoop(e: RideEngine) {
        var lastTrainerAt = 0L
        var lastHrAt = 0L
        var lastTrainerStatus: DeviceStatus? = null
        var lastHrStatus: DeviceStatus? = null
        var lastSave = System.currentTimeMillis()
        val lang = _session.value?.profile?.language ?: "sv"
        while (scope.isActive && engine === e) {
            val now = System.currentTimeMillis()
            val trainer = hub.trainer.value
            trainer?.data?.value?.let { d ->
                if (d.atMs != lastTrainerAt && d.atMs != 0L) {
                    lastTrainerAt = d.atMs
                    d.powerW?.let { e.onPower(it, d.atMs) }
                    d.cadenceRpm?.let { e.onCadence(it, d.atMs) }
                }
            }
            hub.heartRate.value?.let { hr ->
                val bpm = hr.bpm.value
                if (bpm != null && hr.lastAtMs != lastHrAt) {
                    lastHrAt = hr.lastAtMs
                    e.onHeartRate(bpm, hr.lastAtMs)
                }
                val st = hr.status.value
                if (lastHrStatus == DeviceStatus.CONNECTED && st != DeviceStatus.CONNECTED) audio.connectionLost(tr(lang, "Heart rate", "Pulsmätaren"))
                lastHrStatus = st
            }
            // trainer connection transitions
            val ts = trainer?.status?.value
            if (ts != lastTrainerStatus) {
                if (lastTrainerStatus == DeviceStatus.CONNECTED && ts != DeviceStatus.CONNECTED) audio.connectionLost(tr(lang, "Trainer", "Trainern"))
                if (ts == DeviceStatus.CONNECTED) {
                    if (lastTrainerStatus != null && lastTrainerStatus != DeviceStatus.CONNECTED) audio.connectionRestored(tr(lang, "Trainer", "Trainern"))
                    e.invalidateTrainer()
                }
                lastTrainerStatus = ts
            }
            e.tick(now)
            // long-press handling
            val ergActive = e.snapshot(now).ergActive
            val held = HashMap<RideButton, Long>()
            trackers.values.forEach { held.putAll(it.heldButtons(now)) }
            for (a in interpreter.onHeld(held) { b -> bindingFor(b, ergActive) }) e.onAction(a)

            flushCommand(e)
            dispatchEvents(e)
            val snap = e.snapshot(now)
            _snapshot.value = snap
            val n = e.samples().size
            if (n != _samplesVersion.value) _samplesVersion.value = n
            if (now - lastSave > 10_000) {
                lastSave = now
                persistSamples(e)
            }
            delay(250)
        }
    }

    private fun bindingFor(b: RideButton, ergActive: Boolean): ButtonBinding {
        val mapping = _session.value?.profile?.settings?.mapping ?: return ButtonBinding()
        val map = if (ergActive) mapping.erg else mapping.sim
        val defaults = if (ergActive) se.spinhaus.core.ride.ControllerMapping.defaultErg else se.spinhaus.core.ride.ControllerMapping.defaultSim
        // Buttons missing from an older saved mapping fall back to the defaults (e.g. new actions).
        return map[b] ?: defaults[b] ?: ButtonBinding()
    }

    private suspend fun buttonLoop(e: RideEngine) {
        hub.buttons.collect { (address, pressed) ->
            if (engine !== e) return@collect
            val now = System.currentTimeMillis()
            val tracker = trackers.getOrPut(address) { ButtonEdgeTracker() }
            // Both Ride halves may report the same physical press; keep the first one.
            val edges = tracker.update(pressed, now).filter { edge ->
                if (edge !is ButtonEdgeTracker.Edge.Pressed) return@filter true
                val prev = lastPress[edge.button]
                val dup = prev != null && prev.first != address && now - prev.second < 250
                if (!dup) lastPress[edge.button] = address to now
                !dup
            }
            if (edges.isEmpty()) return@collect
            val ergActive = e.snapshot(now).ergActive
            for (a in interpreter.onEdges(edges) { b -> bindingFor(b, ergActive) }) e.onAction(a)
            afterInput(e)
        }
    }

    private suspend fun persistSamples(e: RideEngine) {
        val id = _session.value?.activityId ?: return
        val all = e.samples()
        if (all.size <= savedSamples) return
        val chunk = all.subList(savedSamples, all.size).toList()
        withContext(Dispatchers.IO) { activities.appendSamples(id, chunk) }
        savedSamples += chunk.size
    }

    fun togglePause() = action(ControlAction.PAUSE_RESUME)

    /** Ends the ride. When [save] is false the activity is discarded. */
    suspend fun end(save: Boolean): RideResult = mutex.withLock {
        val e = engine ?: return RideResult(null)
        val s = _session.value ?: return RideResult(null)
        e.finish()
        tickJob?.cancel()
        buttonJob?.cancel()
        // put trainer back to a neutral state
        runCatching { hub.trainer.value?.send(se.spinhaus.core.ride.TrainerCommand.Simulation(se.spinhaus.core.ride.SimParams(0.0, 0.004, 0.51))) }
        persistSamples(e)
        val samples = e.samples().toList()
        engine = null
        _session.value = null
        _snapshot.value = null
        audio.release()
        RideService.stop(context)
        if (!save) {
            activities.delete(s.activityId)
            return RideResult(null)
        }
        val done = activities.complete(s.activityId, profiles.get(s.profile.id), s.title) ?: return RideResult(null)
        val workout = (s.mode as? RideMode.Structured)?.workout
        // Count the ride for the training plan (chosen slot, or the next slot if it is the same workout).
        if (workout != null) runCatching {
            plans.state(s.profile.id)?.let { st ->
                se.spinhaus.core.plan.PlanProgress.slotForRide(st, workout.id, s.planSlot)?.let { slot ->
                    plans.markDone(s.profile.id, slot, done.id)
                }
            }
        }
        val testFtp = if (workout?.ftpTest != null) FtpTests.calculateFtp(workout, samples) else null
        val estimate = if (testFtp == null) {
            val since = System.currentTimeMillis() - 42L * 24 * 3600 * 1000
            val best = activities.completedSince(s.profile.id, since).mapNotNull { it.best20minW }.maxOrNull()
            FtpEstimator.suggest(best, s.profile.ftp)
        } else null
        RideResult(done.id, testFtp, estimate)
    }
}
