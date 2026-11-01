package se.spinhaus.app.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import se.spinhaus.app.AppContainer
import se.spinhaus.app.data.DashboardConfig
import se.spinhaus.app.data.ProfileEntity
import se.spinhaus.app.data.ProfileSettings
import se.spinhaus.app.data.SoundEvent
import se.spinhaus.app.data.SoundMode
import se.spinhaus.app.data.TileSize
import se.spinhaus.app.data.TileSpec
import se.spinhaus.app.data.TileType
import se.spinhaus.app.data.Units
import se.spinhaus.app.data.settings
import se.spinhaus.app.data.withSettings
import se.spinhaus.app.ui.LocalLanguage
import se.spinhaus.app.ui.Nav
import se.spinhaus.app.ui.Routes
import se.spinhaus.app.ui.components.LabeledRow
import se.spinhaus.app.ui.components.ScreenScaffold
import se.spinhaus.app.ui.components.SectionTitle
import se.spinhaus.app.ui.components.SpinCard
import se.spinhaus.app.ui.components.UpdateBanner
import se.spinhaus.app.update.UpdateState
import se.spinhaus.app.ui.t
import se.spinhaus.app.ui.theme.SpinColors
import se.spinhaus.app.ui.tr
import se.spinhaus.core.ble.RideButton
import se.spinhaus.core.ride.ButtonBinding
import se.spinhaus.core.ride.ControlAction
import se.spinhaus.core.ride.ControllerMapping
import se.spinhaus.core.ride.GearTable
import kotlin.math.roundToInt

private fun AppContainer.saveSettings(profile: ProfileEntity, s: ProfileSettings) {
    scope.launch { profiles.update(profile.withSettings(s)) }
}

@Composable
fun SettingsScreen(app: AppContainer, nav: Nav, profile: ProfileEntity) {
    val s = profile.settings
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var confirmImport by remember { mutableStateOf<android.net.Uri?>(null) }
    val msgExported = t("Backup saved", "Backup sparad")
    val msgImported = t("Backup restored", "Backup återställd")
    val msgFailed = t("Failed", "Misslyckades")

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gzip")) { uri ->
        if (uri != null) app.scope.launch {
            val ok = runCatching { app.backup.export(uri) }.isSuccess
            snackbar.showSnackbar(if (ok) msgExported else msgFailed)
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) confirmImport = uri
    }

    ScreenScaffold(title = t("Settings", "Inställningar"), onBack = nav::back, snackbar = snackbar) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            SectionTitle(t("Profile – ${profile.name}", "Profil – ${profile.name}"))
            SpinCard {
                LabeledRow(t("Name, FTP, weight, heart rate, language", "Namn, FTP, vikt, puls, språk")) {
                    TextButton(onClick = { nav.go(Routes.profileEdit(profile.id)) }) { Text(t("Edit", "Redigera")) }
                }
                LabeledRow(t("Ride screen layout", "Layout för åkskärmen")) {
                    TextButton(onClick = { nav.go(Routes.DASHBOARD) }) { Text(t("Customise", "Anpassa")) }
                }
                LabeledRow(t("Handlebar buttons", "Knappar på styret")) {
                    TextButton(onClick = { nav.go(Routes.BUTTONS) }) { Text(t("Customise", "Anpassa")) }
                }
                LabeledRow(t("Units", "Enheter")) {
                    FilterChip(s.units == Units.METRIC, { app.saveSettings(profile, s.copy(units = Units.METRIC)) }, { Text("km") })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(s.units == Units.IMPERIAL, { app.saveSettings(profile, s.copy(units = Units.IMPERIAL)) }, { Text("mi") })
                }
                LabeledRow(t("Vibrate controllers when shifting", "Vibrera styrknapparna vid växling")) {
                    Switch(s.vibrateOnShift, { app.saveSettings(profile, s.copy(vibrateOnShift = it)) })
                }
            }

            SectionTitle(t("Sound", "Ljud"))
            SpinCard {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (m in SoundMode.entries) FilterChip(
                        s.sound.mode == m, { app.saveSettings(profile, s.copy(sound = s.sound.copy(mode = m))) },
                        { Text(when (m) { SoundMode.OFF -> t("Off", "Av"); SoundMode.BEEPS -> t("Beeps", "Pip"); SoundMode.VOICE -> t("Beeps + voice", "Pip + röst") }) },
                    )
                }
                if (s.sound.mode != SoundMode.OFF) {
                    var vol by remember(s.sound.volume) { mutableStateOf(s.sound.volume) }
                    LabeledRow(t("Volume", "Volym") + " ${(vol * 100).roundToInt()} %") {}
                    Slider(vol, { vol = it }, valueRange = 0.1f..1f, onValueChangeFinished = { app.saveSettings(profile, s.copy(sound = s.sound.copy(volume = vol))) })
                    for (e in SoundEvent.entries) {
                        LabeledRow(soundEventName(e)) {
                            Switch(e in s.sound.events, { on ->
                                val ev = if (on) s.sound.events + e else s.sound.events - e
                                app.saveSettings(profile, s.copy(sound = s.sound.copy(events = ev)))
                            })
                        }
                    }
                }
            }

            SectionTitle(t("Virtual shifting", "Virtuell växling"))
            SpinCard {
                Text(
                    t("The Zwift Ride has no cassette, so Spinhaus simulates 24 gears. Adjust how heavy the gears feel overall.",
                        "Zwift Ride saknar kassett, så Spinhaus simulerar 24 växlar. Justera hur tunga växlarna känns totalt."),
                    color = SpinColors.Muted,
                )
                var feel by remember(s.gears.feel) { mutableStateOf(s.gears.feel.toFloat()) }
                LabeledRow(t("Gear feel", "Växelkänsla") + " ×${"%.2f".format(feel)}") {
                    TextButton(onClick = { feel = 1f; app.saveSettings(profile, s.copy(gears = s.gears.copy(feel = 1.0))) }) { Text(t("Reset", "Återställ")) }
                }
                Slider(feel, { feel = it }, valueRange = 0.6f..1.6f, onValueChangeFinished = {
                    app.saveSettings(profile, s.copy(gears = s.gears.copy(feel = feel.toDouble())))
                })
                var neutral by remember(s.gears.neutralIndex) { mutableStateOf(s.gears.neutralIndex.toFloat()) }
                LabeledRow(t("Start gear", "Startväxel") + " ${neutral.roundToInt() + 1}") {
                    TextButton(onClick = { app.saveSettings(profile, s.copy(gears = GearTable())) }) { Text(t("Defaults", "Standard")) }
                }
                Slider(neutral, { neutral = it }, valueRange = 0f..23f, steps = 22, onValueChangeFinished = {
                    app.saveSettings(profile, s.copy(gears = s.gears.copy(neutralIndex = neutral.roundToInt())))
                })
            }

            SectionTitle(t("Data", "Data"))
            SpinCard {
                Text(
                    t("All data is stored on this tablet. Make a backup now and then (e.g. to Google Drive) so nothing is lost if the tablet breaks.",
                        "All data sparas på plattan. Gör en backup ibland (t.ex. till Google Drive) så att inget försvinner om plattan går sönder."),
                    color = SpinColors.Muted,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { exportLauncher.launch("spinhaus-backup-${java.time.LocalDate.now()}.json.gz") }) { Text(t("Create backup", "Skapa backup")) }
                    OutlinedButton(enabled = !app.ride.active, onClick = { importLauncher.launch(arrayOf("*/*")) }) { Text(t("Restore backup", "Återställ backup")) }
                }
            }

            SectionTitle(t("Troubleshooting", "Felsökning"))
            SpinCard {
                LabeledRow(t("Bluetooth log", "Bluetooth-logg")) {
                    TextButton(onClick = { nav.go(Routes.DIAGNOSTICS) }) { Text(t("Open", "Öppna")) }
                }
                LabeledRow(t("Reconnect all devices", "Återanslut alla enheter")) {
                    TextButton(onClick = { app.hub.reconnectAll() }) { Text(t("Reconnect", "Återanslut")) }
                }
            }

            SectionTitle(t("Updates", "Uppdateringar"))
            SpinCard {
                val upd by app.updates.state.collectAsState()
                LabeledRow("Spinhaus ${app.updates.installedVersionName}") {
                    TextButton(onClick = { app.updates.check(force = true) }) { Text(t("Check now", "Sök nu")) }
                }
                Text(
                    when (val u = upd) {
                        is UpdateState.Checking -> t("Checking…", "Söker…")
                        is UpdateState.UpToDate -> t("You have the latest version.", "Du har senaste versionen.")
                        is UpdateState.Failed -> t("Could not check: ${u.message}", "Kunde inte söka: ${u.message}")
                        is UpdateState.Available -> t("Version ${u.versionName} is available.", "Version ${u.versionName} finns.")
                        else -> t("The app checks automatically when it starts.", "Appen söker automatiskt när den startar.")
                    },
                    color = SpinColors.Muted,
                )
                Spacer(Modifier.height(8.dp))
                UpdateBanner(app)
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    confirmImport?.let { uri ->
        AlertDialog(
            onDismissRequest = { confirmImport = null },
            title = { Text(t("Restore backup?", "Återställ backup?")) },
            text = { Text(t("All current data on this tablet is replaced by the backup.", "All nuvarande data på plattan ersätts med backupen.")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmImport = null
                    app.scope.launch {
                        val ok = runCatching { app.backup.import(uri) }.isSuccess
                        snackbar.showSnackbar(if (ok) msgImported else msgFailed)
                        if (ok) app.selectProfile(app.profiles.all().firstOrNull()?.id)
                    }
                }) { Text(t("Restore", "Återställ")) }
            },
            dismissButton = { TextButton(onClick = { confirmImport = null }) { Text(t("Cancel", "Avbryt")) } },
        )
    }
}

@Composable
private fun soundEventName(e: SoundEvent): String = when (e) {
    SoundEvent.INTERVAL_CHANGE -> t("Interval change", "Intervallbyte")
    SoundEvent.COUNTDOWN -> t("Countdown 3-2-1", "Nedräkning 3-2-1")
    SoundEvent.HALFWAY -> t("Halfway", "Halvvägs")
    SoundEvent.WORKOUT_DONE -> t("Workout complete / goal reached", "Pass klart / mål nått")
    SoundEvent.CONNECTION_LOST -> t("Connection lost", "Tappad anslutning")
    SoundEvent.LOW_CADENCE -> t("Cadence too low", "För låg kadens")
    SoundEvent.TEXT_MESSAGES -> t("Read workout messages", "Läs upp meddelanden i passet")
    SoundEvent.SHIFT_CLICK -> t("Click when shifting", "Klick vid växling")
}

@Composable
fun tileName(type: TileType): String = when (type) {
    TileType.POWER_3S -> t("Power (3 s)", "Effekt (3 s)")
    TileType.POWER -> t("Power (instant)", "Effekt (momentan)")
    TileType.TARGET -> t("Target", "Mål")
    TileType.PERCENT_OF_TARGET -> t("% of target", "% av mål")
    TileType.HEART_RATE -> t("Heart rate", "Puls")
    TileType.HR_ZONE -> t("HR zone", "Pulszon")
    TileType.CADENCE -> t("Cadence", "Kadens")
    TileType.LAP_TIME -> t("Lap time", "Varvtid")
    TileType.TOTAL_TIME -> t("Total time", "Total tid")
    TileType.INTERVAL_REMAINING -> t("Interval time left", "Tid kvar i intervall")
    TileType.NEXT_INTERVAL -> t("Next interval", "Nästa intervall")
    TileType.SPEED -> t("Speed", "Fart")
    TileType.DISTANCE -> t("Distance", "Distans")
    TileType.GRADE -> t("Grade", "Lutning")
    TileType.GEAR -> t("Gear", "Växel")
    TileType.NORMALIZED_POWER -> t("Normalized power", "Normaliserad effekt")
    TileType.AVG_POWER -> t("Average power", "Snittwatt")
    TileType.KJ -> "kJ"
    TileType.KCAL -> "kcal"
    TileType.WATTS_PER_KG -> "W/kg"
    TileType.TSS -> "TSS"
    TileType.CLOCK -> t("Clock", "Klocka")
    TileType.AVG_HR -> t("Average HR", "Snittpuls")
    TileType.MAX_POWER -> t("Max power", "Maxeffekt")
}

@Composable
fun DashboardEditorScreen(app: AppContainer, nav: Nav, profile: ProfileEntity) {
    val s = profile.settings
    val pages = remember { mutableStateListOf<List<TileSpec>>().apply { addAll(s.dashboard.pages.ifEmpty { DashboardConfig.standard }) } }
    var pageIndex by remember { mutableStateOf(0) }
    var addOpen by remember { mutableStateOf(false) }

    fun save() = app.saveSettings(profile, s.copy(dashboard = DashboardConfig(pages.toList())))
    fun updatePage(list: List<TileSpec>) { pages[pageIndex] = list; save() }

    ScreenScaffold(title = t("Ride screen layout", "Layout för åkskärmen"), onBack = nav::back) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp)) {
            Text(t("Presets", "Förval"), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                for ((label, preset) in listOf(
                    t("Minimal", "Minimal") to DashboardConfig.minimal,
                    t("Standard", "Standard") to DashboardConfig.standard,
                    t("Data nerd", "Datanörd") to DashboardConfig.nerd,
                )) OutlinedButton(onClick = { pages.clear(); pages.addAll(preset); pageIndex = 0; save() }) { Text(label) }
            }
            Text(
                t("Pages are switched with ◀ ▶ on the left controller. Large tiles take 2×2 slots, medium 2×1, small 1×1.",
                    "Sidor byts med ◀ ▶ på vänster styrknapp. Stora rutor tar 2×2 platser, mellan 2×1, små 1×1."),
                color = SpinColors.Muted, style = MaterialTheme.typography.bodySmall,
            )
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                pages.indices.forEach { i -> FilterChip(pageIndex == i, { pageIndex = i }, { Text(t("Page ${i + 1}", "Sida ${i + 1}")) }) }
                TextButton(onClick = { pages.add(listOf(TileSpec(TileType.POWER_3S, TileSize.LARGE))); pageIndex = pages.lastIndex; save() }) { Text(t("+ Page", "+ Sida")) }
                if (pages.size > 1) TextButton(onClick = { pages.removeAt(pageIndex); pageIndex = 0; save() }) { Text(t("Remove page", "Ta bort sida"), color = SpinColors.Bad) }
            }
            val tiles = pages.getOrNull(pageIndex) ?: emptyList()
            LazyColumn(Modifier.weight(1f)) {
                items(tiles.size) { i ->
                    val tile = tiles[i]
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(tileName(tile.type), modifier = Modifier.weight(1f))
                        for (size in TileSize.entries) {
                            FilterChip(tile.size == size, {
                                updatePage(tiles.toMutableList().also { it[i] = tile.copy(size = size) })
                            }, { Text(when (size) { TileSize.LARGE -> t("L", "S"); TileSize.MEDIUM -> "M"; TileSize.SMALL -> t("S", "L") }) })
                            Spacer(Modifier.width(4.dp))
                        }
                        IconButton(enabled = i > 0, onClick = {
                            updatePage(tiles.toMutableList().also { val x = it.removeAt(i); it.add(i - 1, x) })
                        }) { Icon(Icons.Filled.KeyboardArrowUp, t("Up", "Upp")) }
                        IconButton(enabled = i < tiles.lastIndex, onClick = {
                            updatePage(tiles.toMutableList().also { val x = it.removeAt(i); it.add(i + 1, x) })
                        }) { Icon(Icons.Filled.KeyboardArrowDown, t("Down", "Ner")) }
                        IconButton(onClick = { updatePage(tiles.toMutableList().also { it.removeAt(i) }) }) {
                            Icon(Icons.Filled.Delete, t("Remove", "Ta bort"), tint = SpinColors.Bad)
                        }
                    }
                }
                item {
                    Column {
                        Button(onClick = { addOpen = true }, modifier = Modifier.padding(vertical = 8.dp)) { Text(t("Add tile", "Lägg till ruta")) }
                        DropdownMenu(expanded = addOpen, onDismissRequest = { addOpen = false }) {
                            for (type in TileType.entries) DropdownMenuItem(text = { Text(tileName(type)) }, onClick = {
                                addOpen = false
                                updatePage(tiles + TileSpec(type, TileSize.MEDIUM))
                            })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun actionName(a: ControlAction): String = when (a) {
    ControlAction.NONE -> t("Nothing", "Ingenting")
    ControlAction.SHIFT_UP -> t("Shift harder", "Växla tyngre")
    ControlAction.SHIFT_DOWN -> t("Shift easier", "Växla lättare")
    ControlAction.INTENSITY_UP -> t("Intensity +5 %", "Intensitet +5 %")
    ControlAction.INTENSITY_DOWN -> t("Intensity −5 %", "Intensitet −5 %")
    ControlAction.SKIP_INTERVAL -> t("Skip interval", "Hoppa över intervall")
    ControlAction.TOGGLE_ERG -> t("ERG on/off", "ERG på/av")
    ControlAction.PAUSE_RESUME -> t("Pause/resume", "Paus/fortsätt")
    ControlAction.LAP -> t("Lap", "Varv")
    ControlAction.NEXT_PAGE -> t("Next page", "Nästa sida")
    ControlAction.PREV_PAGE -> t("Previous page", "Föregående sida")
    ControlAction.TOGGLE_GRAPH -> t("Show/hide graph", "Visa/dölj graf")
    ControlAction.GRADE_UP -> t("Grade +", "Lutning +")
    ControlAction.GRADE_DOWN -> t("Grade −", "Lutning −")
}

@Composable
private fun buttonName(b: RideButton): String = when (b) {
    RideButton.LEFT -> t("Left ◀", "Vänster ◀")
    RideButton.RIGHT -> t("Right ▶", "Höger ▶")
    RideButton.UP -> t("Up ▲", "Upp ▲")
    RideButton.DOWN -> t("Down ▼", "Ner ▼")
    RideButton.A -> "A"
    RideButton.B -> "B"
    RideButton.Y -> "Y"
    RideButton.Z -> "Z"
    RideButton.SHIFT_UP_LEFT -> t("Left shifter +", "Vänster växel +")
    RideButton.SHIFT_DOWN_LEFT -> t("Left shifter −", "Vänster växel −")
    RideButton.SHIFT_UP_RIGHT -> t("Right shifter +", "Höger växel +")
    RideButton.SHIFT_DOWN_RIGHT -> t("Right shifter −", "Höger växel −")
    RideButton.POWERUP_LEFT -> t("Left power-up", "Vänster power-up")
    RideButton.POWERUP_RIGHT -> t("Right power-up", "Höger power-up")
    RideButton.ONOFF_LEFT -> t("Left on/off", "Vänster på/av")
    RideButton.ONOFF_RIGHT -> t("Right on/off", "Höger på/av")
    RideButton.PADDLE_LEFT -> t("Left brake lever", "Vänster bromshandtag")
    RideButton.PADDLE_RIGHT -> t("Right brake lever", "Höger bromshandtag")
}

@Composable
fun ButtonsScreen(app: AppContainer, nav: Nav, profile: ProfileEntity) {
    val s = profile.settings
    var ergMode by remember { mutableStateOf(true) }
    val mapping = s.mapping
    val current = if (ergMode) mapping.erg else mapping.sim
    val lastPressedFlows = app.hub.controllers.value
    var editing by remember { mutableStateOf<Pair<RideButton, Boolean>?>(null) }

    fun update(button: RideButton, binding: ButtonBinding) {
        val newMap = current + (button to binding)
        val m = if (ergMode) mapping.copy(erg = newMap) else mapping.copy(sim = newMap)
        app.saveSettings(profile, s.copy(mapping = m))
    }

    ScreenScaffold(title = t("Handlebar buttons", "Knappar på styret"), onBack = nav::back, actions = {
        TextButton(onClick = { app.saveSettings(profile, s.copy(mapping = ControllerMapping())) }) { Text(t("Defaults", "Standard")) }
    }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(ergMode, { ergMode = true }, { Text(t("During ERG", "Under ERG")) })
                FilterChip(!ergMode, { ergMode = false }, { Text(t("Free ride / ERG off", "Fri cykling / ERG av")) })
            }
            Text(
                t("Short press and long press (hold ~1 s) can do different things. Press a button on the handlebar to see its name below.",
                    "Kort och långt tryck (håll ~1 s) kan göra olika saker. Tryck på en knapp på styret för att se dess namn nedan."),
                color = SpinColors.Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp),
            )
            for (c in lastPressedFlows) {
                val pressed by c.lastPressed.collectAsState()
                Text(t("Last pressed: ", "Senast tryckt: ") + pressed.joinToString { it.name }, color = SpinColors.Primary)
            }
            LazyColumn {
                items(RideButton.entries.toList()) { b ->
                    val binding = current[b] ?: ButtonBinding()
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(buttonName(b), modifier = Modifier.weight(1f))
                        Column(Modifier.weight(1f)) {
                            TextButton(onClick = { editing = b to false }) { Text(t("Short: ", "Kort: ") + actionName(binding.short)) }
                            DropdownMenu(expanded = editing == (b to false), onDismissRequest = { editing = null }) {
                                for (a in ControlAction.entries) DropdownMenuItem(text = { Text(actionName(a)) }, onClick = {
                                    editing = null; update(b, binding.copy(short = a))
                                })
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            TextButton(onClick = { editing = b to true }) { Text(t("Long: ", "Långt: ") + actionName(binding.long)) }
                            DropdownMenu(expanded = editing == (b to true), onDismissRequest = { editing = null }) {
                                for (a in ControlAction.entries) DropdownMenuItem(text = { Text(actionName(a)) }, onClick = {
                                    editing = null; update(b, binding.copy(long = a))
                                })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DiagnosticsScreen(app: AppContainer, nav: Nav, profile: ProfileEntity) {
    val context = LocalContext.current
    var lines by remember { mutableStateOf(app.bleLog.snapshot()) }
    val lang = LocalLanguage.current
    LaunchedEffect(Unit) {
        while (true) { lines = app.bleLog.snapshot(); delay(1000) }
    }
    ScreenScaffold(title = t("Bluetooth log", "Bluetooth-logg"), onBack = nav::back, actions = {
        TextButton(onClick = {
            val trainer = app.hub.trainer.value
            val header = buildString {
                append("Spinhaus diagnostics\n")
                append("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} Android ${android.os.Build.VERSION.RELEASE}\n")
                append("App: ").append(runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()).append('\n')
                append("Trainer: ${trainer?.name} ${trainer?.status?.value} fw=${trainer?.firmware?.value} caps=${trainer?.capabilities?.value}\n")
                app.hub.controllers.value.forEach { append("Controller: ${it.name} ${it.status.value} fw=${it.firmware.value} battery=${it.batteryPercent.value}\n") }
                app.hub.heartRate.value?.let { append("HR: ${it.name} ${it.status.value}\n") }
                append("Profile: ${profile.name} FTP ${profile.ftp}\n")
            }
            val f = app.bleLog.export(context, header)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Spinhaus log")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, tr(lang, "Share log", "Dela logg")))
        }) { Text(t("Export", "Exportera")) }
    }) { pad ->
        LazyColumn(Modifier.padding(pad).padding(horizontal = 12.dp)) {
            items(lines.reversed()) { l ->
                Text(l, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = SpinColors.Muted)
            }
        }
    }
}
