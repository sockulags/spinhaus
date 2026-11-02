package se.spinhaus.app.ui.screens

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import se.spinhaus.app.AppContainer
import se.spinhaus.app.ble.DeviceStatus
import se.spinhaus.app.data.DashboardConfig
import se.spinhaus.app.data.ProfileEntity
import se.spinhaus.app.data.TileSize
import se.spinhaus.app.data.TileSpec
import se.spinhaus.app.data.TileType
import se.spinhaus.app.data.Units
import se.spinhaus.app.data.settings
import se.spinhaus.app.data.withSettings
import se.spinhaus.app.ui.Fmt
import se.spinhaus.app.ui.LocalLanguage
import se.spinhaus.app.ui.Nav
import se.spinhaus.app.ui.components.ElevationChart
import se.spinhaus.app.ui.components.StatusDot
import se.spinhaus.app.ui.components.WorkoutChart
import se.spinhaus.app.ui.t
import se.spinhaus.app.ui.theme.NumberStyle
import se.spinhaus.app.ui.theme.SpinColors
import se.spinhaus.app.ui.tr
import se.spinhaus.core.ride.ControlAction
import se.spinhaus.core.ride.RideEvent
import se.spinhaus.core.ride.RideMode
import se.spinhaus.core.ride.RideSnapshot
import se.spinhaus.core.ride.RideStatus
import se.spinhaus.core.workout.Step
import se.spinhaus.core.workout.StepKind
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun RideScreen(app: AppContainer, nav: Nav, profile: ProfileEntity) {
    val session by app.ride.session.collectAsState()
    val snap by app.ride.snapshot.collectAsState()
    val samplesVersion by app.ride.samplesVersion.collectAsState()
    val context = LocalContext.current
    var confirmEnd by remember { mutableStateOf(false) }
    var filmOpen by remember { mutableStateOf(false) }
    var ending by remember { mutableStateOf(false) }
    val settings = profile.settings
    var page by remember { mutableIntStateOf(0) }
    var showGraph by remember { mutableStateOf(settings.showGraph) }
    val pages = settings.dashboard.pages.ifEmpty { DashboardConfig.standard }

    // Keep the screen on during the ride.
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    LaunchedEffect(Unit) {
        app.ride.events.collect { e ->
            if (e is RideEvent.UiAction) when (e.action) {
                ControlAction.NEXT_PAGE -> page = (page + 1) % pages.size
                ControlAction.PREV_PAGE -> page = (page - 1 + pages.size) % pages.size
                ControlAction.TOGGLE_GRAPH -> showGraph = !showGraph
                else -> {}
            }
        }
    }
    BackHandler { confirmEnd = true }

    val s = session
    val sn = snap
    if (s == null || sn == null) {
        Box(Modifier.fillMaxSize().background(SpinColors.Background), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (ending) t("Saving…", "Sparar…") else t("No ride in progress", "Ingen pågående åktur"), color = SpinColors.Muted)
                if (!ending) TextButton(onClick = { nav.home() }) { Text(t("Home", "Hem")) }
            }
        }
        return
    }

    val trainer by app.hub.trainer.collectAsState()
    val trainerStatus by (trainer?.status ?: remember { flowOf(null) }).collectAsState(initial = null)
    val controllers by app.hub.controllers.collectAsState()
    val ctrlStatus by (controllers.firstOrNull()?.status ?: remember { flowOf(null) }).collectAsState(initial = null)
    val hr by app.hub.heartRate.collectAsState()
    val hrStatus by (hr?.status ?: remember { flowOf(null) }).collectAsState(initial = null)

    val elsewhereFlow = remember(trainer) { trainer?.controlledElsewhere ?: kotlinx.coroutines.flow.MutableStateFlow(false) }
    val trainerElsewhere by elsewhereFlow.collectAsState()
    val zoneColor by animateColorAsState(SpinColors.powerZone(sn.powerZone), label = "zone")
    val imperial = settings.units == Units.IMPERIAL
    val lang = LocalLanguage.current

    val worldData = s.world
    if (worldData != null) {
        var cameraTick by remember { mutableIntStateOf(0) }
        Box(Modifier.fillMaxSize().background(SpinColors.Background)) {
            WorldView(app, worldData, { app.ride.snapshot.value }, cameraTick, Modifier.fillMaxSize())
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Box(Modifier.fillMaxWidth().height(6.dp).background(zoneColor))
                TopStatus(sn, s.title, trainerStatus, ctrlStatus, hrStatus)
                StepBanner(sn, s.profile.ftp, (s.mode as? RideMode.Structured)?.workout?.absolutePower == true, lang)
                Row(Modifier.weight(1f).fillMaxWidth().padding(12.dp)) {
                    WorldHud(sn, worldData.init.total, imperial)
                }
                if (showGraph) {
                    val route = (s.mode as? RideMode.Free)?.route
                    if (route != null) Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xCC0E1116)).padding(8.dp)) {
                        ElevationChart(route, sn.distanceM, height = 70.dp)
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        Controls(sn, false, onAction = { app.ride.action(it) }, onEnd = { confirmEnd = true }, onFilm = { filmOpen = true })
                    }
                    BigButton(t("Camera", "Kamera"), null) { cameraTick++ }
                    Spacer(Modifier.width(12.dp))
                }
            }
        }
    } else Column(Modifier.fillMaxSize().background(SpinColors.Background).systemBarsPadding()) {
        // zone accent bar
        Box(Modifier.fillMaxWidth().height(6.dp).background(zoneColor))
        TopStatus(sn, s.title, trainerStatus, ctrlStatus, hrStatus)
        StepBanner(sn, s.profile.ftp, (s.mode as? RideMode.Structured)?.workout?.absolutePower == true, lang)

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
            val tiles = pages[page.coerceIn(0, pages.lastIndex)]
            TileGrid(tiles, sn, s.profile, imperial, maxWidth, maxHeight)
        }

        if (showGraph) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(12.dp)).background(SpinColors.Surface).padding(8.dp)) {
                val expanded = s.expanded
                val route = (s.mode as? RideMode.Free)?.route
                when {
                    expanded != null && !sn.workoutComplete -> {
                        val actual = remember(samplesVersion / 5) {
                            app.ride.samples().mapNotNull { sm -> sm.workoutT?.let { it to sm.power } }.let { thin(it, 400) }
                        }
                        WorkoutChart(expanded.steps, s.profile.ftp, expanded.workout.absolutePower, height = 110.dp, progressS = sn.workoutTimeS, actual = actual)
                    }
                    route != null -> ElevationChart(route, sn.distanceM, height = 90.dp)
                    else -> Text(
                        t("Grade ${Fmt.one(sn.gradePercent)} % – use ▲▼ to change", "Lutning ${Fmt.one(sn.gradePercent)} % – ändra med ▲▼"),
                        color = SpinColors.Muted, modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }

        Controls(sn, s.mode is RideMode.Free && (s.mode as RideMode.Free).route == null,
            onAction = { app.ride.action(it) },
            onEnd = { confirmEnd = true },
            onFilm = { filmOpen = true },
        )
    }

    sn.textMessage?.let { msg ->
        Box(Modifier.fillMaxSize().padding(top = 120.dp), contentAlignment = Alignment.TopCenter) {
            Text(
                msg, color = Color.White, fontSize = 22.sp, textAlign = TextAlign.Center,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.75f)).padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }

    if (trainerElsewhere || trainerStatus == DeviceStatus.RECONNECTING) {
        Box(Modifier.fillMaxSize().padding(top = 110.dp), contentAlignment = Alignment.TopCenter) {
            Text(
                if (trainerElsewhere) t("Another app controls the trainer – close Zwift", "En annan app styr trainern – stäng Zwift")
                else t("Trainer reconnecting…", "Trainern återansluter…"),
                color = Color.White, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(SpinColors.Bad).padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }

    if (sn.hrMissing) {
        Box(Modifier.fillMaxSize().padding(top = 150.dp), contentAlignment = Alignment.TopCenter) {
            Text(
                t("No heart rate – holding the power", "Ingen puls – effekten hålls"),
                color = Color.Black, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(SpinColors.Warn).padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }

    if (sn.ergProtecting) {
        Box(Modifier.fillMaxSize().padding(top = 70.dp), contentAlignment = Alignment.TopCenter) {
            Text(
                t("Low cadence – resistance eased", "Låg kadens – motståndet sänkt"),
                color = Color.Black, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(SpinColors.Warn).padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }

    if (filmOpen) FilmDialog(app, onDismiss = { filmOpen = false })

    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text(t("End ride?", "Avsluta åkturen?")) },
            text = { Text(t("Save the ride to your history, or discard it.", "Spara åkturen i historiken eller släng den.")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmEnd = false
                    ending = true
                    app.scope.launch {
                        val r = app.ride.end(save = true)
                        if (r.activityId != null) nav.showFinished(r.activityId, r.testFtp, r.estimatedFtp) else nav.home()
                    }
                }) { Text(t("Save", "Spara")) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        confirmEnd = false
                        ending = true
                        app.scope.launch { app.ride.end(save = false); nav.home() }
                    }) { Text(t("Discard", "Släng"), color = SpinColors.Bad) }
                    TextButton(onClick = { confirmEnd = false }) { Text(t("Keep riding", "Fortsätt cykla")) }
                }
            },
        )
    }

    // Remember the graph toggle per profile.
    LaunchedEffect(showGraph) {
        if (showGraph != settings.showGraph) app.profiles.update(profile.withSettings(settings.copy(showGraph = showGraph)))
    }
}

private fun thin(points: List<Pair<Int, Int>>, max: Int): List<Pair<Int, Int>> {
    if (points.size <= max) return points
    val step = points.size.toDouble() / max
    return (0 until max).map { i ->
        val from = (i * step).toInt()
        val to = ((i + 1) * step).toInt().coerceAtMost(points.size)
        val chunk = points.subList(from, to.coerceAtLeast(from + 1))
        chunk.first().first to chunk.map { it.second }.average().toInt()
    }
}

@Composable
private fun TopStatus(sn: RideSnapshot, title: String, trainer: DeviceStatus?, ctrl: DeviceStatus?, hr: DeviceStatus?) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        val statusLabel = when (sn.status) {
            RideStatus.WAITING -> t("Start pedalling", "Börja trampa")
            RideStatus.AUTO_PAUSED -> t("Paused", "Pausad")
            RideStatus.PAUSED -> t("Paused", "Pausad")
            RideStatus.RUNNING, RideStatus.FINISHED -> null
        }
        if (statusLabel != null) {
            Text(
                statusLabel, color = Color.Black, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(SpinColors.Warn).padding(horizontal = 10.dp, vertical = 2.dp),
            )
            Spacer(Modifier.width(12.dp))
        }
        Badge(if (sn.ergActive) "ERG" else "SIM", if (sn.ergActive) SpinColors.Primary else SpinColors.powerZones[1])
        if (sn.intensityPercent != 0) {
            Spacer(Modifier.width(6.dp))
            Badge((if (sn.intensityPercent > 0) "+" else "") + "${sn.intensityPercent} %", SpinColors.Warn)
        }
        Spacer(Modifier.width(16.dp))
        DotLabel("T", trainer)
        DotLabel("K", ctrl)
        DotLabel("♥", hr)
        Spacer(Modifier.width(12.dp))
        Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()), color = SpinColors.Muted)
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text, color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(color).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun DotLabel(label: String, status: DeviceStatus?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
        StatusDot(status)
        Spacer(Modifier.width(3.dp))
        Text(label, color = SpinColors.Muted, fontSize = 13.sp)
    }
}

private fun stepLabel(step: Step, ftp: Int, absolute: Boolean, lang: String): String {
    fun w(v: Double?) = v?.let { if (absolute) it.roundToInt() else (it * ftp).roundToInt() }
    val base = when (step.kind) {
        StepKind.FREE -> tr(lang, "Free ride", "Fri cykling")
        StepKind.MAX -> tr(lang, "All out", "Max") + (w(step.from)?.let { " (≈$it W)" } ?: "")
        StepKind.RAMP -> "${w(step.from)} → ${w(step.to)} W"
        StepKind.STEADY -> if (step.hrTarget != null) "♥ " + tr(lang, "heart rate ${(step.hrTarget!! * 100).roundToInt()} % of max", "puls ${(step.hrTarget!! * 100).roundToInt()} % av max") else "${w(step.from)} W"
    }
    val rep = if (step.repeat != null && step.repeatCount != null) " · ${step.repeat}/${step.repeatCount}" else ""
    val cad = step.cadence?.let { " · $it rpm" } ?: ""
    return base + rep + cad
}

@Composable
private fun StepBanner(sn: RideSnapshot, ftp: Int, absolute: Boolean, lang: String) {
    val step = sn.step
    if (step == null && !sn.workoutComplete) return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(12.dp)).background(SpinColors.Surface).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (step == null) {
            Text(t("Workout complete! Keep riding or end the ride.", "Passet är klart! Fortsätt cykla eller avsluta."), color = SpinColors.Good, style = MaterialTheme.typography.titleMedium)
            return@Row
        }
        Column(Modifier.weight(1f)) {
            Text(t("Now", "Nu"), color = SpinColors.Muted, fontSize = 12.sp)
            Text(stepLabel(step, ftp, absolute, lang), style = MaterialTheme.typography.titleLarge, maxLines = 1)
        }
        Text(Fmt.duration(kotlin.math.ceil(sn.stepRemainingS).toInt()), style = NumberStyle.copy(fontSize = 34.sp))
        sn.nextStep?.let { n ->
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text(t("Next", "Nästa") + " · " + Fmt.duration(n.durationS), color = SpinColors.Muted, fontSize = 12.sp)
                Text(stepLabel(n, ftp, absolute, lang), style = MaterialTheme.typography.titleMedium, color = SpinColors.Muted, maxLines = 1)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TileGrid(tiles: List<TileSpec>, sn: RideSnapshot, profile: ProfileEntity, imperial: Boolean, width: Dp, height: Dp) {
    // Size units: large = 2 columns × 2 rows, medium = 2 × 1, small = 1 × 1 on a 6-column grid.
    val cols = if (width > 900.dp) 8 else if (width > 600.dp) 6 else 4
    val unit = (width - 8.dp * (cols - 1)) / cols
    val units = tiles.sumOf { spanOf(it.size).let { (w, h) -> w * h } }
    val rows = ((units + cols - 1) / cols).coerceAtLeast(2)
    val rowH = ((height - 8.dp * (rows - 1)) / rows).coerceIn(56.dp, 180.dp)
    FlowRow(
        Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        maxItemsInEachRow = cols,
    ) {
        for (tile in tiles) {
            val (w, h) = spanOf(tile.size)
            val tw = unit * w + 8.dp * (w - 1)
            val th = rowH * h + 8.dp * (h - 1)
            Tile(tile.type, sn, profile, imperial, Modifier.width(tw).height(th))
        }
    }
}

private fun spanOf(size: TileSize): Pair<Int, Int> = when (size) {
    TileSize.LARGE -> 2 to 2
    TileSize.MEDIUM -> 2 to 1
    TileSize.SMALL -> 1 to 1
}

@Composable
private fun Tile(type: TileType, sn: RideSnapshot, profile: ProfileEntity, imperial: Boolean, modifier: Modifier) {
    val (label, value, unit, color) = tileContent(type, sn, profile, imperial)
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(SpinColors.Surface).padding(10.dp)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val valueSize = (maxHeight.value * 0.55f).coerceIn(18f, 120f)
            Column(Modifier.fillMaxSize()) {
                Text(label, color = SpinColors.Muted, fontSize = 13.sp, maxLines = 1)
                Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Text(value, style = NumberStyle.copy(fontSize = valueSize.sp, color = color), maxLines = 1)
                    if (unit != null) {
                        Spacer(Modifier.width(4.dp))
                        Text(unit, color = SpinColors.Muted, fontSize = (valueSize * 0.28f).coerceAtLeast(11f).sp)
                    }
                }
            }
        }
    }
}

private data class TileText(val label: String, val value: String, val unit: String?, val color: Color)

@Composable
private fun tileContent(type: TileType, sn: RideSnapshot, profile: ProfileEntity, imperial: Boolean): TileText {
    val white = SpinColors.OnSurface
    return when (type) {
        TileType.POWER_3S -> TileText(t("Power", "Effekt"), "${sn.power3sW}", "W", SpinColors.powerZone(sn.powerZone))
        TileType.POWER -> TileText(t("Power (1 s)", "Effekt (1 s)"), "${sn.powerW}", "W", white)
        TileType.TARGET -> TileText(t("Target", "Mål"), sn.targetW?.toString() ?: "–", if (sn.targetW != null) "W" else null, SpinColors.Primary)
        TileType.PERCENT_OF_TARGET -> TileText(t("% of target", "% av mål"), sn.targetW?.takeIf { it > 0 }?.let { "${sn.power3sW * 100 / it}" } ?: "–", "%", white)
        TileType.HEART_RATE -> TileText(
            t("Heart rate", "Puls") + (sn.hrTargetBpm?.let { t(" · target $it", " · mål $it") } ?: ""),
            sn.hr?.toString() ?: "–", "bpm",
            sn.hrTargetBpm?.let { tgt -> val h = sn.hr; if (h == null) white else if (kotlin.math.abs(h - tgt) <= 3) SpinColors.Good else SpinColors.Warn }
                ?: sn.hrZone?.let { SpinColors.hrZone(it) } ?: white,
        )
        TileType.HR_ZONE -> TileText(t("HR zone", "Pulszon"), sn.hrZone?.let { "Z$it" } ?: "–", null, sn.hrZone?.let { SpinColors.hrZone(it) } ?: white)
        TileType.CADENCE -> {
            val target = sn.cadenceTarget
            val c = sn.cadence
            val color = if (target != null && c != null && kotlin.math.abs(c - target) > 10) SpinColors.Warn else white
            TileText(t("Cadence", "Kadens") + (target?.let { " · $it" } ?: ""), c?.toString() ?: "–", "rpm", color)
        }
        TileType.LAP_TIME -> TileText(t("Lap ${sn.lap}", "Varv ${sn.lap}"), Fmt.duration(sn.lapTimeS), null, white)
        TileType.TOTAL_TIME -> TileText(t("Time", "Tid"), Fmt.duration(sn.rideTimeS), null, white)
        TileType.INTERVAL_REMAINING -> TileText(
            t("Interval left", "Kvar av intervall"),
            if (sn.step != null) Fmt.duration(kotlin.math.ceil(sn.stepRemainingS).toInt()) else Fmt.duration(sn.rideTimeS), null, white,
        )
        TileType.NEXT_INTERVAL -> {
            val n = sn.nextStep
            val w = n?.from?.let { if (it > 5) it.roundToInt() else (it * profile.ftp).roundToInt() }
            TileText(t("Next", "Nästa"), if (n == null) "–" else if (n.kind == StepKind.FREE) t("Free", "Fri") else "${w ?: "–"}", if (n != null && n.kind != StepKind.FREE) "W" else null, SpinColors.Muted)
        }
        TileType.SPEED -> TileText(t("Speed", "Fart"), Fmt.speedValue(sn.speedKmh, imperial), if (imperial) "mph" else "km/h", white)
        TileType.DISTANCE -> TileText(t("Distance", "Distans"), Fmt.distanceValue(sn.distanceM, imperial), if (imperial) "mi" else "km", white)
        TileType.GRADE -> TileText(t("Grade", "Lutning"), Fmt.one(sn.gradePercent), "%", white)
        TileType.GEAR -> TileText(t("Gear", "Växel"), if (sn.ergActive) "–" else "${sn.gear}", "/${sn.gearCount}", white)
        TileType.NORMALIZED_POWER -> TileText("NP", sn.normalizedPower?.toString() ?: "–", "W", white)
        TileType.AVG_POWER -> TileText(t("Avg power", "Snittwatt"), "${sn.avgPower}", "W", white)
        TileType.KJ -> TileText("kJ", "${sn.kj.roundToInt()}", null, white)
        TileType.KCAL -> TileText("kcal", "${sn.kj.roundToInt()}", null, white)
        TileType.WATTS_PER_KG -> TileText("W/kg", Fmt.one(sn.wattsPerKg), null, white)
        TileType.TSS -> TileText("TSS", "${sn.tss.roundToInt()}", null, white)
        TileType.CLOCK -> TileText(t("Clock", "Klocka"), SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()), null, white)
        TileType.AVG_HR -> TileText(t("Avg HR", "Snittpuls"), sn.avgHr?.toString() ?: "–", "bpm", white)
        TileType.MAX_POWER -> TileText(t("Max power", "Maxeffekt"), "${sn.maxPower}", "W", white)
    }
}

@Composable
private fun Controls(sn: RideSnapshot, manualGrade: Boolean, onAction: (ControlAction) -> Unit, onEnd: () -> Unit, onFilm: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val paused = sn.status == RideStatus.PAUSED
        BigButton(if (paused) t("Resume", "Fortsätt") else t("Pause", "Paus"), if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause) { onAction(ControlAction.PAUSE_RESUME) }
        if (sn.ergActive) {
            BigButton("−5 %", Icons.Filled.KeyboardArrowDown) { onAction(ControlAction.INTENSITY_DOWN) }
            BigButton("+5 %", Icons.Filled.KeyboardArrowUp) { onAction(ControlAction.INTENSITY_UP) }
        } else {
            BigButton(t("Easier", "Lättare"), Icons.Filled.KeyboardArrowDown) { onAction(ControlAction.SHIFT_DOWN) }
            BigButton(t("Harder", "Tyngre"), Icons.Filled.KeyboardArrowUp) { onAction(ControlAction.SHIFT_UP) }
            if (manualGrade) {
                BigButton(t("Grade −", "Lutning −"), null) { onAction(ControlAction.GRADE_DOWN) }
                BigButton(t("Grade +", "Lutning +"), null) { onAction(ControlAction.GRADE_UP) }
            }
        }
        if (sn.step != null) {
            BigButton(if (sn.ergEnabled) t("ERG off", "ERG av") else t("ERG on", "ERG på"), null) { onAction(ControlAction.TOGGLE_ERG) }
            BigButton(t("Skip", "Hoppa över"), Icons.Filled.SkipNext) { onAction(ControlAction.SKIP_INTERVAL) }
        }
        Spacer(Modifier.weight(1f))
        BigButton("🎬 " + t("Film", "Film"), null, onFilm)
        FilledTonalButton(onClick = onEnd, modifier = Modifier.height(56.dp)) {
            Icon(Icons.Filled.Stop, null, tint = SpinColors.Bad)
            Spacer(Modifier.width(6.dp))
            Text(t("End", "Avsluta"))
        }
    }
}

@Composable
private fun BigButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = Modifier.height(56.dp)) {
        if (icon != null) {
            Icon(icon, null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(label, maxLines = 1)
    }
}


private val filmApps = listOf(
    "com.netflix.mediaclient" to "Netflix",
    "com.google.android.youtube" to "YouTube",
    "se.svt.svtplay" to "SVT Play",
    "se.svt.android.svtplay" to "SVT Play",
    "se.tv4.tv4playtab" to "TV4 Play",
    "se.tv4.tv4play" to "TV4 Play",
    "com.viaplay.android" to "Viaplay",
    "com.disney.disneyplus" to "Disney+",
    "com.wbd.stream" to "Max",
    "com.amazon.avod.thirdpartyclient" to "Prime Video",
)

@Composable
private fun FilmDialog(app: AppContainer, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val pm = context.packageManager
    val installed = remember { filmApps.mapNotNull { (pkg, name) -> pm.getLaunchIntentForPackage(pkg)?.let { name to it } }.distinctBy { it.first } }
    var canDraw by remember { mutableStateOf(app.overlay.canDraw()) }
    LaunchedEffect(Unit) {
        while (!canDraw) { kotlinx.coroutines.delay(1000); canDraw = app.overlay.canDraw() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("Watch while you ride", "Titta medan du cyklar")) },
        text = {
            Column {
                if (!canDraw) {
                    Text(
                        t("To show your numbers on top of the film, allow Spinhaus to \"display over other apps\". You only need to do this once.",
                            "För att visa dina siffror ovanpå filmen behöver Spinhaus få \"visa över andra appar\". Det behövs bara en gång."),
                    )
                    Spacer(Modifier.height(12.dp))
                    FilledTonalButton(onClick = { runCatching { context.startActivity(app.overlay.permissionIntent()) } }) {
                        Text(t("Allow", "Tillåt"))
                    }
                } else {
                    Text(
                        t("Open your film app. A small box with your numbers floats on top – drag it, double-tap for more, tap to come back. The handlebar buttons keep working; right power-up pauses the film.",
                            "Öppna din filmapp. En liten ruta med dina siffror ligger ovanpå – dra i den, dubbeltryck för mer, tryck för att komma tillbaka. Knapparna på styret fungerar som vanligt; höger power-up pausar filmen."),
                    )
                    Spacer(Modifier.height(12.dp))
                    if (installed.isEmpty()) Text(t("No film apps found – open one from the home screen.", "Inga filmappar hittades – öppna en från hemskärmen."), color = SpinColors.Muted)
                    FlowRowCompat {
                        for ((name, intent) in installed) FilledTonalButton(onClick = {
                            onDismiss()
                            runCatching { context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }) { Text(name) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(t("Close", "Stäng")) } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowCompat(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}
