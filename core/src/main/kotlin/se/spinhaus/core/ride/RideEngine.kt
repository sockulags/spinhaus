package se.spinhaus.core.ride

import se.spinhaus.core.training.HeartRateZones
import se.spinhaus.core.training.HrZoneModel
import se.spinhaus.core.training.PowerZones
import se.spinhaus.core.training.Sample
import se.spinhaus.core.workout.ExpandedWorkout
import se.spinhaus.core.workout.FtpTestKind
import se.spinhaus.core.workout.FtpTests
import se.spinhaus.core.workout.Step
import se.spinhaus.core.workout.Workout
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

sealed class RideMode {
    data class Structured(val workout: Workout) : RideMode()
    /** Free ride on a route, or manual grade when [route] is null. */
    data class Free(val route: Route?, val goalDistanceM: Double? = null, val goalTimeS: Int? = null) : RideMode()
}

data class RideConfig(
    val mode: RideMode,
    val ftp: Int,
    val physics: PhysicsParams = PhysicsParams(),
    val gears: GearTable = GearTable(),
    val maxHr: Int = 190,
    val restHr: Int = 60,
    val hrModel: HrZoneModel = HrZoneModel.PERCENT_MAX,
    val autoPauseSeconds: Int = 3,
    val intensityStep: Double = 0.05,
    val gradeStep: Double = 0.5,
    val powerRange: IntRange = 0..2000,
)

sealed class TrainerCommand {
    data class TargetPower(val watts: Int) : TrainerCommand()
    data class Simulation(val params: SimParams) : TrainerCommand()
}

sealed class RideEvent {
    data class StepStarted(val step: Step) : RideEvent()
    data class Countdown(val secondsLeft: Int) : RideEvent()
    data object Halfway : RideEvent()
    data object WorkoutComplete : RideEvent()
    data class Text(val message: String) : RideEvent()
    data object LowCadence : RideEvent()
    data object RampTestEnded : RideEvent()
    data class Shifted(val gear: Int) : RideEvent()
    data class IntensityChanged(val percent: Int) : RideEvent()
    data class ErgToggled(val enabled: Boolean) : RideEvent()
    data class Lap(val number: Int) : RideEvent()
    data object Paused : RideEvent()
    data object Resumed : RideEvent()
    data object GoalReached : RideEvent()
    data object HeartRateMissing : RideEvent()
    data class UiAction(val action: ControlAction) : RideEvent()
}

enum class RideStatus { WAITING, RUNNING, AUTO_PAUSED, PAUSED, FINISHED }

data class RideSnapshot(
    val status: RideStatus,
    val rideTimeS: Int,
    val workoutTimeS: Double,
    val powerW: Int,
    val power3sW: Int,
    val targetW: Int?,
    val hr: Int?,
    val hrZone: Int?,
    val cadence: Int?,
    val speedKmh: Double,
    val distanceM: Double,
    val altitudeM: Double?,
    val gradePercent: Double,
    val gear: Int,
    val gearCount: Int,
    val ergActive: Boolean,
    val ergEnabled: Boolean,
    val ergProtecting: Boolean,
    val intensityPercent: Int,
    val step: Step?,
    val stepElapsedS: Double,
    val stepRemainingS: Double,
    val nextStep: Step?,
    val workoutTotalS: Int,
    val workoutComplete: Boolean,
    val avgPower: Int,
    val maxPower: Int,
    val normalizedPower: Int?,
    val kj: Double,
    val tss: Double,
    val avgHr: Int?,
    val avgCadence: Int?,
    val lap: Int,
    val lapTimeS: Int,
    val powerZone: Int,
    val wattsPerKg: Double,
    val textMessage: String?,
    val cadenceTarget: Int?,
    val routeProgress: Double?,
    /** Heart-rate target of the current step (heart-rate ERG), in bpm. */
    val hrTargetBpm: Int? = null,
    /** True when a heart-rate step runs without heart-rate data. */
    val hrMissing: Boolean = false,
)

/**
 * The ride state machine. Pure logic, no Android. Feed it sensor values and call [tick]
 * regularly (e.g. every 250 ms); take trainer commands with [takeCommand] and events with
 * [drainEvents].
 */
class RideEngine(val config: RideConfig) {
    private val workout: ExpandedWorkout? = (config.mode as? RideMode.Structured)?.let { ExpandedWorkout.of(it.workout) }
    private val route: Route? = (config.mode as? RideMode.Free)?.route
    val gearbox = VirtualGearbox(config.gears)
    private val erg = ErgController()
    private val hrCtrl = HrController(
        minW = (config.ftp * 0.3).roundToInt().coerceAtLeast(config.powerRange.first),
        maxW = (config.ftp * 1.2).roundToInt().coerceAtMost(config.powerRange.last),
    )
    private var hrMissingFor = 0.0
    private var hrMissingWarned = false

    // --- sensor inputs
    private var power = 0
    private var cadence: Double? = null
    private var hr: Int? = null
    private var lastPowerAtMs = 0L
    private var lastCadenceAtMs = 0L
    private var lastHrAtMs = 0L
    private val powerWindow = ArrayDeque<Pair<Long, Int>>()

    // --- state
    var status = RideStatus.WAITING
        private set
    private var lastTickMs: Long? = null
    private var rideTime = 0.0
    private var workoutTime = 0.0
    private var secondAccumulator = 0.0
    private var idleFor = 0.0
    private var speed = 0.0
    private var distance = 0.0
    private var manualGrade = 0.0
    private var intensity = 0.0
    private var ergEnabled = true
    private var currentStepIndex = -1
    private var workoutComplete = false
    private var halfwayFired = false
    private var lastCountdown = -1
    private var lowCadFor = 0.0
    private var lowCadWarned = false
    private var rampFailFor = 0.0
    private var lap = 1
    private var lapStartTime = 0.0
    private var textMessage: String? = null
    private var textUntil = 0.0
    private var goalFired = false

    // --- stats
    private val samples = ArrayList<Sample>()
    private var sumPower = 0L
    private var maxPower = 0
    private var hrSum = 0L
    private var hrCount = 0
    private var cadSum = 0L
    private var cadCount = 0
    private var kj = 0.0
    private val npWindow = ArrayDeque<Int>()
    private var npWindowSum = 0.0
    private var np4Sum = 0.0
    private var np4Count = 0

    // --- outputs
    private val events = ArrayList<RideEvent>()
    private var desiredCommand: TrainerCommand? = null
    private var sentCommand: TrainerCommand? = null
    private var lastSimSentAt = -10.0
    private var lastPowerSetAt = -10.0

    // ------------------------------------------------------------------ inputs

    fun onPower(watts: Int, nowMs: Long) {
        power = max(0, watts)
        lastPowerAtMs = nowMs
        powerWindow.addLast(nowMs to power)
        while (powerWindow.isNotEmpty() && nowMs - powerWindow.first().first > 3000) powerWindow.removeFirst()
    }

    fun onCadence(rpm: Double, nowMs: Long) {
        cadence = rpm
        lastCadenceAtMs = nowMs
    }

    fun onHeartRate(bpm: Int, nowMs: Long) {
        hr = if (bpm > 0) bpm else null
        lastHrAtMs = nowMs
    }

    fun onAction(action: ControlAction) {
        when (action) {
            ControlAction.SHIFT_UP -> if (gearbox.shiftUp()) { events += RideEvent.Shifted(gearbox.gearNumber); updateCommand(force = true) }
            ControlAction.SHIFT_DOWN -> if (gearbox.shiftDown()) { events += RideEvent.Shifted(gearbox.gearNumber); updateCommand(force = true) }
            ControlAction.INTENSITY_UP -> setIntensity(intensity + config.intensityStep)
            ControlAction.INTENSITY_DOWN -> setIntensity(intensity - config.intensityStep)
            ControlAction.SKIP_INTERVAL -> skipStep()
            ControlAction.TOGGLE_ERG -> {
                ergEnabled = !ergEnabled
                events += RideEvent.ErgToggled(ergEnabled)
                updateCommand(force = true)
            }
            ControlAction.PAUSE_RESUME -> togglePause()
            ControlAction.LAP -> {
                lap++
                lapStartTime = rideTime
                events += RideEvent.Lap(lap)
            }
            ControlAction.GRADE_UP -> if (route == null) { manualGrade = (manualGrade + config.gradeStep).coerceAtMost(15.0); updateCommand(force = true) }
            ControlAction.GRADE_DOWN -> if (route == null) { manualGrade = (manualGrade - config.gradeStep).coerceAtLeast(-10.0); updateCommand(force = true) }
            ControlAction.NEXT_PAGE, ControlAction.PREV_PAGE, ControlAction.TOGGLE_GRAPH,
            ControlAction.MEDIA_PLAY_PAUSE -> events += RideEvent.UiAction(action)
            ControlAction.NONE -> {}
        }
    }

    fun setManualGrade(percent: Double) {
        manualGrade = percent.coerceIn(-10.0, 15.0)
        updateCommand(force = true)
    }

    private fun setIntensity(v: Double) {
        intensity = (v * 100).roundToInt().coerceIn(-50, 50) / 100.0
        events += RideEvent.IntensityChanged((intensity * 100).roundToInt())
        updateCommand(force = true)
    }

    fun togglePause() {
        if (status == RideStatus.PAUSED) {
            status = RideStatus.RUNNING
            events += RideEvent.Resumed
        } else if (status == RideStatus.RUNNING || status == RideStatus.AUTO_PAUSED || status == RideStatus.WAITING) {
            status = RideStatus.PAUSED
            events += RideEvent.Paused
        }
    }

    fun skipStep() {
        val ew = workout ?: return
        val s = ew.stepAt(workoutTime) ?: return
        workoutTime = s.endS.toDouble()
        lastCountdown = -1
        updateWorkoutStep()
        updateCommand(force = true)
    }

    fun finish() {
        status = RideStatus.FINISHED
    }

    /** Call after a trainer reconnect so the current target is re-sent. */
    fun invalidateTrainer() {
        sentCommand = null
        updateCommand(force = true)
    }

    // ------------------------------------------------------------------ outputs

    fun takeCommand(): TrainerCommand? {
        val d = desiredCommand ?: return null
        if (d == sentCommand) return null
        sentCommand = d
        if (d is TrainerCommand.Simulation) lastSimSentAt = rideTime
        return d
    }

    fun drainEvents(): List<RideEvent> {
        val out = ArrayList(events)
        events.clear()
        return out
    }

    fun samples(): List<Sample> = samples

    // ------------------------------------------------------------------ ticking

    private fun currentCadence(nowMs: Long): Double = if (nowMs - lastCadenceAtMs > 3000) 0.0 else cadence ?: 0.0
    private fun currentPower(nowMs: Long): Int = if (nowMs - lastPowerAtMs > 3000) 0 else power
    private fun currentHr(nowMs: Long): Int? = if (nowMs - lastHrAtMs > 5000) null else hr

    fun power3s(nowMs: Long): Int {
        val items = powerWindow.filter { nowMs - it.first <= 3000 }
        if (items.isEmpty() || nowMs - lastPowerAtMs > 3000) return 0
        return items.map { it.second }.average().roundToInt()
    }

    fun tick(nowMs: Long) {
        val last = lastTickMs
        lastTickMs = nowMs
        if (last == null) {
            updateWorkoutStep()
            updateCommand(force = true)
            return
        }
        val dt = ((nowMs - last) / 1000.0).coerceIn(0.0, 5.0)
        val cad = currentCadence(nowMs)
        val pw = currentPower(nowMs)
        val moving = cad > 0.5 || pw > 0

        when (status) {
            RideStatus.WAITING -> if (moving) {
                status = RideStatus.RUNNING
                events += RideEvent.Resumed
            }
            RideStatus.RUNNING -> {
                idleFor = if (moving) 0.0 else idleFor + dt
                if (idleFor >= config.autoPauseSeconds) {
                    status = RideStatus.AUTO_PAUSED
                    events += RideEvent.Paused
                }
            }
            RideStatus.AUTO_PAUSED -> if (moving) {
                status = RideStatus.RUNNING
                idleFor = 0.0
                events += RideEvent.Resumed
            }
            RideStatus.PAUSED, RideStatus.FINISHED -> {}
        }

        if (status != RideStatus.RUNNING) {
            // decay speed while paused
            speed = Physics.step(speed, 0.0, gradeNow(), dt, config.physics)
            updateCommand(force = false)
            return
        }

        rideTime += dt
        if (workout != null && !workoutComplete) workoutTime += dt
        speed = Physics.step(speed, pw.toDouble(), gradeNow(), dt, config.physics)
        distance += speed * dt
        kj += pw * dt / 1000.0

        updateWorkoutStep()
        checkLowCadence(cad, dt)
        updateHeartRateControl(nowMs, dt)
        checkRampTest(cad, dt)
        checkGoal()
        if (textMessage != null && rideTime > textUntil) textMessage = null

        // ERG output with protection
        updateCommand(force = false, cadence = cad, dt = dt)

        secondAccumulator += dt
        while (secondAccumulator >= 1.0) {
            secondAccumulator -= 1.0
            recordSample(nowMs, pw, cad)
        }
    }

    private fun gradeNow(): Double = when {
        route != null -> route.gradeAt(distance)
        workout != null -> 0.0
        else -> manualGrade
    }

    private fun updateWorkoutStep() {
        val ew = workout ?: return
        if (workoutComplete) return
        val s = ew.stepAt(workoutTime)
        if (s == null) {
            workoutComplete = true
            events += RideEvent.WorkoutComplete
            erg.reset()
            return
        }
        if (s.index != currentStepIndex) {
            val prevWasHr = workout.steps.getOrNull(currentStepIndex)?.hrTarget != null
            if (s.hrTarget != null) {
                hrCtrl.start(workout.toWatts(s.from ?: 0.6, config.ftp), keepCurrent = prevWasHr)
                hrMissingFor = 0.0
                hrMissingWarned = false
            }
            currentStepIndex = s.index
            lowCadFor = 0.0
            lowCadWarned = false
            events += RideEvent.StepStarted(s)
            for (t in s.texts) if (t.offsetS == 0) showText(t.message, t.durationS)
        }
        val inStep = workoutTime - s.startS
        for (t in s.texts) {
            if (t.offsetS > 0 && inStep >= t.offsetS && inStep - t.offsetS < 1.0 && textMessage != t.message) showText(t.message, t.durationS)
        }
        val remaining = s.endS - workoutTime
        val cd = kotlin.math.ceil(remaining).toInt()
        if (cd in 1..3 && cd != lastCountdown && s.index < ew.steps.lastIndex) {
            lastCountdown = cd
            events += RideEvent.Countdown(cd)
        }
        if (cd > 3) lastCountdown = -1
        if (!halfwayFired && workoutTime >= ew.totalDurationS / 2.0) {
            halfwayFired = true
            events += RideEvent.Halfway
        }
    }

    private fun showText(msg: String, durationS: Int) {
        textMessage = msg
        textUntil = rideTime + durationS
        events += RideEvent.Text(msg)
    }

    private fun checkLowCadence(cad: Double, dt: Double) {
        val target = currentStep()?.cadence ?: return
        if (cad > 0 && cad < target - 15) lowCadFor += dt else lowCadFor = 0.0
        if (lowCadFor >= 10 && !lowCadWarned) {
            lowCadWarned = true
            events += RideEvent.LowCadence
        }
    }

    private fun checkRampTest(cad: Double, dt: Double) {
        val w = (config.mode as? RideMode.Structured)?.workout ?: return
        if (w.ftpTest != FtpTestKind.RAMP && w.ftpTest != FtpTestKind.RAMP_SHORT) return
        val ew = workout ?: return
        val s = currentStep() ?: return
        val rampPhase = s.index >= 2 && s.index < ew.steps.lastIndex
        if (!rampPhase) return
        if (cad < FtpTests.RAMP_FAIL_CADENCE) rampFailFor += dt else rampFailFor = 0.0
        if (rampFailFor >= FtpTests.RAMP_FAIL_SECONDS) {
            rampFailFor = 0.0
            workoutTime = ew.steps.last().startS.toDouble()
            events += RideEvent.RampTestEnded
            updateWorkoutStep()
        }
    }

    private fun checkGoal() {
        val m = config.mode as? RideMode.Free ?: return
        if (goalFired) return
        val reached = (m.goalDistanceM != null && distance >= m.goalDistanceM) ||
            (m.goalTimeS != null && rideTime >= m.goalTimeS) ||
            (route != null && route.isFinished(distance))
        if (reached) {
            goalFired = true
            events += RideEvent.GoalReached
        }
    }

    private fun currentStep(): Step? = if (workout == null || workoutComplete) null else workout.stepAt(workoutTime)

    private fun ergActiveNow(): Boolean {
        val s = currentStep() ?: return false
        return ergEnabled && s.ergControlled
    }

    /** Target heart rate in bpm; intensity +5 % adds 3 bpm. */
    private fun hrTargetBpm(): Int? {
        val f = currentStep()?.hrTarget ?: return null
        return (f * config.maxHr + intensity * 60).roundToInt()
    }

    private fun updateHeartRateControl(nowMs: Long, dt: Double) {
        val target = hrTargetBpm() ?: return
        val h = currentHr(nowMs)
        hrCtrl.update(target, h, dt)
        if (h == null) hrMissingFor += dt else hrMissingFor = 0.0
        if (hrMissingFor >= 10 && !hrMissingWarned) {
            hrMissingWarned = true
            events += RideEvent.HeartRateMissing
        }
    }

    private fun workoutTargetW(): Int? {
        val ew = workout ?: return null
        val s = currentStep() ?: return null
        if (s.hrTarget != null) return hrCtrl.powerW.roundToInt()
        val v = s.targetAt(workoutTime - s.startS) ?: return null
        val base = ew.toWatts(v, config.ftp)
        return (base * (1 + intensity)).roundToInt()
    }

    private fun updateCommand(force: Boolean, cadence: Double = 90.0, dt: Double = 0.0) {
        if (ergActiveNow()) {
            val target = workoutTargetW() ?: return
            val pedalling = status == RideStatus.RUNNING
            val out = erg.update(target, cadence, dt, pedalling).coerceIn(config.powerRange.first, config.powerRange.last)
            val prev = (desiredCommand as? TrainerCommand.TargetPower)?.watts
            // Big changes (interval boundaries, protection) go out immediately; small ramp steps at most 1/s
            // so the trainer is not flooded with control-point writes.
            val diff = if (prev == null) Int.MAX_VALUE else abs(out - prev)
            if (force || diff >= 10 || (diff > 0 && rideTime - lastPowerSetAt >= 1.0)) {
                desiredCommand = TrainerCommand.TargetPower(out)
                lastPowerSetAt = rideTime
            }
        } else {
            val sim = gearbox.simulationFor(gradeNow(), config.physics)
            val prev = desiredCommand as? TrainerCommand.Simulation
            val changed = prev == null || abs(prev.params.gradePercent - sim.gradePercent) >= 0.1 ||
                abs(prev.params.crr - sim.crr) > 1e-5 || abs(prev.params.cw - sim.cw) > 1e-3
            if (force || (changed && rideTime - lastSimSentAt >= 1.0)) {
                desiredCommand = TrainerCommand.Simulation(sim)
            }
        }
    }

    private fun recordSample(nowMs: Long, pw: Int, cad: Double) {
        val h = currentHr(nowMs)
        val target = if (ergActiveNow()) (desiredCommand as? TrainerCommand.TargetPower)?.watts else workoutTargetW()
        samples += Sample(
            t = samples.size,
            power = pw,
            target = target,
            hr = h,
            cadence = cad.roundToInt(),
            speedKmh = speed * 3.6,
            distanceM = distance,
            altitudeM = route?.elevationAt(distance),
            grade = gradeNow(),
            gear = if (ergActiveNow()) null else gearbox.gearNumber,
            workoutT = if (workout != null && !workoutComplete) workoutTime.toInt() else null,
        )
        sumPower += pw
        if (pw > maxPower) maxPower = pw
        if (h != null) { hrSum += h; hrCount++ }
        if (cad > 0) { cadSum += cad.roundToInt(); cadCount++ }
        npWindow.addLast(pw)
        npWindowSum += pw
        if (npWindow.size > 30) npWindowSum -= npWindow.removeFirst()
        if (npWindow.size == 30) {
            np4Sum += (npWindowSum / 30).pow(4)
            np4Count++
        }
    }

    // ------------------------------------------------------------------ snapshot

    fun snapshot(nowMs: Long): RideSnapshot {
        val ew = workout
        val step = currentStep()
        val next = if (ew != null && step != null) ew.steps.getOrNull(step.index + 1) else null
        val pw = currentPower(nowMs)
        val h = currentHr(nowMs)
        val np = if (np4Count > 0) (np4Sum / np4Count).pow(0.25) else null
        val n = samples.size
        val tss = if (np != null && config.ftp > 0) n * np * (np / config.ftp) / (config.ftp * 3600.0) * 100 else 0.0
        val target = when {
            ergActiveNow() -> (desiredCommand as? TrainerCommand.TargetPower)?.watts
            else -> workoutTargetW()
        }
        val cad = currentCadence(nowMs)
        return RideSnapshot(
            status = status,
            rideTimeS = rideTime.toInt(),
            workoutTimeS = workoutTime,
            powerW = pw,
            power3sW = power3s(nowMs),
            targetW = target,
            hr = h,
            hrZone = h?.let { HeartRateZones.zoneOf(it, config.maxHr, config.restHr, config.hrModel) },
            cadence = if (nowMs - lastCadenceAtMs > 3000 && cadence == null) null else cad.roundToInt(),
            speedKmh = speed * 3.6,
            distanceM = distance,
            altitudeM = route?.elevationAt(distance),
            gradePercent = gradeNow(),
            gear = gearbox.gearNumber,
            gearCount = gearbox.count,
            ergActive = ergActiveNow(),
            ergEnabled = ergEnabled,
            ergProtecting = erg.isProtecting && ergActiveNow(),
            intensityPercent = (intensity * 100).roundToInt(),
            step = step,
            stepElapsedS = if (step != null) workoutTime - step.startS else 0.0,
            stepRemainingS = if (step != null) step.endS - workoutTime else 0.0,
            nextStep = next,
            workoutTotalS = ew?.totalDurationS ?: 0,
            workoutComplete = workoutComplete,
            avgPower = if (n > 0) (sumPower / n).toInt() else 0,
            maxPower = maxPower,
            normalizedPower = np?.roundToInt(),
            kj = kj,
            tss = tss,
            avgHr = if (hrCount > 0) (hrSum / hrCount).toInt() else null,
            avgCadence = if (cadCount > 0) (cadSum / cadCount).toInt() else null,
            lap = if (route != null && route.loop) route.lapOf(distance) else lap,
            lapTimeS = (rideTime - lapStartTime).toInt(),
            powerZone = PowerZones.zoneOf(power3s(nowMs).toDouble(), config.ftp),
            wattsPerKg = if (config.physics.riderKg > 0) power3s(nowMs) / config.physics.riderKg else 0.0,
            textMessage = textMessage,
            cadenceTarget = step?.cadence,
            routeProgress = route?.let { if (it.loop) (distance % it.lengthM) / it.lengthM else (distance / it.lengthM).coerceAtMost(1.0) },
            hrTargetBpm = hrTargetBpm(),
            hrMissing = hrTargetBpm() != null && hrCtrl.hrMissing,
        )
    }

}
