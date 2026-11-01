package se.spinhaus.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import se.spinhaus.app.AppContainer
import se.spinhaus.app.ui.t
import se.spinhaus.app.ui.theme.SpinColors
import se.spinhaus.app.update.UpdateState

/** Shows a card when a newer version is available / downloading / ready. Nothing otherwise. */
@Composable
fun UpdateBanner(app: AppContainer, modifier: Modifier = Modifier) {
    val state by app.updates.state.collectAsState()
    val rideActive by app.ride.session.collectAsState()
    when (val s = state) {
        is UpdateState.Available -> SpinCard(modifier, color = SpinColors.Primary.copy(alpha = 0.18f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t("New version ${s.versionName} available", "Ny version ${s.versionName} finns"), style = MaterialTheme.typography.titleMedium)
                    Text(
                        t("You have ${app.updates.installedVersionName}. Your profiles and history are kept.",
                            "Du har ${app.updates.installedVersionName}. Profiler och historik finns kvar."),
                        color = SpinColors.Muted, style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = { app.updates.dismiss() }) { Text(t("Later", "Senare")) }
                Spacer(Modifier.width(8.dp))
                Button(enabled = rideActive == null, onClick = { app.updates.download() }) { Text(t("Update", "Uppdatera")) }
            }
        }
        is UpdateState.Downloading -> SpinCard(modifier, color = SpinColors.SurfaceHigh) {
            Text(t("Downloading ${s.versionName}…", "Laddar ner ${s.versionName}…"))
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
        }
        is UpdateState.ReadyToInstall -> SpinCard(modifier, color = SpinColors.Primary.copy(alpha = 0.18f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    t("Version ${s.versionName} is downloaded. Tap Install and confirm.", "Version ${s.versionName} är nedladdad. Tryck Installera och bekräfta."),
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = { app.updates.install() }) { Text(t("Install", "Installera")) }
            }
        }
        else -> {}
    }
}
