package se.spinhaus.app.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.spinhaus.app.AppContainer
import se.spinhaus.app.data.ProfileEntity
import se.spinhaus.app.ui.Fmt
import se.spinhaus.app.ui.LocalLanguage
import se.spinhaus.app.ui.Nav
import se.spinhaus.app.ui.Routes
import se.spinhaus.app.ui.categoryName
import se.spinhaus.app.ui.components.CenteredMessage
import se.spinhaus.app.ui.components.ScreenScaffold
import se.spinhaus.app.ui.components.SectionTitle
import se.spinhaus.app.ui.components.SpinCard
import se.spinhaus.app.ui.components.StatValue
import se.spinhaus.app.ui.components.WorkoutChart
import se.spinhaus.app.ui.t
import se.spinhaus.app.ui.theme.SpinColors
import se.spinhaus.core.ride.RideMode
import se.spinhaus.core.workout.ExpandedWorkout
import se.spinhaus.core.workout.FtpTestKind
import se.spinhaus.core.workout.Segment
import se.spinhaus.core.workout.Step
import se.spinhaus.core.workout.StepKind
import se.spinhaus.core.workout.Workout
import se.spinhaus.core.workout.WorkoutCategory
import se.spinhaus.core.workout.WorkoutImporter
import se.spinhaus.core.workout.WorkoutSource
import se.spinhaus.core.workout.WorkoutStats
import se.spinhaus.core.workout.ZwoWriter
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt
import androidx.compose.foundation.lazy.grid.items as gridItems

// ---------------------------------------------------------------------------------------------
// Library
// ---------------------------------------------------------------------------------------------

@Composable
fun LibraryScreen(app: AppContainer, nav: Nav, profile: ProfileEntity, initialCategory: String?) {
    val lang = LocalLanguage.current
    val workoutsFlow = remember { app.workouts.observeAll() }
    val all by workoutsFlow.collectAsState(initial = null)
    var selectedName by rememberSaveable {
        mutableStateOf(initialCategory?.let { n -> WorkoutCategory.entries.firstOrNull { it.name == n }?.name })
    }
    var query by rememberSaveable { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val msgImportFailed = t("Could not import", "Kunde inte importera")
    val msgImportedWarnings = t("Imported with warnings", "Importerat med varningar")
    val msgOpen = t("Open", "Öppna")
    val msgUnknownFile = t("workout", "pass")

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                try {
                    val imported = withContext(Dispatchers.IO) {
                        val resolver = app.context.contentResolver
                        val name = runCatching {
                            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                                if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
                            }
                        }.getOrNull() ?: uri.lastPathSegment ?: msgUnknownFile
                        val text = resolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                            ?: throw IllegalStateException("Empty file")
                        val result = WorkoutImporter.import(name, text)
                        val w = if (result.workout.source == WorkoutSource.BUILT_IN) {
                            result.workout.copy(source = WorkoutSource.IMPORTED)
                        } else result.workout
                        Pair(w, result.warnings)
                    }
                    val workout = imported.first
                    val warnings = imported.second
                    app.workouts.save(workout)
                    if (warnings.isEmpty()) {
                        nav.go(Routes.workout(workout.id))
                    } else {
                        val res = snackbar.showSnackbar(
                            message = msgImportedWarnings + ": " + warnings.take(3).joinToString("; "),
                            actionLabel = msgOpen,
                            withDismissAction = true,
                            duration = SnackbarDuration.Long,
                        )
                        if (res == SnackbarResult.ActionPerformed) nav.go(Routes.workout(workout.id))
                    }
                } catch (e: Exception) {
                    snackbar.showSnackbar(
                        message = msgImportFailed + ": " + (e.message ?: e.javaClass.simpleName),
                        withDismissAction = true,
                        duration = SnackbarDuration.Long,
                    )
                }
            }
        }
    }

    ScreenScaffold(
        title = t("Workouts", "Pass"),
        onBack = nav::back,
        snackbar = snackbar,
        actions = {
            TextButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                Icon(Icons.Filled.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(t("Import", "Importera"))
            }
            TextButton(onClick = { nav.go(Routes.editor(null)) }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(t("New", "Nytt"))
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text(t("Search workouts", "Sök pass")) },
            )
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    FilterChip(
                        selected = selectedName == null,
                        onClick = { selectedName = null },
                        label = { Text(t("All", "Alla")) },
                    )
                }
                items(WorkoutCategory.entries.toList()) { c ->
                    FilterChip(
                        selected = selectedName == c.name,
                        onClick = { selectedName = if (selectedName == c.name) null else c.name },
                        label = { Text(categoryName(c, lang)) },
                    )
                }
            }

            val list = all
            if (list == null) {
                CenteredMessage(t("Loading…", "Laddar…"))
            } else {
                val q = query.trim()
                val filtered = list.filter { w ->
                    (selectedName == null || w.category.name == selectedName) &&
                        (q.isEmpty() || w.displayName(lang).contains(q, ignoreCase = true))
                }
                if (filtered.isEmpty()) {
                    CenteredMessage(t("No workouts found", "Inga pass hittades"))
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(320.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        gridItems(filtered, key = { it.id }) { w ->
                            WorkoutCard(w, profile.ftp, lang) { nav.go(Routes.workout(w.id)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkoutCard(w: Workout, ftp: Int, lang: String, onClick: () -> Unit) {
    val steps = remember(w) { ExpandedWorkout.of(w).steps }
    val est = remember(w) { WorkoutStats.estimate(w) }
    SpinCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                w.displayName(lang),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            SourceBadge(w.source)
        }
        Text(
            categoryName(w.category, lang),
            style = MaterialTheme.typography.bodySmall,
            color = SpinColors.Muted,
        )
        Spacer(Modifier.height(8.dp))
        WorkoutChart(steps = steps, ftp = ftp, absolute = w.absolutePower, height = 48.dp)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(Fmt.minutes(est.durationS), style = MaterialTheme.typography.bodyMedium)
            if (est.tss > 0) {
                Text("TSS ${est.tss.roundToInt()}", style = MaterialTheme.typography.bodyMedium, color = SpinColors.Muted)
                Text("IF ${Fmt.two(est.intensityFactor)}", style = MaterialTheme.typography.bodyMedium, color = SpinColors.Muted)
            }
        }
    }
}

@Composable
private fun SourceBadge(source: WorkoutSource) {
    val label = when (source) {
        WorkoutSource.BUILT_IN -> return
        WorkoutSource.CUSTOM -> t("Custom", "Eget")
        WorkoutSource.IMPORTED -> t("Imported", "Importerat")
    }
    val color = if (source == WorkoutSource.CUSTOM) SpinColors.Primary else SpinColors.Good
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.18f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

// ---------------------------------------------------------------------------------------------
// Detail
// ---------------------------------------------------------------------------------------------

@Composable
fun WorkoutDetailScreen(app: AppContainer, nav: Nav, profile: ProfileEntity, workoutId: String) {
    val lang = LocalLanguage.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var workout by remember { mutableStateOf<Workout?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(workoutId) {
        workout = app.workouts.get(workoutId)
        loaded = true
    }

    val copySuffix = t(" (copy)", " (kopia)")
    val shareTitle = t("Share workout", "Dela pass")
    val shareFailed = t("Could not share", "Kunde inte dela")

    val w = workout
    ScreenScaffold(
        title = w?.displayName(lang) ?: t("Workout", "Pass"),
        onBack = nav::back,
        snackbar = snackbar,
    ) { padding ->
        if (w == null) {
            Box(Modifier.padding(padding).fillMaxSize()) {
                CenteredMessage(if (loaded) t("Workout not found", "Passet hittades inte") else t("Loading…", "Laddar…"))
            }
            return@ScreenScaffold
        }
        val expanded = remember(w) { ExpandedWorkout.of(w) }
        val est = remember(w) { WorkoutStats.estimate(w) }

        val onStart = {
            nav.startRide(profile, RideMode.Structured(w), w.displayName(lang), if (w.ftpTest != null) "TEST" else "WORKOUT")
        }
        val onDuplicate: () -> Unit = {
            val newId = "cus-" + UUID.randomUUID().toString()
            val copy = w.copy(
                id = newId,
                name = w.displayName(lang) + copySuffix,
                description = w.displayDescription(lang),
                localizedNames = emptyMap(),
                localizedDescriptions = emptyMap(),
                source = WorkoutSource.CUSTOM,
                ftpTest = null,
                category = if (w.category == WorkoutCategory.FTP_TEST) WorkoutCategory.CUSTOM else w.category,
            )
            scope.launch {
                app.workouts.save(copy)
                nav.go(Routes.editor(newId))
            }
        }
        val onShare: () -> Unit = {
            scope.launch {
                try {
                    val file = withContext(Dispatchers.IO) {
                        val dir = File(app.context.cacheDir, "exports")
                        dir.mkdirs()
                        val f = File(dir, safeFileName(w.displayName(lang)) + ".zwo")
                        f.writeText(ZwoWriter.write(w), Charsets.UTF_8)
                        f
                    }
                    shareFile(context, app.context, file, shareTitle)
                } catch (e: Exception) {
                    snackbar.showSnackbar(shareFailed + ": " + (e.message ?: e.javaClass.simpleName))
                }
            }
        }

        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            if (maxWidth >= 840.dp) {
                Row(Modifier.fillMaxSize()) {
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item { DetailHeader(w, expanded, est, profile.ftp, lang) }
                        item {
                            DetailActions(
                                w = w, onStart = onStart, onDuplicate = onDuplicate,
                                onEdit = { nav.go(Routes.editor(w.id)) }, onDelete = { confirmDelete = true }, onShare = onShare,
                            )
                        }
                    }
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        contentPadding = PaddingValues(16.dp),
                    ) {
                        item { SectionTitle(t("Steps", "Steg")) }
                        items(expanded.steps) { s -> StepRow(s, expanded, profile.ftp) }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item { DetailHeader(w, expanded, est, profile.ftp, lang) }
                    item {
                        DetailActions(
                            w = w, onStart = onStart, onDuplicate = onDuplicate,
                            onEdit = { nav.go(Routes.editor(w.id)) }, onDelete = { confirmDelete = true }, onShare = onShare,
                        )
                    }
                    item { SectionTitle(t("Steps", "Steg")) }
                    items(expanded.steps) { s -> StepRow(s, expanded, profile.ftp) }
                }
            }
        }

        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text(t("Delete workout?", "Ta bort pass?")) },
                text = { Text(t("\"${w.displayName(lang)}\" will be removed permanently.", "\"${w.displayName(lang)}\" tas bort permanent.")) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDelete = false
                        scope.launch {
                            app.workouts.delete(w.id)
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
}

@Composable
private fun DetailHeader(w: Workout, expanded: ExpandedWorkout, est: WorkoutStats.Estimate, ftp: Int, lang: String) {
    SpinCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                w.displayName(lang),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            SourceBadge(w.source)
        }
        Text(categoryName(w.category, lang), style = MaterialTheme.typography.bodyMedium, color = SpinColors.Muted)
        val desc = w.displayDescription(lang)
        if (desc.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(desc, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            StatValue(t("Duration", "Tid"), Fmt.minutes(est.durationS))
            if (est.tss > 0) {
                StatValue(t("Est. TSS", "Ber. TSS"), est.tss.roundToInt().toString())
                StatValue(t("Est. IF", "Ber. IF"), Fmt.two(est.intensityFactor))
            }
            StatValue("FTP", ftp.toString(), unit = "W")
        }
        Spacer(Modifier.height(12.dp))
        WorkoutChart(steps = expanded.steps, ftp = ftp, absolute = w.absolutePower, height = 160.dp)
        val kind = w.ftpTest
        if (kind != null) {
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SpinColors.Warn.copy(alpha = 0.12f))
                    .padding(12.dp),
            ) {
                Text(ftpTestNote(kind), style = MaterialTheme.typography.bodyMedium, color = SpinColors.OnSurface)
            }
        }
    }
}

@Composable
private fun ftpTestNote(kind: FtpTestKind): String = when (kind) {
    FtpTestKind.RAMP, FtpTestKind.RAMP_SHORT -> t(
        "FTP test: the trainer raises the power every minute. Keep pedalling until you can't hold the target – the test ends automatically when your cadence drops. Your new FTP (75 % of your best minute) is calculated afterwards and you can choose to save it.",
        "FTP-test: trainern höjer effekten varje minut. Trampa tills du inte orkar hålla målet – testet avslutas automatiskt när kadensen sjunker. Din nya FTP (75 % av din bästa minut) räknas ut efteråt och du kan välja att spara den.",
    )
    FtpTestKind.TWENTY_MIN -> t(
        "FTP test: during the all-out blocks ERG is off – use your gears and pace yourself evenly. Your new FTP (95 % of the 20-minute average) is calculated afterwards and you can choose to save it.",
        "FTP-test: under maxblocken är ERG av – använd växlarna och fördela kraften jämnt. Din nya FTP (95 % av snittet under 20 minuter) räknas ut efteråt och du kan välja att spara den.",
    )
    FtpTestKind.TWO_BY_EIGHT -> t(
        "FTP test: during the two all-out blocks ERG is off – use your gears. Your new FTP (90 % of the average of both efforts) is calculated afterwards and you can choose to save it.",
        "FTP-test: under de två maxblocken är ERG av – använd växlarna. Din nya FTP (90 % av snittet av båda) räknas ut efteråt och du kan välja att spara den.",
    )
}

@Composable
private fun DetailActions(
    w: Workout,
    onStart: () -> Unit,
    onDuplicate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onStart,
            enabled = w.segments.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(64.dp),
            colors = ButtonDefaults.buttonColors(containerColor = SpinColors.Primary, contentColor = Color.Black),
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(8.dp))
            Text(t("Start", "Starta"), style = MaterialTheme.typography.titleLarge)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onDuplicate, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(t("Duplicate & edit", "Kopiera & redigera"), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(t("Share .zwo", "Dela .zwo"), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (w.source != WorkoutSource.BUILT_IN) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(t("Edit", "Redigera"))
                }
                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = SpinColors.Bad),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(t("Delete", "Ta bort"))
                }
            }
        }
    }
}

@Composable
private fun StepRow(s: Step, expanded: ExpandedWorkout, ftp: Int) {
    val absolute = expanded.workout.absolutePower
    fun pct(v: Double): String = if (absolute) "" else " (${(v * 100).roundToInt()} %)"
    val target = when (s.kind) {
        StepKind.FREE -> t("Free ride", "Fri cykling")
        StepKind.MAX -> {
            val g = s.from
            if (g != null) "Max (≈ ${expanded.toWatts(g, ftp)} W)" else "Max"
        }
        StepKind.RAMP -> {
            val a = s.from ?: 0.0
            val b = s.to ?: a
            "${expanded.toWatts(a, ftp)}→${expanded.toWatts(b, ftp)} W"
        }
        StepKind.STEADY -> {
            val a = s.from ?: 0.0
            "${expanded.toWatts(a, ftp)} W" + pct(a)
        }
    }
    val repeatLabel = if (s.repeat != null && s.repeatCount != null) {
        (if (s.isWork) t("On", "På") else t("Off", "Vila")) + " ${s.repeat}/${s.repeatCount}"
    } else null
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${Fmt.duration(s.startS)}–${Fmt.duration(s.endS)}",
                style = MaterialTheme.typography.bodyMedium,
                color = SpinColors.Muted,
                modifier = Modifier.width(120.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(target, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                val extra = listOfNotNull(
                    repeatLabel,
                    Fmt.duration(s.durationS),
                    s.cadence?.let { "$it rpm" },
                ).joinToString(" · ")
                Text(extra, style = MaterialTheme.typography.bodySmall, color = SpinColors.Muted)
            }
        }
        HorizontalDivider(color = SpinColors.Outline)
    }
}

private fun safeFileName(name: String): String =
    name.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_', '.').take(60).ifBlank { "workout" }

private fun shareFile(launchContext: Context, appContext: Context, file: File, chooserTitle: String) {
    val uri = FileProvider.getUriForFile(appContext, appContext.packageName + ".files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/xml"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, file.name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(send, chooserTitle)
    if (launchContext !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    launchContext.startActivity(chooser)
}

// ---------------------------------------------------------------------------------------------
// Editor
// ---------------------------------------------------------------------------------------------

/** A segment with a stable key so text-field state survives reordering. */
private data class EditorItem(val key: Long, val seg: Segment)

@Composable
fun EditorScreen(app: AppContainer, nav: Nav, profile: ProfileEntity, workoutId: String?) {
    val lang = LocalLanguage.current
    val scope = rememberCoroutineScope()

    var original by remember { mutableStateOf<Workout?>(null) }
    var loaded by remember { mutableStateOf(workoutId == null) }
    var notFound by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(WorkoutCategory.CUSTOM) }
    var absolute by remember { mutableStateOf(false) }
    var nextKey by remember { mutableStateOf(1L) }
    val segItems = remember { mutableStateListOf<EditorItem>() }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(workoutId) {
        if (workoutId != null) {
            val w = app.workouts.get(workoutId)
            if (w == null) {
                notFound = true
            } else {
                original = w
                name = w.displayName(lang)
                description = w.displayDescription(lang)
                category = w.category
                absolute = w.absolutePower
                segItems.clear()
                var k = 1L
                for (s in w.segments) {
                    segItems.add(EditorItem(k, s))
                    k++
                }
                nextKey = k
            }
            loaded = true
        }
    }

    fun newKey(): Long {
        val k = nextKey
        nextKey = k + 1
        return k
    }

    fun update(key: Long, seg: Segment) {
        val i = segItems.indexOfFirst { it.key == key }
        if (i >= 0) segItems[i] = segItems[i].copy(seg = seg)
    }

    fun move(key: Long, delta: Int) {
        val i = segItems.indexOfFirst { it.key == key }
        val j = i + delta
        if (i >= 0 && j >= 0 && j < segItems.size) {
            val a = segItems[i]
            segItems[i] = segItems[j]
            segItems[j] = a
        }
    }

    fun duplicate(key: Long) {
        val i = segItems.indexOfFirst { it.key == key }
        if (i >= 0) segItems.add(i + 1, EditorItem(newKey(), segItems[i].seg))
    }

    fun remove(key: Long) {
        val i = segItems.indexOfFirst { it.key == key }
        if (i >= 0) segItems.removeAt(i)
    }

    fun power(fraction: Double): Double =
        if (absolute) (fraction * profile.ftp).roundToInt().toDouble() else fraction

    fun addSegment(type: String) {
        val seg: Segment = when (type) {
            "ramp" -> Segment.Ramp(600, power(0.50), power(0.75))
            "intervals" -> Segment.Intervals(5, 180, power(1.05), 180, power(0.55))
            "free" -> Segment.Free(600)
            "max" -> Segment.MaxEffort(30)
            else -> Segment.Steady(300, power(0.75))
        }
        segItems.add(EditorItem(newKey(), seg))
    }

    val isNew = original == null || original?.source == WorkoutSource.BUILT_IN
    val canSave = loaded && !notFound && !saving && name.isNotBlank() && segItems.isNotEmpty()

    fun buildWorkout(): Workout {
        val segs = segItems.map { it.seg }
        val o = original
        return if (o != null && o.source != WorkoutSource.BUILT_IN) {
            o.copy(
                name = name.trim(),
                description = description.trim(),
                category = category,
                segments = segs,
                absolutePower = absolute,
                localizedNames = emptyMap(),
                localizedDescriptions = emptyMap(),
            )
        } else {
            Workout(
                id = "cus-" + UUID.randomUUID().toString(),
                name = name.trim(),
                description = description.trim(),
                category = category,
                segments = segs,
                source = WorkoutSource.CUSTOM,
                absolutePower = absolute,
            )
        }
    }

    ScreenScaffold(
        title = if (isNew) t("New workout", "Nytt pass") else t("Edit workout", "Redigera pass"),
        onBack = nav::back,
        actions = {
            TextButton(
                enabled = canSave,
                onClick = {
                    saving = true
                    val w = buildWorkout()
                    scope.launch {
                        app.workouts.save(w)
                        nav.back()
                    }
                },
            ) {
                Icon(Icons.Filled.Check, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(t("Save", "Spara"))
            }
        },
    ) { padding ->
        if (!loaded) {
            Box(Modifier.padding(padding).fillMaxSize()) { CenteredMessage(t("Loading…", "Laddar…")) }
            return@ScreenScaffold
        }
        if (notFound) {
            Box(Modifier.padding(padding).fillMaxSize()) { CenteredMessage(t("Workout not found", "Passet hittades inte")) }
            return@ScreenScaffold
        }

        val segments = segItems.map { it.seg }
        val preview = Workout(id = "preview", name = name, segments = segments, absolutePower = absolute)
        val steps = ExpandedWorkout.of(preview).steps
        val est = WorkoutStats.estimate(preview)

        val previewContent: @Composable () -> Unit = {
            EditorPreview(steps, est, profile.ftp, absolute)
        }
        val headerContent: @Composable () -> Unit = {
            EditorHeader(
                name = name, onName = { name = it },
                description = description, onDescription = { description = it },
                category = category, onCategory = { category = it },
                lang = lang,
            )
        }
        val addContent: @Composable () -> Unit = {
            AddBlockButton(onAdd = { addSegment(it) })
        }

        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            val wide = maxWidth >= 840.dp
            Row(Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.weight(if (wide) 1.2f else 1f).fillMaxHeight(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "header") { headerContent() }
                    if (!wide) item(key = "preview") { previewContent() }
                    item(key = "blocks-title") { SectionTitle(t("Blocks", "Block")) }
                    if (segItems.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                t("No blocks yet. Add a block below.", "Inga block ännu. Lägg till ett block nedan."),
                                color = SpinColors.Muted,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    items(segItems, key = { it.key }) { entry ->
                        val index = segItems.indexOfFirst { it.key == entry.key }
                        SegmentCard(
                            seg = entry.seg,
                            absolute = absolute,
                            canMoveUp = index > 0,
                            canMoveDown = index >= 0 && index < segItems.size - 1,
                            onChange = { update(entry.key, it) },
                            onUp = { move(entry.key, -1) },
                            onDown = { move(entry.key, 1) },
                            onDuplicate = { duplicate(entry.key) },
                            onDelete = { remove(entry.key) },
                        )
                    }
                    item(key = "add") { addContent() }
                    item(key = "bottom") { Spacer(Modifier.height(48.dp)) }
                }
                if (wide) {
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                    ) {
                        previewContent()
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorPreview(steps: List<Step>, est: WorkoutStats.Estimate, ftp: Int, absolute: Boolean) {
    SpinCard(Modifier.fillMaxWidth()) {
        Text(t("Preview", "Förhandsvisning"), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        if (steps.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                Text(t("Add blocks to see the profile", "Lägg till block för att se profilen"), color = SpinColors.Muted)
            }
        } else {
            WorkoutChart(steps = steps, ftp = ftp, absolute = absolute, height = 120.dp)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            StatValue(t("Duration", "Tid"), Fmt.duration(est.durationS))
            if (est.tss > 0) {
                StatValue(t("Est. TSS", "Ber. TSS"), est.tss.roundToInt().toString())
                StatValue(t("Est. IF", "Ber. IF"), Fmt.two(est.intensityFactor))
            }
        }
        if (absolute) {
            Spacer(Modifier.height(8.dp))
            Text(
                t("This workout uses absolute watts.", "Det här passet använder absoluta watt."),
                style = MaterialTheme.typography.bodySmall,
                color = SpinColors.Muted,
            )
        }
    }
}

@Composable
private fun EditorHeader(
    name: String,
    onName: (String) -> Unit,
    description: String,
    onDescription: (String) -> Unit,
    category: WorkoutCategory,
    onCategory: (WorkoutCategory) -> Unit,
    lang: String,
) {
    var menuOpen by remember { mutableStateOf(false) }
    SpinCard(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = name,
            onValueChange = onName,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(t("Name", "Namn")) },
            isError = name.isBlank(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = description,
            onValueChange = onDescription,
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 5,
            label = { Text(t("Description", "Beskrivning")) },
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t("Category", "Kategori"), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Box {
                OutlinedButton(onClick = { menuOpen = true }) {
                    Text(categoryName(category, lang))
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    WorkoutCategory.entries
                        .filter { it != WorkoutCategory.FTP_TEST || it == category }
                        .forEach { c ->
                            DropdownMenuItem(
                                text = { Text(categoryName(c, lang)) },
                                onClick = {
                                    onCategory(c)
                                    menuOpen = false
                                },
                            )
                        }
                }
            }
        }
    }
}

@Composable
private fun AddBlockButton(onAdd: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Button(onClick = { open = true }) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(t("Add block", "Lägg till block"))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(t("Steady", "Jämn")) }, onClick = { open = false; onAdd("steady") })
            DropdownMenuItem(text = { Text(t("Ramp", "Ramp")) }, onClick = { open = false; onAdd("ramp") })
            DropdownMenuItem(text = { Text(t("Intervals", "Intervaller")) }, onClick = { open = false; onAdd("intervals") })
            DropdownMenuItem(text = { Text(t("Free ride", "Fri cykling")) }, onClick = { open = false; onAdd("free") })
            DropdownMenuItem(text = { Text(t("Max effort", "Maxinsats")) }, onClick = { open = false; onAdd("max") })
        }
    }
}

@Composable
private fun SegmentCard(
    seg: Segment,
    absolute: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onChange: (Segment) -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    val typeLabel = when (seg) {
        is Segment.Steady -> t("Steady", "Jämn")
        is Segment.Ramp -> t("Ramp", "Ramp")
        is Segment.Intervals -> t("Intervals", "Intervaller")
        is Segment.Free -> t("Free ride", "Fri cykling")
        is Segment.MaxEffort -> t("Max effort", "Maxinsats")
    }
    val powerSuffix = if (absolute) "W" else "%"
    val labelPower = t("Power", "Effekt")
    val labelCadence = t("Cadence", "Kadens")
    SpinCard(Modifier.fillMaxWidth(), color = SpinColors.SurfaceHigh) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(typeLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(Fmt.duration(seg.durationS()), style = MaterialTheme.typography.bodySmall, color = SpinColors.Muted)
            }
            IconButton(onClick = onUp, enabled = canMoveUp) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = t("Move up", "Flytta upp"))
            }
            IconButton(onClick = onDown, enabled = canMoveDown) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = t("Move down", "Flytta ned"))
            }
            IconButton(onClick = onDuplicate) {
                Icon(Icons.Filled.ContentCopy, contentDescription = t("Duplicate", "Duplicera"))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = t("Delete", "Ta bort"), tint = SpinColors.Bad)
            }
        }
        Spacer(Modifier.height(8.dp))
        when (seg) {
            is Segment.Steady -> {
                DurationFields(seg.durationS) { onChange(seg.copy(durationS = it)) }
                FieldRow {
                    PowerField(labelPower, seg.power, absolute, powerSuffix, Modifier.weight(1f)) { onChange(seg.copy(power = it)) }
                    CadenceField(labelCadence, seg.cadence, Modifier.weight(1f)) { onChange(seg.copy(cadence = it)) }
                }
            }
            is Segment.Ramp -> {
                DurationFields(seg.durationS) { onChange(seg.copy(durationS = it)) }
                FieldRow {
                    PowerField(t("From", "Från"), seg.from, absolute, powerSuffix, Modifier.weight(1f)) { onChange(seg.copy(from = it)) }
                    PowerField(t("To", "Till"), seg.to, absolute, powerSuffix, Modifier.weight(1f)) { onChange(seg.copy(to = it)) }
                    CadenceField(labelCadence, seg.cadence, Modifier.weight(1f)) { onChange(seg.copy(cadence = it)) }
                }
            }
            is Segment.Intervals -> {
                FieldRow {
                    IntField(t("Repeat", "Antal"), seg.repeat, Modifier.weight(1f), suffix = "×") {
                        if (it != null) onChange(seg.copy(repeat = it.coerceIn(1, 100)))
                    }
                    CadenceField(t("On cadence", "Kadens på"), seg.onCadence, Modifier.weight(1f)) { onChange(seg.copy(onCadence = it)) }
                }
                FieldRow {
                    IntField(t("On", "På"), seg.onS, Modifier.weight(1f), suffix = "s") {
                        if (it != null) onChange(seg.copy(onS = it.coerceIn(1, 36000)))
                    }
                    PowerField(t("On power", "Effekt på"), seg.onPower, absolute, powerSuffix, Modifier.weight(1f)) { onChange(seg.copy(onPower = it)) }
                }
                FieldRow {
                    IntField(t("Off", "Vila"), seg.offS, Modifier.weight(1f), suffix = "s") {
                        if (it != null) onChange(seg.copy(offS = it.coerceIn(0, 36000)))
                    }
                    PowerField(t("Off power", "Effekt vila"), seg.offPower, absolute, powerSuffix, Modifier.weight(1f)) { onChange(seg.copy(offPower = it)) }
                }
            }
            is Segment.Free -> {
                DurationFields(seg.durationS) { onChange(seg.copy(durationS = it)) }
                FieldRow {
                    CadenceField(labelCadence, seg.cadence, Modifier.weight(1f)) { onChange(seg.copy(cadence = it)) }
                    Spacer(Modifier.weight(1f))
                }
            }
            is Segment.MaxEffort -> {
                DurationFields(seg.durationS) { onChange(seg.copy(durationS = it)) }
            }
        }
    }
}

@Composable
private fun FieldRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** Minutes + seconds editor for a duration in seconds (minimum 1 s). */
@Composable
private fun DurationFields(totalS: Int, onChange: (Int) -> Unit) {
    val minutes = totalS / 60
    val seconds = totalS % 60
    FieldRow {
        IntField(t("Minutes", "Minuter"), minutes, Modifier.weight(1f), suffix = "min") { m ->
            if (m != null) onChange((m.coerceIn(0, 600) * 60 + seconds).coerceAtLeast(1))
        }
        IntField(t("Seconds", "Sekunder"), seconds, Modifier.weight(1f), suffix = "s") { s ->
            if (s != null) onChange((minutes * 60 + s.coerceIn(0, 59)).coerceAtLeast(1))
        }
    }
}

/** Power as % of FTP (stored as fraction) or absolute watts. */
@Composable
private fun PowerField(
    label: String,
    value: Double,
    absolute: Boolean,
    suffix: String,
    modifier: Modifier,
    onChange: (Double) -> Unit,
) {
    val shown = if (absolute) value.roundToInt() else (value * 100).roundToInt()
    IntField(label, shown, modifier, suffix = suffix) { v ->
        if (v != null) {
            val clamped = if (absolute) v.coerceIn(0, 2500) else v.coerceIn(0, 300)
            onChange(if (absolute) clamped.toDouble() else clamped / 100.0)
        }
    }
}

/** Optional cadence; empty text = no target. */
@Composable
private fun CadenceField(label: String, value: Int?, modifier: Modifier, onChange: (Int?) -> Unit) {
    IntField(label, value, modifier, suffix = "rpm", allowEmpty = true) { v ->
        if (v == null) onChange(null) else if (v in 20..200) onChange(v)
    }
}

/**
 * Numeric text field with its own text state so partial input feels natural.
 * [onValue] is called with the parsed value on each valid change (null = emptied, only if [allowEmpty]).
 */
@Composable
private fun IntField(
    label: String,
    value: Int?,
    modifier: Modifier = Modifier,
    suffix: String? = null,
    allowEmpty: Boolean = false,
    onValue: (Int?) -> Unit,
) {
    var text by remember { mutableStateOf(value?.toString() ?: "") }
    LaunchedEffect(value) {
        val current = text.toIntOrNull()
        if (current != value && !(value == null && text.isEmpty())) {
            text = value?.toString() ?: ""
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            val digits = input.filter { it.isDigit() }.take(5)
            text = digits
            val parsed = digits.toIntOrNull()
            if (parsed != null) onValue(parsed) else if (digits.isEmpty() && allowEmpty) onValue(null)
        },
        modifier = modifier,
        singleLine = true,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        suffix = if (suffix != null) ({ Text(suffix) }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}
