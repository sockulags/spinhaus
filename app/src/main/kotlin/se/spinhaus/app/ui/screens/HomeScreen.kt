package se.spinhaus.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.item
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.flowOf
import se.spinhaus.app.AppContainer
import se.spinhaus.app.ble.DeviceStatus
import se.spinhaus.app.data.ProfileEntity
import se.spinhaus.app.ui.Nav
import se.spinhaus.app.ui.Routes
import se.spinhaus.app.ui.components.Avatar
import se.spinhaus.app.ui.components.BigActionTile
import se.spinhaus.app.ui.components.ScreenScaffold
import se.spinhaus.app.ui.components.SpinCard
import se.spinhaus.app.ui.components.StatusDot
import se.spinhaus.app.ui.t
import se.spinhaus.app.ui.theme.SpinColors
import se.spinhaus.core.workout.WorkoutCategory
import androidx.compose.runtime.remember

@Composable
fun HomeScreen(app: AppContainer, nav: Nav, profile: ProfileEntity) {
    val trainer by app.hub.trainer.collectAsState()
    val controllers by app.hub.controllers.collectAsState()
    val hr by app.hub.heartRate.collectAsState()
    val trainerStatus by (trainer?.status ?: remember { flowOf(null) }).collectAsState(initial = null)
    val hrStatus by (hr?.status ?: remember { flowOf(null) }).collectAsState(initial = null)
    val firstController = controllers.firstOrNull()
    val controllerStatus by (firstController?.status ?: remember { flowOf(null) }).collectAsState(initial = null)
    val session by app.ride.session.collectAsState()
    val recovered by app.recovered.collectAsState()

    ScreenScaffold(
        title = "Spinhaus",
        actions = {
            Row(
                Modifier.clip(RoundedCornerShape(24.dp)).clickable { nav.go(Routes.PROFILES) }.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(profile.name, style = MaterialTheme.typography.titleMedium)
                    Text("FTP ${profile.ftp} W", style = MaterialTheme.typography.bodySmall, color = SpinColors.Muted)
                }
                Spacer(Modifier.width(10.dp))
                Avatar(profile.name, Color(profile.colorArgb), size = 40)
            }
        },
    ) { pad ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(260.dp),
            modifier = Modifier.padding(pad).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SpinCard(onClick = { nav.go(Routes.DEVICES) }) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        DeviceChip(t("Trainer", "Trainer"), trainerStatus)
                        DeviceChip(t("Controllers", "Styrknappar"), controllerStatus)
                        DeviceChip(t("Heart rate", "Puls"), hrStatus)
                        Spacer(Modifier.weight(1f))
                        Text(t("Devices ›", "Enheter ›"), color = SpinColors.Primary)
                    }
                    if (trainer == null) {
                        Spacer(Modifier.height(8.dp))
                        Text(t("No trainer paired yet – tap to pair your bike.", "Ingen trainer parad än – tryck för att para cykeln."), color = SpinColors.Warn)
                    }
                    if (controllerStatus == DeviceStatus.LOCKED) {
                        Spacer(Modifier.height(8.dp))
                        Text(t("Controllers are locked by Zwift – see Devices.", "Styrknapparna är låsta av Zwift – se Enheter."), color = SpinColors.Bad)
                    }
                }
            }
            if (session != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SpinCard(color = SpinColors.Primary.copy(alpha = 0.2f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(t("A ride is in progress", "En åktur pågår"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Button(onClick = { nav.go(Routes.RIDE) }) { Text(t("Back to ride", "Tillbaka till åkturen")) }
                        }
                    }
                }
            }
            if (recovered > 0) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SpinCard(color = SpinColors.SurfaceHigh) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                t("$recovered interrupted ride(s) were recovered and saved to history.", "$recovered avbrutna åkturer återställdes och sparades i historiken."),
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { app.clearRecovered() }) { Text("OK") }
                        }
                    }
                }
            }
            item {
                BigActionTile(t("Workouts", "Pass"), t("Structured ERG workouts", "Strukturerade ERG-pass"), Icons.Filled.FitnessCenter, SpinColors.powerZones[3]) {
                    nav.go(Routes.library())
                }
            }
            item {
                BigActionTile(t("Free ride", "Fri cykling"), t("Routes, gears and grade", "Rutter, växlar och lutning"), Icons.Filled.Landscape, SpinColors.powerZones[2]) {
                    nav.go(Routes.FREE)
                }
            }
            item {
                BigActionTile(t("FTP test", "FTP-test"), t("Ramp, 20 min or 2×8 min", "Ramp, 20 min eller 2×8 min"), Icons.Filled.Speed, SpinColors.powerZones[5]) {
                    nav.go(Routes.library(WorkoutCategory.FTP_TEST.name))
                }
            }
            item {
                BigActionTile(t("History", "Historik"), t("All your rides", "Alla dina åkturer"), Icons.Filled.History, SpinColors.powerZones[1]) {
                    nav.go(Routes.HISTORY)
                }
            }
            item {
                BigActionTile(t("Progress", "Utveckling"), t("FTP, fitness and power curve", "FTP, form och effektkurva"), Icons.Filled.ShowChart, SpinColors.powerZones[6]) {
                    nav.go(Routes.TRENDS)
                }
            }
            item {
                BigActionTile(t("Devices", "Enheter"), t("Trainer, controllers, HR", "Trainer, styrknappar, puls"), Icons.Filled.Bluetooth, SpinColors.Muted) {
                    nav.go(Routes.DEVICES)
                }
            }
            item {
                BigActionTile(t("Settings", "Inställningar"), t("Dashboard, sound, buttons", "Skärm, ljud, knappar"), Icons.Filled.Settings, SpinColors.Muted) {
                    nav.go(Routes.SETTINGS)
                }
            }
            item {
                val freeTitle = t("Free ride", "Fri cykling")
                BigActionTile(t("Quick start", "Snabbstart"), t("Just ride – flat road, gears", "Bara cykla – platt väg, växlar"), Icons.AutoMirrored.Filled.DirectionsBike, SpinColors.Primary) {
                    nav.startRide(profile, se.spinhaus.core.ride.RideMode.Free(null), freeTitle, "FREE")
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun DeviceChip(label: String, status: DeviceStatus?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(status)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
