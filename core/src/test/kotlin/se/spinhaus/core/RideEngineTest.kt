package se.spinhaus.core

import se.spinhaus.core.ble.ButtonEdgeTracker
import se.spinhaus.core.ble.RideButton
import se.spinhaus.core.ride.ControlAction
import se.spinhaus.core.ride.ControlInterpreter
import se.spinhaus.core.ride.ControllerMapping
import se.spinhaus.core.ride.GearTable
import se.spinhaus.core.ride.Physics
import se.spinhaus.core.ride.PhysicsParams
import se.spinhaus.core.ride.RideConfig
import se.spinhaus.core.ride.RideEngine
import se.spinhaus.core.ride.RideEvent
import se.spinhaus.core.ride.RideMode
import se.spinhaus.core.ride.RideStatus
import se.spinhaus.core.ride.Routes
import se.spinhaus.core.ride.TrainerCommand
import se.spinhaus.core.ride.VirtualGearbox
import se.spinhaus.core.workout.FtpTests
import se.spinhaus.core.workout.Segment
import se.spinhaus.core.workout.Workout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RideEngineTest {
    private class Sim(val engine: RideEngine) {
        var now = 0L
        val commands = ArrayList<TrainerCommand>()
        val events = ArrayList<RideEvent>()
        fun ride(seconds: Int, power: Int = 200, cadence: Double = 90.0) {
            repeat(seconds * 4) {
                now += 250
                engine.onPower(power, now)
                engine.onCadence(cadence, now)
                engine.tick(now)
                engine.takeCommand()?.let { commands += it }
                events += engine.drainEvents()
            }
        }
    }

    private val simple = Workout("w", "w", segments = listOf(
        Segment.Steady(60, 0.5),
        Segment.Steady(60, 1.0),
        Segment.Free(30),
    ))

    @Test
    fun structuredWorkoutSendsErgTargets() {
        val sim = Sim(RideEngine(RideConfig(RideMode.Structured(simple), ftp = 200)))
        sim.engine.tick(0)
        sim.engine.takeCommand()?.let { sim.commands += it }
        assertEquals(TrainerCommand.TargetPower(100), sim.commands.first())
        sim.ride(70)
        assertTrue(TrainerCommand.TargetPower(200) in sim.commands)
        assertTrue(sim.events.any { it is RideEvent.Countdown })
        sim.ride(60)
        assertEquals(1, sim.events.count { it is RideEvent.Halfway })
        // free segment → simulation
        assertIs<TrainerCommand.Simulation>(sim.commands.last())
        sim.ride(30)
        assertTrue(sim.events.any { it is RideEvent.WorkoutComplete })
        val snap = sim.engine.snapshot(sim.now)
        assertTrue(snap.workoutComplete)
        assertTrue(sim.engine.samples().size in 158..162, "samples ${sim.engine.samples().size}")
    }

    @Test
    fun intensityAndSkip() {
        val sim = Sim(RideEngine(RideConfig(RideMode.Structured(simple), ftp = 200)))
        sim.ride(5)
        sim.engine.onAction(ControlAction.INTENSITY_UP)
        assertEquals(TrainerCommand.TargetPower(105), sim.engine.takeCommand())
        sim.engine.onAction(ControlAction.SKIP_INTERVAL)
        assertEquals(TrainerCommand.TargetPower(210), sim.engine.takeCommand())
    }

    @Test
    fun ergToggleSwitchesToSim() {
        val sim = Sim(RideEngine(RideConfig(RideMode.Structured(simple), ftp = 200)))
        sim.ride(5)
        sim.engine.onAction(ControlAction.TOGGLE_ERG)
        assertIs<TrainerCommand.Simulation>(sim.engine.takeCommand())
        sim.engine.onAction(ControlAction.TOGGLE_ERG)
        assertIs<TrainerCommand.TargetPower>(sim.engine.takeCommand())
    }

    @Test
    fun autoPauseAndResume() {
        val sim = Sim(RideEngine(RideConfig(RideMode.Structured(simple), ftp = 200)))
        sim.ride(10)
        val t1 = sim.engine.snapshot(sim.now).rideTimeS
        sim.ride(10, power = 0, cadence = 0.0)
        assertEquals(RideStatus.AUTO_PAUSED, sim.engine.status)
        val t2 = sim.engine.snapshot(sim.now).rideTimeS
        assertTrue(t2 - t1 <= 4)
        sim.ride(2)
        assertEquals(RideStatus.RUNNING, sim.engine.status)
    }

    @Test
    fun lowCadenceProtectionLowersTarget() {
        val sim = Sim(RideEngine(RideConfig(RideMode.Structured(simple), ftp = 200)))
        sim.ride(65) // in the 200 W step
        sim.ride(5, power = 150, cadence = 30.0)
        assertTrue(sim.engine.snapshot(sim.now).ergProtecting)
        assertEquals(TrainerCommand.TargetPower(100), sim.commands.last())
        sim.ride(8, cadence = 85.0)
        assertEquals(TrainerCommand.TargetPower(200), sim.commands.last())
    }

    @Test
    fun freeRideShiftingChangesSimulation() {
        val sim = Sim(RideEngine(RideConfig(RideMode.Free(null), ftp = 200)))
        sim.ride(3)
        val before = sim.commands.last() as TrainerCommand.Simulation
        sim.engine.onAction(ControlAction.SHIFT_UP)
        val after = sim.engine.takeCommand() as TrainerCommand.Simulation
        assertTrue(after.params.cw > before.params.cw)
        sim.engine.onAction(ControlAction.GRADE_UP)
        val graded = sim.engine.takeCommand() as TrainerCommand.Simulation
        assertTrue(graded.params.gradePercent > 0)
        sim.ride(60, power = 200)
        val snap = sim.engine.snapshot(sim.now)
        assertTrue(snap.distanceM > 200)
    }

    @Test
    fun routeGradeIsApplied() {
        val sim = Sim(RideEngine(RideConfig(RideMode.Free(Routes.longClimb), ftp = 250)))
        sim.ride(600, power = 300)
        val snap = sim.engine.snapshot(sim.now)
        assertTrue(snap.gradePercent > 0.5, "grade ${snap.gradePercent}")
        assertTrue(snap.altitudeM!! > 25)
    }

    @Test
    fun rampTestEndsOnLowCadence() {
        val sim = Sim(RideEngine(RideConfig(RideMode.Structured(FtpTests.ramp), ftp = 200)))
        sim.ride(7 * 60 + 300) // warmup + 5 ramp minutes
        sim.ride(8, power = 100, cadence = 30.0)
        assertTrue(sim.events.any { it is RideEvent.RampTestEnded })
        val samples = sim.engine.samples()
        assertTrue(FtpTests.calculateFtp(FtpTests.ramp, samples)!! > 0)
    }

    @Test
    fun physicsSanity() {
        val p = PhysicsParams(riderKg = 75.0)
        val v = Physics.steadySpeed(200.0, 0.0, p) * 3.6
        assertTrue(v in 30.0..38.0, "flat speed $v")
        val climb = Physics.steadySpeed(250.0, 7.0, p) * 3.6
        assertTrue(climb in 9.0..14.0, "climb speed $climb")
    }

    @Test
    fun gearboxScaling() {
        val g = VirtualGearbox(GearTable())
        val neutral = g.simulationFor(2.0, PhysicsParams())
        g.shiftUp(); g.shiftUp()
        val harder = g.simulationFor(2.0, PhysicsParams())
        assertTrue(harder.gradePercent > neutral.gradePercent)
        repeat(30) { g.shiftDown() }
        assertEquals(1, g.gearNumber)
    }

    @Test
    fun controlInterpreterShortAndLong() {
        val tracker = ButtonEdgeTracker()
        val ci = ControlInterpreter(800)
        val map = ControllerMapping.defaultErg
        val bind = { b: RideButton -> map[b] ?: se.spinhaus.core.ride.ButtonBinding() }
        // shift fires immediately
        assertEquals(listOf(ControlAction.INTENSITY_UP), ci.onEdges(tracker.update(setOf(RideButton.SHIFT_UP_RIGHT), 0), bind))
        assertEquals(emptyList(), ci.onEdges(tracker.update(emptySet(), 100), bind))
        // A short → LAP on release
        assertEquals(emptyList(), ci.onEdges(tracker.update(setOf(RideButton.A), 1000), bind))
        assertEquals(listOf(ControlAction.LAP), ci.onEdges(tracker.update(emptySet(), 1200), bind))
        // A long → SKIP once, nothing on release
        ci.onEdges(tracker.update(setOf(RideButton.A), 2000), bind)
        assertEquals(listOf(ControlAction.SKIP_INTERVAL), ci.onHeld(tracker.heldButtons(2900), bind))
        assertEquals(emptyList(), ci.onHeld(tracker.heldButtons(3000), bind))
        assertEquals(emptyList(), ci.onEdges(tracker.update(emptySet(), 3100), bind))
    }
}
