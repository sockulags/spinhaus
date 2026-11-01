package se.spinhaus.core

import se.spinhaus.core.training.ActivityCalculator
import se.spinhaus.core.training.FitnessModel
import se.spinhaus.core.training.FtpEstimator
import se.spinhaus.core.training.HeartRateZones
import se.spinhaus.core.training.HrZoneModel
import se.spinhaus.core.training.PowerAnalysis
import se.spinhaus.core.training.PowerZones
import se.spinhaus.core.training.Sample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrainingTest {
    @Test
    fun npOfConstantPowerEqualsPower() {
        val p = IntArray(3600) { 200 }
        assertEquals(200.0, PowerAnalysis.normalizedPower(p)!!, 1e-6)
        assertEquals(100.0, PowerAnalysis.tss(3600, 200.0, 200), 1e-6)
        assertNull(PowerAnalysis.normalizedPower(IntArray(10) { 100 }))
    }

    @Test
    fun npHigherThanAverageForVariablePower() {
        val p = IntArray(1200) { if ((it / 60) % 2 == 0) 300 else 100 }
        val np = PowerAnalysis.normalizedPower(p)!!
        assertTrue(np > 200 && np < 300)
    }

    @Test
    fun bests() {
        val p = IntArray(600) { if (it in 100 until 160) 400 else 100 }
        assertEquals(400, PowerAnalysis.bests(p)[60])
        assertEquals(400, PowerAnalysis.bests(p)[5])
        assertEquals(null, PowerAnalysis.bests(p)[1200])
    }

    @Test
    fun zones() {
        assertEquals(1, PowerZones.zoneOf(100.0, 250))
        assertEquals(4, PowerZones.zoneOf(250.0, 250))
        assertEquals(7, PowerZones.zoneOf(400.0, 250))
        assertEquals(5, HeartRateZones.zoneOf(185, 190, 60, HrZoneModel.PERCENT_MAX))
        assertEquals(1, HeartRateZones.zoneOf(100, 190, 60, HrZoneModel.KARVONEN))
    }

    @Test
    fun summary() {
        val samples = (0 until 1800).map { Sample(t = it, power = 200, hr = 140, cadence = 90, distanceM = it * 8.0, altitudeM = (it % 100).toDouble()) }
        val s = ActivityCalculator.summarize(samples, 250, 190, 60, HrZoneModel.PERCENT_MAX)
        assertEquals(200, s.avgPower)
        assertEquals(200, s.normalizedPower)
        assertEquals(360, s.kj)
        assertEquals(140, s.avgHr)
        assertEquals(1800, s.powerZoneSeconds[2])
        assertTrue(s.elevationGainM > 1000)
    }

    @Test
    fun fitnessModel() {
        val days = FitnessModel.compute((0L until 42L).associateWith { 100.0 }, 0, 41)
        assertTrue(days.last().ctl > 60)
        assertTrue(days.last().atl > days.last().ctl)
    }

    @Test
    fun ftpSuggestion() {
        assertEquals(266, FtpEstimator.suggest(280, 250))
        assertNull(FtpEstimator.suggest(260, 250))
    }
}
