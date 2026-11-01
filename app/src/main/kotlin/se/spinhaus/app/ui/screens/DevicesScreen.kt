package se.spinhaus.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import se.spinhaus.app.AppContainer
import se.spinhaus.app.ble.ControllerConnection
import se.spinhaus.app.ble.DeviceKind
import se.spinhaus.app.ble.DeviceStatus
import se.spinhaus.app.ble.ScannedDevice
import se.spinhaus.app.data.ProfileEntity
import se.spinhaus.app.ui.Nav
import se.spinhaus.app.ui.Routes
import se.spinhaus.app.ui.components.ScreenScaffold
import se.spinhaus.app.ui.components.SectionTitle
import se.spinhaus.app.ui.components.SpinCard
import se.spinhaus.app.ui.components.StatValue
import se.spinhaus.app.ui.components.StatusDot
import se.spinhaus.app.ui.components.statusText
import se.spinhaus.app.ui.t
import se.spinhaus.app.ui.theme.SpinColors
import se.spinhaus.core.ble.FtmsControl

@Composable
fun DevicesScreen(app: AppContainer, nav: Nav, profile: ProfileEntity) {
    val hub = app.hub
    val scanning by hub.scanner.scanning.collectAsState()
    val results by hub.scanner.results.collectAsState()
    val trainer by hub.trainer.collectAsState()
    val controllers by hub.controllers.collectAsState()
    val hr by hub.heartRate.collectAsState()
    val bluetoothOn by hub.bluetoothOn.collectAsState()
    val context = LocalContext.current

    DisposableEffect(Unit) {
        hub.scanner.start()
        onDispose { hub.scanner.stop() }
    }
    LaunchedEffect(scanning) {
        if (scanning) { delay(45_000); hub.scanner.stop() }
    }

    val found = results.values.sortedByDescending { it.rssi }
    val connectedAddresses = setOfNotNull(trainer?.address, hr?.address) + controllers.map { it.address }

    ScreenScaffold(
        title = t("Devices", "Enheter"),
        onBack = nav::back,
        actions = {
            if (scanning) {
                CircularProgressIndicator(Modifier.padding(end = 8.dp).width(20.dp).height(20.dp), strokeWidth = 2.dp)
                TextButton(onClick = { hub.scanner.stop() }) { Text(t("Stop", "Stoppa")) }
            } else TextButton(onClick = { hub.scanner.start() }) { Text(t("Scan", "Sök")) }
            TextButton(onClick = { nav.go(Routes.DIAGNOSTICS) }) { Text(t("Log", "Logg")) }
        },
    ) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            if (!bluetoothOn) {
                SpinCard(color = SpinColors.Bad.copy(alpha = 0.2f)) {
                    Text(t("Bluetooth is off. Turn it on in the tablet's settings.", "Bluetooth är avstängt. Slå på det i plattans inställningar."))
                }
            }
            Text(
                t("Close Zwift and Zwift Companion first – only one app at a time can control the trainer.",
                    "Stäng Zwift och Zwift Companion först – bara en app i taget kan styra trainern."),
                color = SpinColors.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp),
            )

            // ---------------- Trainer
            SectionTitle(t("Trainer", "Trainer"))
            val t0 = trainer
            if (t0 != null) {
                val status by t0.status.collectAsState()
                val data by t0.data.collectAsState()
                val caps by t0.capabilities.collectAsState()
                val lastResult by t0.lastResult.collectAsState()
                val elsewhere by t0.controlledElsewhere.collectAsState()
                SpinCard {
                    DeviceHeader(t0.name, status)
                    if (elsewhere) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            t("The trainer is controlled by another app (Zwift?). Close it completely – Spinhaus retries automatically.",
                                "Trainern styrs av en annan app (Zwift?). Stäng den helt – Spinhaus försöker igen automatiskt."),
                            color = SpinColors.Bad,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        StatValue(t("Power", "Effekt"), data.powerW?.toString() ?: "–", unit = "W")
                        StatValue(t("Cadence", "Kadens"), data.cadenceRpm?.toInt()?.toString() ?: "–", unit = "rpm")
                        StatValue(t("Speed", "Fart"), data.speedKmh?.let { "%.1f".format(it) } ?: "–", unit = "km/h")
                    }
                    if (status == DeviceStatus.CONNECTED) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            (if (caps.ftms) "FTMS" else "Cycling Power") +
                                (if (caps.erg) " · ERG" else "") + (if (caps.simulation) " · SIM" else "") +
                                " · ${caps.powerRange.first}–${caps.powerRange.last} W" + (lastResult?.let { " · $it" } ?: ""),
                            color = SpinColors.Muted, style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { app.scope.launch { t0.sendRaw(FtmsControl.setTargetPower(150)) } }) { Text("ERG 150 W") }
                            OutlinedButton(onClick = { app.scope.launch { t0.sendRaw(FtmsControl.setSimulation(5.0, 0.004, 0.51)) } }) { Text(t("Grade 5 %", "Lutning 5 %")) }
                            OutlinedButton(onClick = { app.scope.launch { t0.sendRaw(FtmsControl.setSimulation(0.0, 0.004, 0.51)) } }) { Text(t("Flat", "Platt")) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { hub.forgetTrainer() }) { Text(t("Forget trainer", "Glöm trainern"), color = SpinColors.Bad) }
                }
            } else {
                Text(t("No trainer paired. Pedal a few strokes to wake it up, then pick it below.", "Ingen trainer parad. Trampa några varv så vaknar den, välj den sedan nedan."), color = SpinColors.Muted)
            }
            FoundList(found.filter { it.kind == DeviceKind.TRAINER && it.address !in connectedAddresses }, t("Use as trainer", "Använd som trainer")) {
                hub.connectTrainer(it.address, it.name)
            }

            // ---------------- Controllers
            SectionTitle(t("Zwift Ride controllers", "Zwift Ride-styrknappar"))
            if (controllers.isEmpty()) {
                Text(t("No controllers paired. Press any button on the handlebar to wake them.", "Inga styrknappar parade. Tryck på valfri knapp på styret för att väcka dem."), color = SpinColors.Muted)
            }
            for (c in controllers) ControllerCard(c) { hub.forgetController(c.address) }
            FoundList(found.filter { it.kind == DeviceKind.CONTROLLER && it.address !in connectedAddresses }, t("Add", "Lägg till")) {
                hub.connectController(it.address, it.name)
            }

            // ---------------- Heart rate
            SectionTitle(t("Heart rate – ${profile.name}", "Puls – ${profile.name}"))
            val h = hr
            if (h != null) {
                val status by h.status.collectAsState()
                val bpm by h.bpm.collectAsState()
                val battery by h.batteryPercent.collectAsState()
                SpinCard {
                    DeviceHeader(profile.hrSensorName ?: h.name, status)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        StatValue(t("Heart rate", "Puls"), bpm?.toString() ?: "–", unit = "bpm", color = SpinColors.Bad)
                        battery?.let { StatValue(t("Battery", "Batteri"), "$it", unit = "%") }
                    }
                    TextButton(onClick = {
                        app.scope.launch { app.profiles.update(profile.copy(hrSensorAddress = null, hrSensorName = null)) }
                        hub.useHeartRate(null)
                    }) { Text(t("Remove", "Ta bort"), color = SpinColors.Bad) }
                }
            } else {
                Text(
                    t("No heart-rate sensor for this profile. Garmin watches: enable 'Broadcast heart rate'.",
                        "Ingen pulsmätare för den här profilen. Garmin-klocka: slå på 'Sänd puls'."),
                    color = SpinColors.Muted,
                )
            }
            FoundList(found.filter { it.kind == DeviceKind.HR && it.address !in connectedAddresses }, t("Use for me", "Använd för mig")) { d ->
                app.scope.launch { app.profiles.update(profile.copy(hrSensorAddress = d.address, hrSensorName = d.name)) }
                hub.useHeartRate(d.address)
            }

            // ---------------- Other
            val other = found.filter { it.kind == DeviceKind.OTHER && it.name != null && it.address !in connectedAddresses }
            if (other.isNotEmpty()) {
                SectionTitle(t("Other Bluetooth devices", "Andra Bluetooth-enheter"))
                Text(t("If your device is listed here, you can still assign it:", "Om din enhet syns här kan du ändå välja den:"), color = SpinColors.Muted)
                for (d in other.take(12)) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${d.name} (${d.rssi} dBm)", modifier = Modifier.weight(1f))
                        TextButton(onClick = { hub.connectTrainer(d.address, d.name) }) { Text(t("Trainer", "Trainer")) }
                        TextButton(onClick = { hub.connectController(d.address, d.name) }) { Text(t("Controller", "Knappar")) }
                        TextButton(onClick = {
                            app.scope.launch { app.profiles.update(profile.copy(hrSensorAddress = d.address, hrSensorName = d.name)) }
                            hub.useHeartRate(d.address)
                        }) { Text(t("HR", "Puls")) }
                    }
                }
            }

            SectionTitle(t("Keep the app alive", "Håll appen vid liv"))
            SpinCard {
                Text(
                    t("Lenovo tablets may stop apps in the background to save battery. Allow Spinhaus to run without battery restrictions so rides are never interrupted.",
                        "Lenovo-plattor kan stoppa appar i bakgrunden för att spara batteri. Låt Spinhaus köras utan batteribegränsning så att åkturer aldrig avbryts."),
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                    runCatching { context.startActivity(intent) }.onFailure {
                        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                }) { Text(t("Allow background use", "Tillåt bakgrundsanvändning")) }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun DeviceHeader(name: String, status: DeviceStatus?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(status)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            Text(statusText(status), color = SpinColors.Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ControllerCard(c: ControllerConnection, onForget: () -> Unit) {
    val status by c.status.collectAsState()
    val battery by c.batteryPercent.collectAsState()
    val pressed by c.lastPressed.collectAsState()
    val fw by c.firmware.collectAsState()
    SpinCard(Modifier.padding(bottom = 8.dp)) {
        DeviceHeader(c.name, status)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            battery?.let { StatValue(t("Battery", "Batteri"), "$it", unit = "%") }
            fw?.let { StatValue("Firmware", it) }
            StatValue(t("Last button", "Senaste knapp"), if (pressed.isEmpty()) "–" else pressed.joinToString { it.name })
        }
        if (status == DeviceStatus.LOCKED) {
            Spacer(Modifier.height(8.dp))
            SpinCard(color = SpinColors.Bad.copy(alpha = 0.15f)) {
                Text(t("Locked by Zwift", "Låst av Zwift"), style = MaterialTheme.typography.titleMedium)
                Text(
                    t("Zwift Ride V2 controllers (firmware 1.3+) only talk to other apps after they have been unlocked by the Zwift app, and the unlock lasts about 24 hours.\n\n" +
                        "1. Open the Zwift app (not Companion) on this tablet or a phone.\n2. Pair the controllers in Zwift's pairing screen.\n3. Close Zwift completely.\n4. Come back here – Spinhaus retries automatically every 20 seconds.\n\n" +
                        "Everything also works with the on-screen buttons while the controllers are locked.",
                        "Zwift Ride V2-kontroller (firmware 1.3+) pratar bara med andra appar efter att Zwift-appen har låst upp dem, och upplåsningen gäller i ungefär 24 timmar.\n\n" +
                        "1. Öppna Zwift-appen (inte Companion) på plattan eller en mobil.\n2. Para styrknapparna i Zwifts parningsvy.\n3. Stäng Zwift helt.\n4. Kom tillbaka hit – Spinhaus försöker igen automatiskt var 20:e sekund.\n\n" +
                        "Allt fungerar även med knapparna på skärmen medan kontrollerna är låsta."),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        TextButton(onClick = onForget) { Text(t("Forget", "Glöm"), color = SpinColors.Bad) }
    }
}

@Composable
private fun FoundList(devices: List<ScannedDevice>, actionLabel: String, onPick: (ScannedDevice) -> Unit) {
    for (d in devices) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(d.name ?: d.address, style = MaterialTheme.typography.bodyLarge)
                Text("${d.address} · ${d.rssi} dBm" + (d.detail?.let { " · $it" } ?: ""), color = SpinColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = { onPick(d) }) { Text(actionLabel) }
        }
    }
}

