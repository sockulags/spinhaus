package se.spinhaus.app.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.item
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import se.spinhaus.app.AppContainer
import se.spinhaus.app.data.ProfileEntity
import se.spinhaus.app.data.Units
import se.spinhaus.app.data.settings
import se.spinhaus.app.ui.Fmt
import se.spinhaus.app.ui.LocalLanguage
import se.spinhaus.app.ui.Nav
import se.spinhaus.app.ui.components.ElevationChart
import se.spinhaus.app.ui.components.ScreenScaffold
import se.spinhaus.app.ui.components.SpinCard
import se.spinhaus.app.ui.t
import se.spinhaus.app.ui.theme.SpinColors
import se.spinhaus.core.ride.RideMode
import se.spinhaus.core.ride.Routes as RideRoutes

@Composable
fun FreeRideSetupScreen(app: AppContainer, nav: Nav, profile: ProfileEntity) {
    var routeId by rememberSaveable { mutableStateOf<String?>(null) }
    var goal by rememberSaveable { mutableStateOf("none") }
    val lang = LocalLanguage.current
    val imperial = profile.settings.units == Units.IMPERIAL
    val manualTitle = t("Free ride", "Fri cykling")

    ScreenScaffold(title = t("Free ride", "Fri cykling"), onBack = nav::back) { pad ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(300.dp),
            modifier = Modifier.padding(pad).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    t("Shift with the handlebar buttons. Your effort decides the speed; climbs get heavier just like outside.",
                        "Växla med knapparna på styret. Din insats avgör farten; backar blir tyngre precis som ute."),
                    color = SpinColors.Muted,
                )
            }
            item {
                val selected = routeId == null
                SpinCard(
                    modifier = if (selected) Modifier.border(2.dp, SpinColors.Primary, RoundedCornerShape(16.dp)) else Modifier,
                    onClick = { routeId = null },
                ) {
                    Text(t("Manual grade", "Manuell lutning"), style = MaterialTheme.typography.titleLarge)
                    Text(t("Flat road – change the grade with ▲▼ on the controller or screen.", "Platt väg – ändra lutningen med ▲▼ på styret eller skärmen."), color = SpinColors.Muted)
                    Spacer(Modifier.height(60.dp))
                }
            }
            items(RideRoutes.all, key = { it.id }) { r ->
                val selected = routeId == r.id
                SpinCard(
                    modifier = if (selected) Modifier.border(2.dp, SpinColors.Primary, RoundedCornerShape(16.dp)) else Modifier,
                    onClick = { routeId = r.id },
                ) {
                    Text(if (lang == "sv") r.nameSv else r.name, style = MaterialTheme.typography.titleLarge)
                    Text(
                        Fmt.distance(r.lengthM, imperial) + " · ↑ ${r.ascentM.toInt()} m" + if (r.loop) t(" · loop", " · slinga") else "",
                        color = SpinColors.Muted,
                    )
                    Spacer(Modifier.height(8.dp))
                    ElevationChart(r, null, height = 60.dp)
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Text(t("Goal", "Mål"), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((id, label) in listOf(
                            "none" to t("Just ride", "Bara cykla"),
                            "t30" to "30 min", "t45" to "45 min", "t60" to "60 min",
                            "d20" to "20 km", "d40" to "40 km",
                        )) FilterChip(selected = goal == id, onClick = { goal = id }, label = { Text(label) })
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        onClick = {
                            val route = RideRoutes.byId(routeId)
                            val time = goal.takeIf { it.startsWith("t") }?.drop(1)?.toIntOrNull()?.let { it * 60 }
                            val dist = goal.takeIf { it.startsWith("d") }?.drop(1)?.toDoubleOrNull()?.let { it * 1000 }
                            val title = route?.let { if (lang == "sv") it.nameSv else it.name } ?: manualTitle
                            nav.startRide(profile, RideMode.Free(route, dist, time), title, "FREE")
                        },
                    ) { Text(t("Start riding", "Börja cykla"), style = MaterialTheme.typography.titleMedium) }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}
