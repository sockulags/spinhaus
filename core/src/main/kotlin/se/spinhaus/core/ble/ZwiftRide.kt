package se.spinhaus.core.ble

/**
 * Zwift Ride controller protocol, based on community reverse engineering
 * (BikeControl/SwiftControl, MAKINOLO, RideToWoosh).
 *
 * Flow: connect → find service 0xFC82 (or 00000001-19CA-…) → subscribe ASYNC (notify) and
 * SYNC_TX (indicate) → write "RideOn" to SYNC_RX (write without response) → receive
 * 0x23 keypad status messages (protobuf) on ASYNC.
 *
 * Ride V2 controllers (firmware ≥ 1.3.0) hide the custom service until they have been
 * unlocked by the Zwift app (valid ~24 h). We detect that and tell the user.
 */
object ZwiftRideProtocol {
    const val MANUFACTURER_ID = 0x094A // Zwift, Inc.
    const val TYPE_RIDE_RIGHT = 0x07
    const val TYPE_RIDE_LEFT = 0x08
    const val TYPE_PLAY_RIGHT = 0x02
    const val TYPE_PLAY_LEFT = 0x03
    const val TYPE_CLICK = 0x09
    const val TYPE_CLICK_V2_RIGHT = 0x0A
    const val TYPE_CLICK_V2_LEFT = 0x0B

    val RIDE_ON: ByteArray = "RideOn".toByteArray(Charsets.US_ASCII)

    const val MSG_CONTROLLER_NOTIFICATION = 0x23
    const val MSG_EMPTY = 0x15
    const val MSG_BATTERY = 0x19
    const val MSG_LOG = 0x2A
    const val MSG_LOST_CONTROL = 0xFE

    private val VIBRATE_PATTERN = byteArrayOf(0x12, 0x12, 0x08, 0x0A, 0x06, 0x08, 0x02, 0x10, 0x00, 0x18, 0x20)

    fun vibrateCommand(): ByteArray = VIBRATE_PATTERN.copyOf()

    /** Returns true if the advertisement looks like a Zwift controller. */
    fun isZwiftController(name: String?, manufacturerData: Map<Int, ByteArray>, serviceUuids: List<String>): Boolean {
        val md = manufacturerData[MANUFACTURER_ID]
        if (md != null && md.isNotEmpty() && deviceTypeOf(md) != null) return true
        if (serviceUuids.any { it.equals(GattUuids.ZWIFT_RIDE_SERVICE, true) }) return true
        val n = name ?: return false
        return n.contains("Zwift Ride", true) || n.contains("Zwift SF2", true) ||
            n.contains("Zwift Play", true) || n.contains("Zwift Click", true)
    }

    fun deviceTypeOf(manufacturerPayload: ByteArray): ControllerType? = when (manufacturerPayload[0].toInt() and 0xFF) {
        TYPE_RIDE_RIGHT -> ControllerType.RIDE_RIGHT
        TYPE_RIDE_LEFT -> ControllerType.RIDE_LEFT
        TYPE_PLAY_RIGHT -> ControllerType.PLAY_RIGHT
        TYPE_PLAY_LEFT -> ControllerType.PLAY_LEFT
        TYPE_CLICK -> ControllerType.CLICK
        TYPE_CLICK_V2_RIGHT -> ControllerType.CLICK_V2_RIGHT
        TYPE_CLICK_V2_LEFT -> ControllerType.CLICK_V2_LEFT
        else -> null
    }

    enum class ControllerType { RIDE_RIGHT, RIDE_LEFT, PLAY_RIGHT, PLAY_LEFT, CLICK, CLICK_V2_RIGHT, CLICK_V2_LEFT }

    sealed class Message {
        data class Buttons(val pressed: Set<RideButton>, val analog: Map<Int, Int>) : Message()
        data class Battery(val percent: Int) : Message()
        data object RideOnResponse : Message()
        data object Empty : Message()
        data object LostControl : Message()
        data class Other(val type: Int) : Message()
    }

    fun decode(bytes: ByteArray): Message? {
        if (bytes.isEmpty()) return null
        if (bytes.size >= RIDE_ON.size && bytes.copyOfRange(0, RIDE_ON.size).contentEquals(RIDE_ON)) {
            return Message.RideOnResponse
        }
        val type = bytes[0].toInt() and 0xFF
        val body = bytes.copyOfRange(1, bytes.size)
        return try {
            when (type) {
                MSG_CONTROLLER_NOTIFICATION -> decodeKeyPad(body)
                MSG_BATTERY -> {
                    val fields = Protobuf.parse(body)
                    val level = fields.firstOrNull { it.number == 2 }?.varint?.toInt()
                    if (level != null) Message.Battery(level) else Message.Other(type)
                }
                MSG_EMPTY -> Message.Empty
                MSG_LOST_CONTROL -> Message.LostControl
                else -> Message.Other(type)
            }
        } catch (e: Exception) {
            Message.Other(type)
        }
    }

    private const val ANALOG_THRESHOLD = 25

    fun decodeKeyPad(body: ByteArray): Message.Buttons {
        val fields = Protobuf.parse(body)
        val map = fields.firstOrNull { it.number == 1 }?.varint
        val pressed = HashSet<RideButton>()
        if (map != null) {
            for (b in RideButton.digital) {
                // A cleared bit means the button is pressed.
                if (map and b.mask.toLong() == 0L) pressed.add(b)
            }
        }
        val analog = HashMap<Int, Int>()
        for (f in fields.filter { it.number == 3 && it.bytes != null }) {
            val sub = Protobuf.parse(f.bytes!!)
            val loc = sub.firstOrNull { it.number == 1 }?.varint?.toInt() ?: 0
            val raw = sub.firstOrNull { it.number == 2 }?.varint ?: continue
            val value = Protobuf.zigZag(raw).toInt()
            analog[loc] = value
            if (kotlin.math.abs(value) >= ANALOG_THRESHOLD) {
                when (loc) {
                    0 -> pressed.add(RideButton.PADDLE_LEFT)
                    1 -> pressed.add(RideButton.PADDLE_RIGHT)
                }
            }
        }
        return Message.Buttons(pressed, analog)
    }
}

enum class RideButton(val mask: Int) {
    LEFT(0x00001),
    UP(0x00002),
    RIGHT(0x00004),
    DOWN(0x00008),
    A(0x00010),
    B(0x00020),
    Y(0x00040),
    Z(0x00080),
    SHIFT_UP_LEFT(0x00100),
    SHIFT_DOWN_LEFT(0x00200),
    POWERUP_LEFT(0x00400),
    ONOFF_LEFT(0x00800),
    SHIFT_UP_RIGHT(0x01000),
    SHIFT_DOWN_RIGHT(0x02000),
    POWERUP_RIGHT(0x04000),
    ONOFF_RIGHT(0x08000),
    PADDLE_LEFT(0),
    PADDLE_RIGHT(0);

    companion object {
        val digital = entries.filter { it.mask != 0 }
    }
}

/** Minimal protobuf wire-format reader (varint and length-delimited fields). */
object Protobuf {
    data class Field(val number: Int, val wireType: Int, val varint: Long?, val bytes: ByteArray?)

    fun parse(data: ByteArray): List<Field> {
        val out = ArrayList<Field>()
        var i = 0
        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = data[i++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) break
                shift += 7
                if (shift > 63) throw IllegalArgumentException("varint too long")
            }
            return result
        }
        while (i < data.size) {
            val key = readVarint()
            val number = (key shr 3).toInt()
            when (val wt = (key and 0x7).toInt()) {
                0 -> out.add(Field(number, wt, readVarint(), null))
                2 -> {
                    val len = readVarint().toInt()
                    if (len < 0 || i + len > data.size) throw IllegalArgumentException("bad length")
                    out.add(Field(number, wt, null, data.copyOfRange(i, i + len)))
                    i += len
                }
                5 -> { i += 4; out.add(Field(number, wt, null, null)) }
                1 -> { i += 8; out.add(Field(number, wt, null, null)) }
                else -> throw IllegalArgumentException("unsupported wire type $wt")
            }
        }
        return out
    }

    fun zigZag(n: Long): Long = (n ushr 1) xor -(n and 1)
}

/**
 * Turns raw "currently pressed" sets (which the controller repeats) into press / release edges,
 * with hold duration on release.
 */
class ButtonEdgeTracker {
    private val downSince = HashMap<RideButton, Long>()

    sealed class Edge {
        data class Pressed(val button: RideButton) : Edge()
        data class Released(val button: RideButton, val heldMs: Long) : Edge()
    }

    fun update(pressed: Set<RideButton>, nowMs: Long): List<Edge> {
        val edges = ArrayList<Edge>()
        for (b in pressed) if (b !in downSince) {
            downSince[b] = nowMs
            edges.add(Edge.Pressed(b))
        }
        val released = downSince.keys.filter { it !in pressed }
        for (b in released) {
            val since = downSince.remove(b)!!
            edges.add(Edge.Released(b, nowMs - since))
        }
        return edges
    }

    fun heldButtons(nowMs: Long): Map<RideButton, Long> = downSince.mapValues { nowMs - it.value }

    fun reset() = downSince.clear()
}
