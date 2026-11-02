package se.spinhaus.app.ride

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import se.spinhaus.app.AppContainer
import se.spinhaus.app.MainActivity
import se.spinhaus.app.data.settings
import se.spinhaus.app.data.withSettings
import se.spinhaus.app.ui.Fmt
import se.spinhaus.app.ui.LocalLanguage
import se.spinhaus.app.ui.components.WorkoutChart
import se.spinhaus.app.ui.t
import se.spinhaus.app.ui.theme.NumberStyle
import se.spinhaus.app.ui.theme.SpinColors
import se.spinhaus.app.ui.theme.SpinhausTheme
import se.spinhaus.core.workout.StepKind
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Floating stats box drawn over other apps (Netflix, YouTube…) while a ride is running and
 * Spinhaus is in the background.
 */
class FilmOverlay(private val context: Context, private val scope: CoroutineScope, private val app: AppContainer) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val prefs = context.getSharedPreferences("spinhaus-overlay", Context.MODE_PRIVATE)
    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null
    private var params: WindowManager.LayoutParams? = null

    fun canDraw(): Boolean = Settings.canDrawOverlays(context)

    fun permissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    init {
        scope.launch {
            combine(app.ride.session, app.appVisible) { s, visible -> s != null && !visible && s.profile.settings.film.overlay }
                .collect { show -> if (show && canDraw()) show() else hide() }
        }
    }

    private fun show() {
        if (view != null) return
        val o = OverlayOwner().also { it.start() }
        val v = ComposeView(context).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setContent { OverlayContent() }
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("x", 40)
            y = prefs.getInt("y", 40)
        }
        try {
            wm.addView(v, p)
            view = v
            owner = o
            params = p
        } catch (e: Exception) {
            o.stop()
        }
    }

    private fun hide() {
        val v = view ?: return
        runCatching { wm.removeView(v) }
        owner?.stop()
        view = null
        owner = null
        params?.let { prefs.edit().putInt("x", it.x).putInt("y", it.y).apply() }
        params = null
    }

    private fun moveBy(dx: Float, dy: Float) {
        val p = params ?: return
        val v = view ?: return
        p.x = (p.x + dx.roundToInt()).coerceAtLeast(0)
        p.y = (p.y + dy.roundToInt()).coerceAtLeast(0)
        runCatching { wm.updateViewLayout(v, p) }
    }

    private fun openApp() {
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        )
    }

    private fun toggleExpanded() {
        val s = app.ride.session.value ?: return
        scope.launch {
            val p = app.profiles.get(s.profile.id) ?: return@launch
            val st = p.settings
            app.profiles.update(p.withSettings(st.copy(film = st.film.copy(expanded = !st.film.expanded))))
        }
    }

    @Composable
    private fun OverlayContent() {
        val session by app.ride.session.collectAsState()
        val snap by app.ride.snapshot.collectAsState()
        val s = session ?: return
        val sn = snap ?: return
        val profileFlow = androidx.compose.runtime.remember(s.profile.id) { app.profiles.observe(s.profile.id) }
        val profile by profileFlow.collectAsState(initial = s.profile)
        val film = (profile ?: s.profile).settings.film
        val zone = SpinColors.powerZone(sn.powerZone)
        val alert = sn.ergProtecting || (sn.step != null && sn.stepRemainingS <= 3.0 && sn.nextStep != null)
        CompositionLocalProvider(LocalLanguage provides s.profile.language) {
            SpinhausTheme {
                Column(
                    Modifier
                        .alpha(film.opacity.coerceIn(0.3f, 1f))
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (alert) Color(0xEE3A2A00) else Color(0xE60E1116))
                        .pointerInput(Unit) { detectDragGestures { change, drag -> change.consume(); moveBy(drag.x, drag.y) } }
                        .pointerInput(Unit) { detectTapGestures(onDoubleTap = { toggleExpanded() }, onTap = { openApp() }) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Value("${sn.power3sW}", "W", zone, 34)
                        sn.targetW?.let { Value("$it", t("target", "mål"), SpinColors.Primary, 22) }
                        Value(sn.hr?.toString() ?: "–", "bpm", sn.hrZone?.let { SpinColors.hrZone(it) } ?: Color.White, 22)
                        if (sn.step != null) Value(Fmt.duration(ceil(sn.stepRemainingS).toInt()), "", Color.White, 22)
                    }
                    if (sn.ergProtecting) {
                        Text(t("Low cadence – resistance eased", "Låg kadens – motståndet sänkt"), color = SpinColors.Warn, fontSize = 12.sp)
                    }
                    if (film.expanded) {
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Value(sn.cadence?.toString() ?: "–", "rpm", Color.White, 18)
                            Value(Fmt.duration(sn.rideTimeS), "", Color.White, 18)
                            if (!sn.ergActive) Value("${sn.gear}", t("gear", "växel"), Color.White, 18)
                            sn.nextStep?.let { n ->
                                val label = when (n.kind) {
                                    StepKind.FREE -> t("Next: free", "Nästa: fri")
                                    StepKind.MAX -> t("Next: max", "Nästa: max")
                                    else -> t("Next: ", "Nästa: ") + (n.from?.let { f -> if (s.expanded?.workout?.absolutePower == true) f.roundToInt() else (f * s.profile.ftp).roundToInt() } ?: "–") + " W"
                                }
                                Text(label, color = SpinColors.Muted, fontSize = 14.sp, modifier = Modifier.align(Alignment.CenterVertically))
                            }
                        }
                        val ew = s.expanded
                        if (ew != null && !sn.workoutComplete) {
                            Spacer(Modifier.height(6.dp))
                            WorkoutChart(ew.steps, s.profile.ftp, ew.workout.absolutePower, modifier = Modifier.width(320.dp), height = 40.dp, progressS = sn.workoutTimeS)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Value(value: String, unit: String, color: Color, size: Int) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = NumberStyle.copy(fontSize = size.sp, color = color, fontWeight = FontWeight.Bold))
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(3.dp))
                Text(unit, color = SpinColors.Muted, fontSize = (size * 0.4f).coerceAtLeast(10f).sp, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
    }

    /** Minimal lifecycle + saved-state owner so a ComposeView can live outside an Activity. */
    private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val controller = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry

        fun start() {
            controller.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun stop() {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }
}
