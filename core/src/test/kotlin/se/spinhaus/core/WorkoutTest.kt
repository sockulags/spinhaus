package se.spinhaus.core

import se.spinhaus.core.training.Sample
import se.spinhaus.core.workout.ErgMrcParser
import se.spinhaus.core.workout.ExpandedWorkout
import se.spinhaus.core.workout.FtpTests
import se.spinhaus.core.workout.Segment
import se.spinhaus.core.workout.StepKind
import se.spinhaus.core.workout.Workout
import se.spinhaus.core.workout.WorkoutImporter
import se.spinhaus.core.workout.WorkoutJson
import se.spinhaus.core.workout.WorkoutLibrary
import se.spinhaus.core.workout.WorkoutStats
import se.spinhaus.core.workout.ZwoParser
import se.spinhaus.core.workout.ZwoWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkoutTest {
    @Test
    fun expansionOfIntervals() {
        val w = Workout("t", "t", segments = listOf(
            Segment.Ramp(60, 0.5, 0.7),
            Segment.Intervals(3, 30, 1.2, 30, 0.5),
            Segment.Free(60),
        ))
        val e = ExpandedWorkout.of(w)
        assertEquals(1 + 6 + 1, e.steps.size)
        assertEquals(60 + 180 + 60, e.totalDurationS)
        assertEquals(0.6, e.stepAt(30.0)!!.targetAt(30.0)!!, 1e-9)
        assertEquals(240, e.targetWattsAt(60.0, 200))
        assertEquals(100, e.targetWattsAt(95.0, 200))
        assertEquals(StepKind.FREE, e.stepAt(250.0)!!.kind)
        assertEquals(null, e.stepAt(300.0))
        val third = e.stepAt(185.0)!!
        assertEquals(3, third.repeat)
    }

    @Test
    fun libraryIsValid() {
        val ids = HashSet<String>()
        assertTrue(WorkoutLibrary.all.size >= 40, "library size ${WorkoutLibrary.all.size}")
        for (w in WorkoutLibrary.all) {
            assertTrue(ids.add(w.id), "duplicate id ${w.id}")
            assertTrue(w.totalDurationS in 10 * 60..3 * 3600, "${w.id} duration ${w.totalDurationS}")
            assertTrue(w.localizedNames.containsKey("sv"), "${w.id} lacks Swedish name")
            // JSON round-trip
            assertEquals(w, WorkoutJson.decode(WorkoutJson.encode(w)))
        }
    }

    @Test
    fun estimateIsReasonable() {
        val e = WorkoutStats.estimate(WorkoutLibrary.byId("builtin-thr-2x20")!!)
        assertTrue(e.tss in 50.0..90.0, "tss ${e.tss}")
        assertTrue(e.intensityFactor in 0.75..0.95, "if ${e.intensityFactor}")
    }

    private val zwo = """
        <workout_file>
          <author>Me</author>
          <name>My Test</name>
          <description>Desc</description>
          <sportType>bike</sportType>
          <tags><tag name="FTP"/></tags>
          <workout>
            <Warmup Duration="600" PowerLow="0.25" PowerHigh="0.75"/>
            <SteadyState Duration="300" Power="0.88" Cadence="90">
              <textevent timeoffset="10" message="Hello"/>
            </SteadyState>
            <IntervalsT Repeat="4" OnDuration="60" OffDuration="120" OnPower="1.2" OffPower="0.5" Cadence="100" CadenceResting="85"/>
            <FreeRide Duration="120" FlatRoad="1"/>
            <MaxEffort Duration="20"/>
            <Unknown Duration="5"/>
            <Cooldown Duration="300" PowerLow="0.4" PowerHigh="0.7"/>
          </workout>
        </workout_file>
    """.trimIndent()

    @Test
    fun parseZwo() {
        val r = ZwoParser.parse(zwo)
        val w = r.workout
        assertEquals("My Test", w.name)
        assertEquals(6, w.segments.size)
        assertEquals(1, r.warnings.size)
        val steady = w.segments[1] as Segment.Steady
        assertEquals(90, steady.cadence)
        assertEquals("Hello", steady.texts.single().message)
        val cool = w.segments.last() as Segment.Ramp
        assertEquals(0.7, cool.from) // cooldown descends
        assertEquals(listOf("FTP"), w.tags)
        // write and re-read
        val again = ZwoParser.parse(ZwoWriter.write(w)).workout
        assertEquals(w.segments, again.segments)
    }

    @Test
    fun parseErgAndMrc() {
        val erg = """
            [COURSE HEADER]
            VERSION = 2
            UNITS = ENGLISH
            DESCRIPTION = Test
            FILE NAME = test.erg
            MINUTES WATTS
            [END COURSE HEADER]
            [COURSE DATA]
            0.00	100
            5.00	200
            5.00	250
            10.00	250
            10.00	100
            15.00	100
            [END COURSE DATA]
        """.trimIndent()
        val w = ErgMrcParser.parse(erg, "test.erg").workout
        assertTrue(w.absolutePower)
        assertEquals(3, w.segments.size)
        assertEquals(Segment.Ramp(300, 100.0, 200.0), w.segments[0])
        assertEquals(Segment.Steady(300, 250.0), w.segments[1])
        val mrc = erg.replace("MINUTES WATTS", "MINUTES PERCENT")
        val m = WorkoutImporter.import("test.mrc", mrc).workout
        assertTrue(!m.absolutePower)
        assertEquals(Segment.Steady(300, 2.5), m.segments[1])
    }

    @Test
    fun ftpFromRampTest() {
        // best minute 300 W → FTP 225
        val samples = (0 until 600).map { Sample(t = it, power = if (it in 400 until 460) 300 else 150, workoutT = it) }
        assertEquals(225, FtpTests.calculateFtp(FtpTests.ramp, samples))
    }

    @Test
    fun ftpFrom20MinTest() {
        val ew = ExpandedWorkout.of(FtpTests.twentyMin)
        val step = ew.steps.last { it.kind == StepKind.MAX && it.durationS == 1200 }
        val samples = (0 until ew.totalDurationS).map { Sample(t = it, power = if (it in step.startS until step.endS) 260 else 120, workoutT = it) }
        assertEquals(247, FtpTests.calculateFtp(FtpTests.twentyMin, samples))
    }

    @Test
    fun ftpFrom2x8() {
        val ew = ExpandedWorkout.of(FtpTests.twoByEight)
        val maxSteps = ew.steps.filter { it.kind == StepKind.MAX }
        val samples = (0 until ew.totalDurationS).map { t ->
            val p = when {
                t in maxSteps[0].startS until maxSteps[0].endS -> 280
                t in maxSteps[1].startS until maxSteps[1].endS -> 260
                else -> 120
            }
            Sample(t = t, power = p, workoutT = t)
        }
        assertEquals(243, FtpTests.calculateFtp(FtpTests.twoByEight, samples))
    }
}
