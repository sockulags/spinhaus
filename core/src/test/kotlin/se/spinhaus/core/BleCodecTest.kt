package se.spinhaus.core

import se.spinhaus.core.ble.ButtonEdgeTracker
import se.spinhaus.core.ble.CrankCadenceCalculator
import se.spinhaus.core.ble.CyclingPowerParser
import se.spinhaus.core.ble.FtmsControl
import se.spinhaus.core.ble.FtmsParser
import se.spinhaus.core.ble.HeartRateParser
import se.spinhaus.core.ble.Protobuf
import se.spinhaus.core.ble.RideButton
import se.spinhaus.core.ble.ZwiftRideProtocol
import se.spinhaus.core.util.hexToBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BleCodecTest {
    @Test
    fun indoorBikeDataWithSpeedCadencePower() {
        // flags 0x0044: speed present (bit0=0), cadence (bit2), power (bit6)
        // speed 3000 (30.00 km/h) = b8 0b, cadence 180 (90 rpm) = b4 00, power 250 = fa 00
        val d = FtmsParser.parseIndoorBikeData(hexToBytes("44 00 b8 0b b4 00 fa 00"))!!
        assertEquals(30.0, d.speedKmh)
        assertEquals(90.0, d.cadenceRpm)
        assertEquals(250, d.powerW)
        assertNull(d.heartRateBpm)
    }

    @Test
    fun indoorBikeDataMoreDataFlagMeansNoSpeed() {
        // flags 0x0041: more data (no speed), power present
        val d = FtmsParser.parseIndoorBikeData(hexToBytes("41 00 64 00"))!!
        assertNull(d.speedKmh)
        assertEquals(100, d.powerW)
    }

    @Test
    fun indoorBikeDataAllFields() {
        // flags: avg speed(1), cadence(2), distance(4), resistance(5), power(6), energy(8), hr(9), elapsed(11)
        val flags = (1 shl 1) or (1 shl 2) or (1 shl 4) or (1 shl 5) or (1 shl 6) or (1 shl 8) or (1 shl 9) or (1 shl 11)
        val hex = "%02x %02x".format(flags and 0xFF, flags shr 8) +
            " e8 03" + // speed 10.00
            " d0 07" + // avg speed 20.00
            " a0 00" + // cadence 80
            " 10 27 00" + // distance 10000
            " 05 00" + // resistance 5
            " c8 00" + // power 200
            " 2c 01 00 00 00" + // energy 300
            " 8c" + // hr 140
            " 3c 00" // elapsed 60
        val d = FtmsParser.parseIndoorBikeData(hexToBytes(hex))!!
        assertEquals(10.0, d.speedKmh)
        assertEquals(80.0, d.cadenceRpm)
        assertEquals(10000, d.totalDistanceM)
        assertEquals(200, d.powerW)
        assertEquals(300, d.totalEnergyKcal)
        assertEquals(140, d.heartRateBpm)
        assertEquals(60, d.elapsedTimeS)
    }

    @Test
    fun truncatedIndoorBikeDataReturnsNull() {
        assertNull(FtmsParser.parseIndoorBikeData(hexToBytes("44 00 b8")))
    }

    @Test
    fun controlPointEncoding() {
        assertEquals("05 c8 00", FtmsControl.setTargetPower(200).joinToString(" ") { "%02x".format(it) })
        // grade 5% = 500 = f4 01, crr 0.004 = 40 = 28, cw 0.51 = 51 = 33
        assertEquals("11 00 00 f4 01 28 33", FtmsControl.setSimulation(5.0, 0.004, 0.51).joinToString(" ") { "%02x".format(it) })
        // negative grade -2.5 % = -250 = 06 ff
        assertEquals("11 00 00 06 ff 28 33", FtmsControl.setSimulation(-2.5, 0.004, 0.51).joinToString(" ") { "%02x".format(it) })
        val resp = FtmsParser.parseControlPointResponse(hexToBytes("80 05 01"))!!
        assertTrue(resp.success)
        assertEquals(FtmsControl.OP_SET_TARGET_POWER, resp.requestOpCode)
    }

    @Test
    fun featuresParsing() {
        // target bits: power(3), resistance(2), simulation(13)
        val targets = (1 shl 3) or (1 shl 2) or (1 shl 13)
        val bytes = byteArrayOf(0x02, 0x40, 0, 0, (targets and 0xFF).toByte(), (targets shr 8).toByte(), 0, 0)
        val f = FtmsParser.parseFeatures(bytes)!!
        assertTrue(f.supportsPowerTarget)
        assertTrue(f.supportsSimulation)
        assertTrue(f.reportsCadence)
        assertTrue(f.reportsPower)
    }

    @Test
    fun heartRate8and16bit() {
        assertEquals(72, HeartRateParser.parse(hexToBytes("00 48"))!!.bpm)
        val m = HeartRateParser.parse(hexToBytes("11 2c 01 00 04"))!! // 16-bit 300, RR 1024 → 1000 ms
        assertEquals(300, m.bpm)
        assertEquals(listOf(1000), m.rrIntervalsMs)
    }

    @Test
    fun cyclingPowerAndCadence() {
        // flags 0x20 crank data, power 150, revs 10, time 1024
        val m = CyclingPowerParser.parse(hexToBytes("20 00 96 00 0a 00 00 04"))!!
        assertEquals(150, m.powerW)
        val calc = CrankCadenceCalculator()
        calc.update(10, 1024, 0)
        val cad = calc.update(11, 1024 + 683, 1000) // ~0.667 s per rev → 90 rpm
        assertTrue(cad in 89.0..91.0, "cadence $cad")
    }

    private fun varint(v: Long): List<Byte> {
        val out = ArrayList<Byte>()
        var x = v
        while (true) {
            if (x and 0x7FL.inv() == 0L) { out.add(x.toByte()); break }
            out.add(((x and 0x7F) or 0x80).toByte())
            x = x ushr 7
        }
        return out
    }

    private fun keypad(buttonMap: Long, analog: List<Pair<Int, Int>> = emptyList()): ByteArray {
        val b = ArrayList<Byte>()
        b.add(0x23)
        b.add(0x08); b.addAll(varint(buttonMap))
        for ((loc, value) in analog) {
            val zz = ((value shl 1) xor (value shr 31)).toLong() and 0xFFFFFFFFL
            val sub = ArrayList<Byte>()
            sub.add(0x08); sub.addAll(varint(loc.toLong()))
            sub.add(0x10); sub.addAll(varint(zz))
            b.add(0x1a); b.add(sub.size.toByte()); b.addAll(sub)
        }
        return b.toByteArray()
    }

    @Test
    fun zwiftRideNoButtons() {
        val m = ZwiftRideProtocol.decode(keypad(0xFFFFFFFFL))
        assertIs<ZwiftRideProtocol.Message.Buttons>(m)
        assertTrue(m.pressed.isEmpty())
    }

    @Test
    fun zwiftRideShiftUpRightAndA() {
        val map = 0xFFFFFFFFL and (RideButton.SHIFT_UP_RIGHT.mask.toLong().inv()) and (RideButton.A.mask.toLong().inv())
        val m = ZwiftRideProtocol.decode(keypad(map)) as ZwiftRideProtocol.Message.Buttons
        assertEquals(setOf(RideButton.SHIFT_UP_RIGHT, RideButton.A), m.pressed)
    }

    @Test
    fun zwiftRideAnalogPaddle() {
        val m = ZwiftRideProtocol.decode(keypad(0xFFFFFFFFL, listOf(0 to -80, 1 to 10))) as ZwiftRideProtocol.Message.Buttons
        assertEquals(setOf(RideButton.PADDLE_LEFT), m.pressed)
        assertEquals(-80, m.analog[0])
        assertEquals(10, m.analog[1])
    }

    @Test
    fun zwiftRideOnAndBattery() {
        assertIs<ZwiftRideProtocol.Message.RideOnResponse>(ZwiftRideProtocol.decode("RideOn".toByteArray() + byteArrayOf(1, 3)))
        val bat = ZwiftRideProtocol.decode(byteArrayOf(0x19, 0x08, 0x00, 0x10, 0x55))
        assertEquals(ZwiftRideProtocol.Message.Battery(0x55), bat)
        assertIs<ZwiftRideProtocol.Message.Empty>(ZwiftRideProtocol.decode(byteArrayOf(0x15)))
    }

    @Test
    fun garbageDoesNotCrash() {
        assertNotNull(ZwiftRideProtocol.decode(byteArrayOf(0x23, 0xFF.toByte())))
    }

    @Test
    fun advertisementDetection() {
        assertTrue(ZwiftRideProtocol.isZwiftController(null, mapOf(0x094A to byteArrayOf(0x07, 0x01)), emptyList()))
        assertTrue(ZwiftRideProtocol.isZwiftController("Zwift Ride", emptyMap(), emptyList()))
        assertTrue(ZwiftRideProtocol.isZwiftController(null, emptyMap(), listOf("0000fc82-0000-1000-8000-00805f9b34fb")))
        assertTrue(!ZwiftRideProtocol.isZwiftController("KICKR CORE 1234", emptyMap(), emptyList()))
    }

    @Test
    fun zigZag() {
        assertEquals(-1, Protobuf.zigZag(1))
        assertEquals(1, Protobuf.zigZag(2))
        assertEquals(-100, Protobuf.zigZag(199))
    }

    @Test
    fun edgeTracker() {
        val t = ButtonEdgeTracker()
        assertEquals(listOf<ButtonEdgeTracker.Edge>(ButtonEdgeTracker.Edge.Pressed(RideButton.A)), t.update(setOf(RideButton.A), 0))
        assertTrue(t.update(setOf(RideButton.A), 100).isEmpty())
        assertEquals(listOf<ButtonEdgeTracker.Edge>(ButtonEdgeTracker.Edge.Released(RideButton.A, 500)), t.update(emptySet(), 500))
    }
}
