package se.spinhaus.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.spinhaus.app.ble.DeviceStatus
import se.spinhaus.app.ui.t
import se.spinhaus.app.ui.theme.SpinColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    floating: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        containerColor = SpinColors.Background,
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t("Back", "Tillbaka"))
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SpinColors.Background),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = floating,
        content = content,
    )
}

@Composable
fun SpinCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    color: Color = SpinColors.Surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    val m = if (onClick != null) modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick) else modifier
    Card(
        modifier = m,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = color),
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier.padding(top = 16.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = SpinColors.Muted,
        letterSpacing = 1.sp,
    )
}

@Composable
fun BigActionTile(
    title: String,
    subtitle: String?,
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    SpinCard(modifier = modifier, onClick = onClick) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = color, modifier = Modifier.size(28.dp)) }
        Spacer(Modifier.size(12.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = SpinColors.Muted)
    }
}

@Composable
fun StatusDot(status: DeviceStatus?, modifier: Modifier = Modifier) {
    val c = when (status) {
        DeviceStatus.CONNECTED -> SpinColors.Good
        DeviceStatus.CONNECTING, DeviceStatus.RECONNECTING -> SpinColors.Warn
        DeviceStatus.LOCKED, DeviceStatus.UNSUPPORTED -> SpinColors.Bad
        DeviceStatus.IDLE, null -> SpinColors.Muted.copy(alpha = 0.5f)
    }
    Box(modifier.size(10.dp).clip(CircleShape).background(c))
}

@Composable
fun statusText(status: DeviceStatus?): String = when (status) {
    DeviceStatus.CONNECTED -> t("Connected", "Ansluten")
    DeviceStatus.CONNECTING -> t("Connecting…", "Ansluter…")
    DeviceStatus.RECONNECTING -> t("Reconnecting…", "Återansluter…")
    DeviceStatus.LOCKED -> t("Locked by Zwift", "Låst av Zwift")
    DeviceStatus.UNSUPPORTED -> t("Not supported", "Stöds inte")
    DeviceStatus.IDLE, null -> t("Not connected", "Inte ansluten")
}

@Composable
fun StatValue(label: String, value: String, modifier: Modifier = Modifier, unit: String? = null, color: Color = SpinColors.OnSurface) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = SpinColors.Muted, maxLines = 1)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = MaterialTheme.typography.titleLarge, color = color, fontWeight = FontWeight.Bold)
            if (unit != null) {
                Spacer(Modifier.width(3.dp))
                Text(unit, style = MaterialTheme.typography.bodySmall, color = SpinColors.Muted, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
    }
}

@Composable
fun Avatar(name: String, color: Color, size: Int = 44, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size.dp).clip(CircleShape).background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().split(" ").mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString(""),
            color = Color.Black, fontWeight = FontWeight.Bold, fontSize = (size * 0.4f).sp,
        )
    }
}

@Composable
fun CenteredMessage(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = SpinColors.Muted, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun LabeledRow(label: String, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        content()
    }
}
