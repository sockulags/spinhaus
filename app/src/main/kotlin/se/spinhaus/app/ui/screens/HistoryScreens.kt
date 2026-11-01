package se.spinhaus.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.spinhaus.app.AppContainer
import se.spinhaus.app.data.ActivityEntity
import se.spinhaus.app.data.ActivityRepository
import se.spinhaus.app.data.FtpEntryEntity
import se.spinhaus.app.data.ProfileEntity
import se.spinhaus.app.data.Units
import se.spinhaus.app.data.settings
import se.spinhaus.app.ui.Fmt
import se.spinhaus.app.ui.LocalLanguage
import se.spinhaus.app.ui.Nav
import se.spinhaus.app.ui.Routes
import se.spinhaus.app.ui.components.Avatar
import se.spinhaus.app.ui.components.BarChart
import se.spinhaus.app.ui.components.CenteredMessage
import se.spinhaus.app.ui.components.ScreenScaffold
import se.spinhaus.app.ui.components.SectionTitle
import se.spinhaus.app.ui.components.SeriesChart
import se.spinhaus.app.ui.components.SpinCard
import se.spinhaus.app.ui.components.StackedBar
import se.spinhaus.app.ui.components.StatValue
import se.spinhaus.app.ui.t
import se.spinhaus.app.ui.theme.SpinColors
import se.spinhaus.core.training.FitnessModel
import se.spinhaus.core.training.HeartRateZones
import se.spinhaus.core.training.HrZoneModel
import se.spinhaus.core.training.PowerAnalysis
import se.spinhaus.core.training.PowerZones
import se.spinhaus.core.training.Sample
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------------------------
// History
// ---------------------------------------------------------------------------------------------

@Composable
fun HistoryScreen(app: AppContainer, nav: Nav, profile: ProfileEntity) {
    var everyone by rememberSaveable { mutableStateOf(false) }
    val activityFlow = remember(everyone, profile.id) {
        if (everyone) app.activities.observeCompleted() else app.activities.observeCompletedFor(profile.id)
    }
    val activities by activityFlow.collectAsState(initial = null)
    val profilesFlow = remember { app.profiles.observeAll() }
    val profiles by profilesFlow.collectAsState(initial = emptyList())
    val profilesById = remember(profiles) { profiles.associateBy { it.id } }
    val imperial = remember(profile.settingsJson) { profile.settings.units == Units.IMPERIAL }
    val locale = rememberUiLocale()

    ScreenScaffold(
        title = t("History", "Historik"),
        onBack = nav::back,
        actions = {
            IconButton(onClick = { nav.go(Routes.TRENDS) }) {
                Icon(Icons.Filled.ShowChart, contentDescription = t("Trends", "Trender"))
            }
        },
    ) { pad ->
        val list = activities
        val groups = remember(list) { list?.groupBy { localDate(it.startedAt) } ?: emptyMap() }
        val thisWeek = remember(list) {
            val start = weekStart(LocalDate.now())
            list?.filter { !localDate(it.startedAt).isBefore(start) } ?: emptyList()
        }

        LazyColumn(
            modifier = Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "filters") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !everyone, onClick = { everyone = false }, label = { Text(t("Me", "Mig")) })
                    FilterChip(selected = everyone, onClick = { everyone = true }, label = { Text(t("Everyone", "Alla")) })
                }
            }
            if (list == null) {
                item(key = "loading") { CenteredMessage(t("Loading…", "Laddar…")) }
            } else {
                item(key = "week") { WeekSummaryCard(thisWeek, imperial) }
                if (list.isEmpty()) {
                    item(key = "empty") {
                        CenteredMessage(t("No rides yet. Your finished rides will show up here.", "Inga åkturer än. Dina avslutade åkturer visas här."))
                    }
                }
                groups.forEach { (date, dayActivities) ->
                    item(key = "day-${date.toEpochDay()}") { SectionTitle(dayHeading(date, locale)) }
                    items(dayActivities, key = { it.id }) { a ->
                        ActivityRow(
                            a = a,
                            owner = if (everyone) profilesById[a.profileId] else null,
                            showOwner = everyone,
                            imperial = imperial,
                            locale = locale,
                            onClick = { nav.go(Routes.activity(a.id)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekSummaryCard(week: List<ActivityEntity>, imperial: Boolean) {
    val totalS = week.sumOf { it.durationS }
    val tss = week.sumOf { it.tss ?: 0.0 }
    val distance = week.sumOf { it.distanceM }
    SpinCard(Modifier.fillMaxWidth()) {
        Text(t("This week", "Denna vecka"), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        StatFlow(
            listOf(
                HistoryStat(t("Time", "Tid"), Fmt.minutes(totalS)),
                HistoryStat("TSS", tss.roundToInt().toString()),
                HistoryStat(t("Rides", "Åkturer"), week.size.toString()),
                HistoryStat(t("Distance", "Distans"), Fmt.distance(distance, imperial)),
            ),
        )
    }
}

@Composable
private fun ActivityRow(
    a: ActivityEntity,
    owner: ProfileEntity?,
    showOwner: Boolean,
    imperial: Boolean,
    locale: Locale,
    onClick: () -> Unit,
) {
    val time = remember(a.startedAt, locale) { formatTime(a.startedAt, locale) }
    SpinCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showOwner) {
                Avatar(
                    name = owner?.name ?: "?",
                    color = owner?.let { Color(it.colorArgb) } ?: SpinColors.Muted,
                    size = 36,
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(a.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val who = if (showOwner && owner != null) "${owner.name} · " else ""
                Text("$who$time", style = MaterialTheme.typography.bodySmall, color = SpinColors.Muted)
            }
        }
        Spacer(Modifier.height(8.dp))
        val stats = mutableListOf(
            HistoryStat(t("Time", "Tid"), Fmt.duration(a.durationS)),
            HistoryStat(t("Distance", "Distans"), Fmt.distance(a.distanceM, imperial)),
            HistoryStat(t("Avg", "Snitt"), a.avgW.toString(), "W"),
        )
        a.npW?.let { stats.add(HistoryStat("NP", it.toString(), "W")) }
        a.tss?.let { stats.add(HistoryStat("TSS", it.roundToInt().toString())) }
        a.avgHr?.let { stats.add(HistoryStat(t("Avg HR", "Snittpuls"), it.toString(), "bpm")) }
        StatFlow(stats, spacing = 20.dp)
    }
}

// ---------------------------------------------------------------------------------------------
// Activity detail
// ---------------------------------------------------------------------------------------------

@Composable
fun ActivityScreen(
    app: AppContainer,
    nav: Nav,
    profile: ProfileEntity,
    activityId: Long,
    testFtp: Int?,
    estimatedFtp: Int?,
    justFinished: Boolean,
) {
    val activityFlow = remember(activityId) { app.activities.observe(activityId) }
    val activity by activityFlow.collectAsState(initial = null)
    var samples by remember(activityId) { mutableStateOf<List<Sample>?>(null) }
    LaunchedEffect(activityId) { samples = app.activities.samples(activityId) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    ScreenScaffold(
        title = if (justFinished) t("Ride complete", "Åkturen klar") else (activity?.title ?: t("Ride", "Åktur")),
        onBack = { if (justFinished) nav.home() else nav.back() },
        actions = {
            if (activity != null && !deleting) {
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Filled.Delete, contentDescription = t("Delete", "Ta bort"))
                }
            }
            if (justFinished) {
                TextButton(onClick = { nav.home() }) { Text(t("Done", "Klar")) }
            }
        },
    ) { pad ->
        val a = activity
        if (a == null || deleting) {
            CenteredMessage(t("Loading…", "Laddar…"), Modifier.padding(pad))
        } else {
            ActivityContent(
                app = app,
                nav = nav,
                profile = profile,
                a = a,
                samples = samples,
                testFtp = testFtp,
                estimatedFtp = estimatedFtp,
                justFinished = justFinished,
                modifier = Modifier.padding(pad),
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(t("Delete ride?", "Ta bort åkturen?")) },
            text = { Text(t("The ride and all its data are removed permanently.", "Åkturen och all dess data tas bort permanent.")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    deleting = true
                    app.scope.launch {
                        app.activities.delete(activityId)
                        nav.back()
                    }
                }) { Text(t("Delete", "Ta bort"), color = SpinColors.Bad) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(t("Cancel", "Avbryt")) }
            },
        )
    }
}

@Composable
private fun ActivityContent(
    app: AppContainer,
    nav: Nav,
    profile: ProfileEntity,
    a: ActivityEntity,
    samples: List<Sample>?,
    testFtp: Int?,
    estimatedFtp: Int?,
    justFinished: Boolean,
    modifier: Modifier = Modifier,
) {
    val imperial = remember(profile.settingsJson) { profile.settings.units == Units.IMPERIAL }
    val locale = rememberUiLocale()
    val summary = remember(a.summaryJson) { app.activities.summaryOf(a) }
    val previousFtp = rememberSaveable { profile.ftp }
    var ftpSaved by rememberSaveable { mutableStateOf(false) }
    var suggestionDismissed by rememberSaveable { mutableStateOf(false) }
    var note by rememberSaveable(a.id) { mutableStateOf(a.note ?: "") }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Header
        Column {
            if (justFinished) {
                Text(a.title, style = MaterialTheme.typography.headlineSmall)
            }
            Text(
                remember(a.startedAt, locale) { formatDateTime(a.startedAt, locale) },
                style = MaterialTheme.typography.bodyMedium,
                color = SpinColors.Muted,
            )
        }

        // FTP result / suggestion
        if (testFtp != null) {
            FtpCard(
                newFtp = testFtp,
                previousFtp = previousFtp,
                prominent = true,
                saved = ftpSaved,
                onSave = {
                    app.scope.launch {
                        app.profiles.setFtp(profile.id, testFtp, "TEST", a.id)
                        ftpSaved = true
                    }
                },
                onDismiss = null,
            )
        } else if (estimatedFtp != null && !suggestionDismissed) {
            FtpCard(
                newFtp = estimatedFtp,
                previousFtp = previousFtp,
                prominent = false,
                saved = ftpSaved,
                onSave = {
                    app.scope.launch {
                        app.profiles.setFtp(profile.id, estimatedFtp, "ESTIMATE", a.id)
                        ftpSaved = true
                    }
                },
                onDismiss = { suggestionDismissed = true },
            )
        }

        // Stats grid
        SpinCard(Modifier.fillMaxWidth()) {
            val stats = mutableListOf(
                HistoryStat(t("Duration", "Tid"), Fmt.duration(a.durationS)),
                HistoryStat(t("Distance", "Distans"), Fmt.distance(a.distanceM, imperial)),
                HistoryStat(t("Avg power", "Snitteffekt"), a.avgW.toString(), "W"),
                HistoryStat(t("Max power", "Maxeffekt"), a.maxW.toString(), "W"),
            )
            a.npW?.let { stats.add(HistoryStat("NP", it.toString(), "W")) }
            a.intensityFactor?.let { stats.add(HistoryStat("IF", Fmt.two(it))) }
            a.tss?.let { stats.add(HistoryStat("TSS", it.roundToInt().toString())) }
            a.avgHr?.let { stats.add(HistoryStat(t("Avg HR", "Snittpuls"), it.toString(), "bpm")) }
            a.maxHr?.let { stats.add(HistoryStat(t("Max HR", "Maxpuls"), it.toString(), "bpm")) }
            a.avgCadence?.let { stats.add(HistoryStat(t("Avg cadence", "Snittkadens"), it.toString(), "rpm")) }
            stats.add(HistoryStat(t("Work", "Arbete"), a.kj.toString(), "kJ"))
            if (imperial) {
                stats.add(HistoryStat(t("Elevation", "Höjdmeter"), (a.elevationM * 3.28084).roundToInt().toString(), "ft"))
            } else {
                stats.add(HistoryStat(t("Elevation", "Höjdmeter"), a.elevationM.roundToInt().toString(), "m"))
            }
            StatFlow(stats)
        }

        // Charts
        SampleCharts(samples, a.ftpAtTime)

        // Zones
        if (summary != null) {
            SpinCard(Modifier.fillMaxWidth()) {
                Text(t("Time in power zones", "Tid i effektzoner"), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                val ftp = summary.ftp
                val ranges = (1..PowerZones.COUNT).map { z ->
                    val lo = (PowerZones.lowerBound(z) * ftp).roundToInt()
                    val hi = PowerZones.upperBound(z)?.let { (it * ftp).roundToInt() - 1 }
                    if (hi == null) "> $lo W" else "$lo–$hi W"
                }
                ZoneBreakdown(summary.powerZoneSeconds, SpinColors.powerZones, powerZoneNames(), ranges)
            }
            if (summary.hrZoneSeconds.sum() > 0) {
                SpinCard(Modifier.fillMaxWidth()) {
                    Text(t("Time in heart rate zones", "Tid i pulszoner"), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    val model = remember(profile.hrModel) {
                        runCatching { HrZoneModel.valueOf(profile.hrModel) }.getOrDefault(HrZoneModel.PERCENT_MAX)
                    }
                    val ranges = (1..HeartRateZones.COUNT).map { z ->
                        val lo = HeartRateZones.lowerBpm(z, profile.maxHr, profile.restHr, model)
                        if (z == HeartRateZones.COUNT) {
                            "> $lo bpm"
                        } else {
                            val hi = HeartRateZones.lowerBpm(z + 1, profile.maxHr, profile.restHr, model) - 1
                            "$lo–$hi bpm"
                        }
                    }
                    ZoneBreakdown(summary.hrZoneSeconds, SpinColors.hrZones, hrZoneNames(), ranges)
                }
            }

            // Best efforts
            val bestKeys = listOf(5, 60, 300, 1200, 3600).filter { summary.bests.containsKey(it) }
            if (bestKeys.isNotEmpty()) {
                SpinCard(Modifier.fillMaxWidth()) {
                    Text(t("Best efforts", "Bästa insatser"), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    for (d in bestKeys) {
                        val w = summary.bests[d] ?: 0
                        TableRow(
                            label = durationLabel(d),
                            values = listOf(
                                "$w W",
                                if (profile.weightKg > 0) "${Fmt.one(w / profile.weightKg)} W/kg" else "",
                            ),
                        )
                    }
                }
            }
        }

        // Feel + note
        SpinCard(Modifier.fillMaxWidth()) {
            Text(t("How did it feel?", "Hur kändes det?"), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (n in 1..5) {
                    FilterChip(
                        selected = a.feel == n,
                        onClick = {
                            val newFeel = if (a.feel == n) null else n
                            app.scope.launch { app.activities.update(a.copy(feel = newFeel)) }
                        },
                        label = { Text(n.toString()) },
                    )
                }
            }
            val feel = a.feel
            Text(
                if (feel != null) feelLabel(feel) else t("1 = very hard, 5 = great", "1 = mycket tungt, 5 = toppen"),
                style = MaterialTheme.typography.bodySmall,
                color = SpinColors.Muted,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(t("Note", "Anteckning")) },
                minLines = 2,
            )
            Spacer(Modifier.height(8.dp))
            val noteChanged = note.trim() != (a.note ?: "").trim()
            Button(
                onClick = {
                    val cleaned = note.trim().ifBlank { null }
                    app.scope.launch { app.activities.update(a.copy(note = cleaned)) }
                },
                enabled = noteChanged,
            ) { Text(if (noteChanged || a.note.isNullOrBlank()) t("Save note", "Spara anteckning") else t("Saved", "Sparad")) }
        }

        if (justFinished) {
            Button(onClick = { nav.home() }, modifier = Modifier.fillMaxWidth()) { Text(t("Done", "Klar")) }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun FtpCard(
    newFtp: Int,
    previousFtp: Int,
    prominent: Boolean,
    saved: Boolean,
    onSave: () -> Unit,
    onDismiss: (() -> Unit)?,
) {
    SpinCard(
        Modifier.fillMaxWidth(),
        color = if (prominent) SpinColors.Primary.copy(alpha = 0.18f) else SpinColors.SurfaceHigh,
    ) {
        Text(
            if (prominent) t("New FTP: $newFtp W", "Ny FTP: $newFtp W") else t("Suggested FTP: $newFtp W", "Föreslagen FTP: $newFtp W"),
            style = if (prominent) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
            color = if (prominent) SpinColors.Primary else SpinColors.OnSurface,
        )
        val diff = newFtp - previousFtp
        val sign = if (diff > 0) "+" else ""
        Text(
            t("Was $previousFtp W ($sign$diff W)", "Tidigare $previousFtp W ($sign$diff W)"),
            style = MaterialTheme.typography.bodyMedium,
            color = SpinColors.Muted,
        )
        if (!prominent) {
            Text(
                t("Based on your best 20-minute power on this ride.", "Baserat på din bästa 20-minuterseffekt under åkturen."),
                style = MaterialTheme.typography.bodySmall,
                color = SpinColors.Muted,
            )
        }
        Spacer(Modifier.height(12.dp))
        if (saved) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = SpinColors.Good)
                Spacer(Modifier.width(8.dp))
                Text(t("Saved", "Sparad"), color = SpinColors.Good, fontWeight = FontWeight.Bold)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onSave) { Text(t("Save as my FTP", "Spara som min FTP")) }
                if (onDismiss != null) {
                    TextButton(onClick = onDismiss) { Text(t("Not now", "Inte nu")) }
                }
            }
        }
    }
}

@Composable
private fun SampleCharts(samples: List<Sample>?, ftp: Int) {
    if (samples == null) {
        SpinCard(Modifier.fillMaxWidth()) {
            Text(t("Loading chart…", "Laddar diagram…"), color = SpinColors.Muted)
        }
        return
    }
    if (samples.size < 2) return
    val power = remember(samples) { downsample(samples.map { it.power.toFloat() }) }
    val hr = remember(samples) { downsample(samples.map { s -> s.hr?.takeIf { it > 0 }?.toFloat() }) }
    val cadence = remember(samples) { downsample(samples.map { s -> s.cadence?.takeIf { it > 0 }?.toFloat() }) }
    val hasHr = remember(hr) { hr.any { it != null } }
    val hasCadence = remember(cadence) { cadence.any { it != null } }
    val totalS = samples.size

    SpinCard(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(t("Power", "Effekt"), style = MaterialTheme.typography.titleMedium)
            LegendDot(SpinColors.Primary, "W")
            if (hasHr) LegendDot(SpinColors.Bad, t("Heart rate", "Puls"))
        }
        Spacer(Modifier.height(8.dp))
        val series = if (hasHr) listOf(power to SpinColors.Primary, hr to SpinColors.Bad) else listOf(power to SpinColors.Primary)
        SeriesChart(series = series, height = 180.dp, minY = 0f, fillFirst = true, guideY = ftp.toFloat())
        AxisLabels(start = "0:00", end = Fmt.duration(totalS))
    }
    if (hasCadence) {
        SpinCard(Modifier.fillMaxWidth()) {
            Text(t("Cadence", "Kadens"), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            SeriesChart(series = listOf(cadence to SpinColors.powerZones[1]), height = 120.dp, minY = 0f)
            AxisLabels(start = "0:00", end = Fmt.duration(totalS))
        }
    }
}

@Composable
private fun ZoneBreakdown(seconds: List<Int>, colors: List<Color>, names: List<String>, ranges: List<String>) {
    val total = seconds.sum()
    Column(Modifier.fillMaxWidth()) {
        if (total <= 0) {
            Text(t("No data", "Ingen data"), color = SpinColors.Muted)
        } else {
            StackedBar(seconds, colors)
            Spacer(Modifier.height(8.dp))
            seconds.forEachIndexed { i, s ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(colors[i % colors.size]))
                    Spacer(Modifier.width(8.dp))
                    Text("Z${i + 1}", modifier = Modifier.width(32.dp), fontWeight = FontWeight.Bold)
                    Text(
                        names.getOrElse(i) { "" },
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        ranges.getOrElse(i) { "" },
                        color = SpinColors.Muted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                    Text(Fmt.minutes(s), modifier = Modifier.width(80.dp), textAlign = TextAlign.End)
                    Text(
                        "${(s * 100f / total).roundToInt()}%",
                        modifier = Modifier.width(52.dp),
                        textAlign = TextAlign.End,
                        color = SpinColors.Muted,
                    )
                }
            }
        }
    }
}

@Composable
private fun powerZoneNames(): List<String> = listOf(
    t("Recovery", "Återhämtning"),
    t("Endurance", "Uthållighet"),
    t("Tempo", "Tempo"),
    t("Threshold", "Tröskel"),
    t("VO2max", "VO2max"),
    t("Anaerobic", "Anaerob"),
    t("Neuromuscular", "Neuromuskulär"),
)

@Composable
private fun hrZoneNames(): List<String> = listOf(
    t("Recovery", "Återhämtning"),
    t("Endurance", "Uthållighet"),
    t("Tempo", "Tempo"),
    t("Threshold", "Tröskel"),
    t("Maximum", "Max"),
)

@Composable
private fun feelLabel(n: Int): String = when (n) {
    1 -> t("Very hard", "Mycket tungt")
    2 -> t("Hard", "Tungt")
    3 -> t("OK", "Okej")
    4 -> t("Good", "Bra")
    else -> t("Great", "Toppen")
}

// ---------------------------------------------------------------------------------------------
// Trends
// ---------------------------------------------------------------------------------------------

private class HistoryTrendData(
    val weekStarts: List<LocalDate>,
    val weekHours: List<Float>,
    val weekTss: List<Float>,
    val recentBests: Map<Int, Int>,
    val allTimeBests: Map<Int, Int>,
    val fitness: List<FitnessModel.Day>,
)

@Composable
fun TrendsScreen(app: AppContainer, nav: Nav, profile: ProfileEntity) {
    val ftpFlow = remember(profile.id) { app.profiles.ftpHistory(profile.id) }
    val ftpHistory by ftpFlow.collectAsState(initial = emptyList())
    val activityFlow = remember(profile.id) { app.activities.observeCompletedFor(profile.id) }
    val activities by activityFlow.collectAsState(initial = null)
    val locale = rememberUiLocale()

    val trends by produceState<HistoryTrendData?>(null, activities) {
        val list = activities
        if (list != null) {
            value = withContext(Dispatchers.Default) { computeTrends(list, app.activities) }
        }
    }

    ScreenScaffold(title = t("Trends", "Trender"), onBack = nav::back) { pad ->
        val data = trends
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FtpHistoryCard(ftpHistory, profile.ftp, locale)
            if (data == null) {
                Text(t("Loading…", "Laddar…"), color = SpinColors.Muted)
            } else {
                FitnessCard(data.fitness)
                WeeklyVolumeCard(data, locale)
                PowerCurveCard(data, profile.weightKg)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun FtpHistoryCard(history: List<FtpEntryEntity>, currentFtp: Int, locale: Locale) {
    val sorted = remember(history) { history.sortedBy { it.dateMs } }
    SpinCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t("FTP history", "FTP-historik"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            StatValue(t("Current", "Nuvarande"), currentFtp.toString(), unit = "W", color = SpinColors.Primary)
        }
        if (sorted.size >= 2) {
            Spacer(Modifier.height(8.dp))
            val values = sorted.map { it.watts.toFloat() }
            val lo = (values.min() * 0.9f)
            val hi = (values.max() * 1.05f)
            SeriesChart(series = listOf(values to SpinColors.Primary), height = 140.dp, minY = lo, maxY = hi)
            AxisLabels(
                start = formatShortDate(sorted.first().dateMs, locale),
                end = formatShortDate(sorted.last().dateMs, locale),
            )
        }
        if (sorted.isEmpty()) {
            Text(t("No FTP changes yet.", "Inga FTP-ändringar än."), color = SpinColors.Muted)
        } else {
            Spacer(Modifier.height(8.dp))
            for (e in sorted.takeLast(8).reversed()) {
                TableRow(
                    label = formatShortDate(e.dateMs, locale),
                    values = listOf(ftpSourceLabel(e.source), "${e.watts} W"),
                )
            }
        }
    }
}

@Composable
private fun ftpSourceLabel(source: String): String = when (source) {
    "TEST" -> t("Test", "Test")
    "MANUAL" -> t("Manual", "Manuell")
    "ESTIMATE" -> t("Estimate", "Uppskattning")
    else -> source
}

@Composable
private fun FitnessCard(days: List<FitnessModel.Day>) {
    SpinCard(Modifier.fillMaxWidth()) {
        Text(t("Fitness & form (90 days)", "Kondition & form (90 dagar)"), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        val last = days.lastOrNull()
        val ctlColor = SpinColors.powerZones[1]
        val atlColor = SpinColors.Bad
        val tsbColor = SpinColors.Warn
        StatFlow(
            listOf(
                HistoryStat(t("Fitness (CTL)", "Kondition (CTL)"), (last?.ctl ?: 0.0).roundToInt().toString()),
                HistoryStat(t("Fatigue (ATL)", "Trötthet (ATL)"), (last?.atl ?: 0.0).roundToInt().toString()),
                HistoryStat(t("Form (TSB)", "Form (TSB)"), (last?.tsb ?: 0.0).roundToInt().toString()),
            ),
        )
        if (days.size >= 2 && days.any { it.ctl > 0.0 || it.atl > 0.0 }) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LegendDot(ctlColor, "CTL")
                LegendDot(atlColor, "ATL")
            }
            Spacer(Modifier.height(4.dp))
            val ctl = remember(days) { days.map { it.ctl.toFloat() } }
            val atl = remember(days) { days.map { it.atl.toFloat() } }
            val tsb = remember(days) { days.map { it.tsb.toFloat() } }
            SeriesChart(series = listOf(ctl to ctlColor, atl to atlColor), height = 140.dp, minY = 0f, fillFirst = true)
            Spacer(Modifier.height(12.dp))
            LegendDot(tsbColor, t("Form (TSB)", "Form (TSB)"))
            Spacer(Modifier.height(4.dp))
            SeriesChart(series = listOf(tsb to tsbColor), height = 100.dp, guideY = 0f)
            AxisLabels(start = t("90 days ago", "90 dagar sedan"), end = t("Today", "Idag"))
        } else {
            Spacer(Modifier.height(8.dp))
            Text(
                t("Ride with a power meter or smart trainer to build your fitness chart.", "Cykla med effektmätning för att bygga upp konditionsdiagrammet."),
                color = SpinColors.Muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun WeeklyVolumeCard(data: HistoryTrendData, locale: Locale) {
    val firstWeek = data.weekStarts.firstOrNull()
    val startLabel = if (firstWeek != null) formatShortDate(firstWeek, locale) else ""
    SpinCard(Modifier.fillMaxWidth()) {
        Text(t("Weekly volume (12 weeks)", "Veckovolym (12 veckor)"), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        val totalHours = data.weekHours.sum()
        val avgHours = if (data.weekHours.isNotEmpty()) totalHours / data.weekHours.size else 0f
        StatFlow(
            listOf(
                HistoryStat(t("Hours this week", "Timmar denna vecka"), Fmt.one((data.weekHours.lastOrNull() ?: 0f).toDouble()), "h"),
                HistoryStat(t("Avg hours / week", "Snitt timmar / vecka"), Fmt.one(avgHours.toDouble()), "h"),
                HistoryStat(t("TSS this week", "TSS denna vecka"), (data.weekTss.lastOrNull() ?: 0f).roundToInt().toString()),
            ),
        )
        Spacer(Modifier.height(12.dp))
        Text(t("Hours", "Timmar"), style = MaterialTheme.typography.labelMedium, color = SpinColors.Muted)
        BarChart(values = data.weekHours, color = SpinColors.Primary, height = 110.dp)
        AxisLabels(start = startLabel, end = t("This week", "Denna vecka"))
        Spacer(Modifier.height(12.dp))
        Text("TSS", style = MaterialTheme.typography.labelMedium, color = SpinColors.Muted)
        BarChart(values = data.weekTss, color = SpinColors.powerZones[1], height = 110.dp)
        AxisLabels(start = startLabel, end = t("This week", "Denna vecka"))
    }
}

@Composable
private fun PowerCurveCard(data: HistoryTrendData, weightKg: Double) {
    val durations = PowerAnalysis.BEST_DURATIONS.toList()
    val recentColor = SpinColors.Primary
    val allColor = SpinColors.powerZones[1]
    SpinCard(Modifier.fillMaxWidth()) {
        Text(t("Power curve", "Effektkurva"), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        if (data.allTimeBests.isEmpty()) {
            Text(t("No power data yet.", "Ingen effektdata än."), color = SpinColors.Muted)
        } else {
            PowerCurveContent(data, durations, recentColor, allColor, weightKg)
        }
    }
}

@Composable
private fun PowerCurveContent(
    data: HistoryTrendData,
    durations: List<Int>,
    recentColor: Color,
    allColor: Color,
    weightKg: Double,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LegendDot(recentColor, t("Last 42 days", "Senaste 42 dagarna"))
            LegendDot(allColor, t("All time", "Alla tider"))
        }
        Spacer(Modifier.height(4.dp))
        val recent = durations.map { data.recentBests[it]?.toFloat() }
        val all = durations.map { data.allTimeBests[it]?.toFloat() }
        SeriesChart(series = listOf(all to allColor, recent to recentColor), height = 160.dp, minY = 0f)
        AxisLabels(start = durationLabel(durations.first()), end = durationLabel(durations.last()))
        Spacer(Modifier.height(12.dp))
        TableRow(
            label = t("Duration", "Tid"),
            values = listOf(t("42 days", "42 dagar"), t("All time", "Alla tider")),
            header = true,
        )
        for (d in durations.filter { data.allTimeBests.containsKey(it) }) {
            val r = data.recentBests[d]
            val a = data.allTimeBests[d] ?: 0
            TableRow(
                label = durationLabel(d),
                values = listOf(
                    if (r != null) wattsText(r, weightKg) else "–",
                    wattsText(a, weightKg),
                ),
            )
        }
    }
}

private fun wattsText(w: Int, weightKg: Double): String =
    if (weightKg > 0) "$w W · ${Fmt.one(w / weightKg)} W/kg" else "$w W"

private fun computeTrends(list: List<ActivityEntity>, repo: ActivityRepository): HistoryTrendData {
    val today = LocalDate.now(ZoneId.systemDefault())
    val thisWeek = weekStart(today)
    val weekStarts = (11 downTo 0).map { thisWeek.minusWeeks(it.toLong()) }
    val hours = FloatArray(weekStarts.size)
    val tss = FloatArray(weekStarts.size)
    val daily = HashMap<Long, Double>()
    val recent = HashMap<Int, Int>()
    val allTime = HashMap<Int, Int>()
    val cutoff = today.minusDays(42)

    for (a in list) {
        val day = localDate(a.startedAt)
        val idx = weekStarts.indexOf(weekStart(day))
        if (idx >= 0) {
            hours[idx] += a.durationS / 3600f
            tss[idx] += (a.tss ?: 0.0).toFloat()
        }
        val activityTss = a.tss
        if (activityTss != null) {
            val key = day.toEpochDay()
            daily[key] = (daily[key] ?: 0.0) + activityTss
        }
        val bests: Map<Int, Int> = repo.summaryOf(a)?.bests
            ?: a.best20minW?.let { mapOf(1200 to it) }
            ?: emptyMap()
        val isRecent = !day.isBefore(cutoff)
        for ((duration, watts) in bests) {
            if (watts > (allTime[duration] ?: 0)) allTime[duration] = watts
            if (isRecent && watts > (recent[duration] ?: 0)) recent[duration] = watts
        }
    }

    val todayDay = today.toEpochDay()
    val fitness = FitnessModel.compute(daily, todayDay - 89, todayDay)
    return HistoryTrendData(weekStarts, hours.toList(), tss.toList(), recent, allTime, fitness)
}

// ---------------------------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------------------------

private data class HistoryStat(val label: String, val value: String, val unit: String? = null)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatFlow(stats: List<HistoryStat>, modifier: Modifier = Modifier, spacing: Dp = 24.dp) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        for (s in stats) {
            StatValue(s.label, s.value, modifier = Modifier.widthIn(min = 72.dp), unit = s.unit)
        }
    }
}

@Composable
private fun TableRow(label: String, values: List<String>, header: Boolean = false) {
    val color = if (header) SpinColors.Muted else SpinColors.OnSurface
    val weight = if (header) FontWeight.Normal else FontWeight.SemiBold
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), color = if (header) SpinColors.Muted else SpinColors.OnSurface)
        for (v in values) {
            Text(
                v,
                modifier = Modifier.weight(1.4f),
                textAlign = TextAlign.End,
                color = color,
                fontWeight = weight,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = SpinColors.Muted)
    }
}

@Composable
private fun AxisLabels(start: String, end: String) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(start, style = MaterialTheme.typography.labelSmall, color = SpinColors.Muted)
        Text(end, style = MaterialTheme.typography.labelSmall, color = SpinColors.Muted)
    }
}

@Composable
private fun rememberUiLocale(): Locale {
    val lang = LocalLanguage.current
    return remember(lang) { if (lang == "sv") Locale.forLanguageTag("sv-SE") else Locale.forLanguageTag("en-GB") }
}

@Composable
private fun dayHeading(date: LocalDate, locale: Locale): String {
    val today = LocalDate.now()
    return when (date) {
        today -> t("Today", "Idag")
        today.minusDays(1) -> t("Yesterday", "Igår")
        else -> date.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", locale))
            .replaceFirstChar { it.titlecase(locale) }
    }
}

/** Averages buckets so a series has at most [maxPoints] points. Nulls are skipped; all-null buckets stay null. */
private fun downsample(values: List<Float?>, maxPoints: Int = 600): List<Float?> {
    if (values.size <= maxPoints) return values
    val bucket = ceil(values.size / maxPoints.toDouble()).toInt()
    return values.chunked(bucket).map { chunk ->
        val present = chunk.filterNotNull()
        if (present.isEmpty()) null else present.average().toFloat()
    }
}

private fun durationLabel(seconds: Int): String = if (seconds < 60) "$seconds s" else "${seconds / 60} min"

private fun localDate(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()

private fun weekStart(d: LocalDate): LocalDate = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

private fun formatTime(ms: Long, locale: Locale): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm", locale))

private fun formatDateTime(ms: Long, locale: Locale): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", locale))
        .replaceFirstChar { it.titlecase(locale) }

private fun formatShortDate(ms: Long, locale: Locale): String = formatShortDate(localDate(ms), locale)

private fun formatShortDate(d: LocalDate, locale: Locale): String =
    d.format(DateTimeFormatter.ofPattern("d MMM yyyy", locale))
