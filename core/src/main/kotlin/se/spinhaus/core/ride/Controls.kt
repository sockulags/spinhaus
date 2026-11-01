package se.spinhaus.core.ride

import kotlinx.serialization.Serializable
import se.spinhaus.core.ble.ButtonEdgeTracker
import se.spinhaus.core.ble.RideButton

@Serializable
enum class ControlAction {
    NONE, SHIFT_UP, SHIFT_DOWN, INTENSITY_UP, INTENSITY_DOWN, SKIP_INTERVAL, TOGGLE_ERG,
    PAUSE_RESUME, LAP, NEXT_PAGE, PREV_PAGE, TOGGLE_GRAPH, GRADE_UP, GRADE_DOWN,
}

@Serializable
data class ButtonBinding(val short: ControlAction = ControlAction.NONE, val long: ControlAction = ControlAction.NONE)

/** Button bindings for the two control contexts. */
@Serializable
data class ControllerMapping(
    /** Used while the trainer is in ERG mode. */
    val erg: Map<RideButton, ButtonBinding> = defaultErg,
    /** Used in free ride / sim mode (including ERG-off segments). */
    val sim: Map<RideButton, ButtonBinding> = defaultSim,
) {
    companion object {
        private val common = mapOf(
            RideButton.LEFT to ButtonBinding(ControlAction.PREV_PAGE),
            RideButton.RIGHT to ButtonBinding(ControlAction.NEXT_PAGE),
            RideButton.Y to ButtonBinding(ControlAction.PAUSE_RESUME),
            RideButton.Z to ButtonBinding(ControlAction.TOGGLE_GRAPH),
        )
        val defaultErg: Map<RideButton, ButtonBinding> = common + mapOf(
            RideButton.SHIFT_UP_RIGHT to ButtonBinding(ControlAction.INTENSITY_UP),
            RideButton.SHIFT_DOWN_RIGHT to ButtonBinding(ControlAction.INTENSITY_DOWN),
            RideButton.SHIFT_UP_LEFT to ButtonBinding(ControlAction.INTENSITY_UP),
            RideButton.SHIFT_DOWN_LEFT to ButtonBinding(ControlAction.INTENSITY_DOWN),
            RideButton.UP to ButtonBinding(ControlAction.INTENSITY_UP),
            RideButton.DOWN to ButtonBinding(ControlAction.INTENSITY_DOWN),
            RideButton.A to ButtonBinding(ControlAction.LAP, ControlAction.SKIP_INTERVAL),
            RideButton.B to ButtonBinding(ControlAction.TOGGLE_ERG),
        )
        val defaultSim: Map<RideButton, ButtonBinding> = common + mapOf(
            RideButton.SHIFT_UP_RIGHT to ButtonBinding(ControlAction.SHIFT_UP),
            RideButton.SHIFT_DOWN_RIGHT to ButtonBinding(ControlAction.SHIFT_DOWN),
            RideButton.SHIFT_UP_LEFT to ButtonBinding(ControlAction.SHIFT_UP),
            RideButton.SHIFT_DOWN_LEFT to ButtonBinding(ControlAction.SHIFT_DOWN),
            RideButton.UP to ButtonBinding(ControlAction.GRADE_UP),
            RideButton.DOWN to ButtonBinding(ControlAction.GRADE_DOWN),
            RideButton.A to ButtonBinding(ControlAction.LAP, ControlAction.SKIP_INTERVAL),
            RideButton.B to ButtonBinding(ControlAction.TOGGLE_ERG),
        )
    }
}

/**
 * Converts controller button edges into actions. A button whose binding has a long action fires its
 * short action on release (if held < [longPressMs]) and its long action once the hold passes the
 * threshold. Buttons without a long action fire immediately on press.
 */
class ControlInterpreter(var longPressMs: Long = 800) {
    private val longFired = HashSet<RideButton>()

    fun onEdges(edges: List<ButtonEdgeTracker.Edge>, binding: (RideButton) -> ButtonBinding): List<ControlAction> {
        val out = ArrayList<ControlAction>()
        for (e in edges) when (e) {
            is ButtonEdgeTracker.Edge.Pressed -> {
                val b = binding(e.button)
                if (b.long == ControlAction.NONE && b.short != ControlAction.NONE) out.add(b.short)
            }
            is ButtonEdgeTracker.Edge.Released -> {
                val b = binding(e.button)
                val wasLong = longFired.remove(e.button)
                if (b.long != ControlAction.NONE && !wasLong && b.short != ControlAction.NONE) out.add(b.short)
            }
        }
        return out
    }

    /** Call periodically with currently held buttons to fire long-press actions. */
    fun onHeld(held: Map<RideButton, Long>, binding: (RideButton) -> ButtonBinding): List<ControlAction> {
        val out = ArrayList<ControlAction>()
        for ((button, ms) in held) {
            val b = binding(button)
            if (b.long != ControlAction.NONE && ms >= longPressMs && longFired.add(button)) out.add(b.long)
        }
        return out
    }
}
