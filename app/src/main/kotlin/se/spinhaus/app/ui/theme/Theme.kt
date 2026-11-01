package se.spinhaus.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object SpinColors {
    val Background = Color(0xFF0E1116)
    val Surface = Color(0xFF171B22)
    val SurfaceHigh = Color(0xFF20262F)
    val Outline = Color(0xFF2E3640)
    val Primary = Color(0xFFFF6B1A)
    val OnSurface = Color(0xFFE8EAED)
    val Muted = Color(0xFF8A93A0)
    val Good = Color(0xFF3CC24A)
    val Warn = Color(0xFFFFC400)
    val Bad = Color(0xFFF23B3B)

    /** Power zone colours (Coggan 7 zones, Zwift-like palette). */
    val powerZones = listOf(
        Color(0xFF8E959E), // Z1 recovery – grey
        Color(0xFF2D8CFF), // Z2 endurance – blue
        Color(0xFF3CC24A), // Z3 tempo – green
        Color(0xFFFFC400), // Z4 threshold – yellow
        Color(0xFFFF7A1A), // Z5 VO2max – orange
        Color(0xFFF23B3B), // Z6 anaerobic – red
        Color(0xFFB23BF2), // Z7 neuromuscular – purple
    )

    val hrZones = listOf(
        Color(0xFF8E959E), Color(0xFF2D8CFF), Color(0xFF3CC24A), Color(0xFFFF7A1A), Color(0xFFF23B3B),
    )

    fun powerZone(zone: Int): Color = powerZones[(zone - 1).coerceIn(0, 6)]
    fun hrZone(zone: Int): Color = hrZones[(zone - 1).coerceIn(0, 4)]

    val profileColors = listOf(
        Color(0xFFFF6B1A), Color(0xFF2D8CFF), Color(0xFF3CC24A), Color(0xFFB23BF2),
        Color(0xFFF23B3B), Color(0xFFFFC400), Color(0xFF1ACFC0), Color(0xFFFF4FA3),
    )
}

private val scheme = darkColorScheme(
    primary = SpinColors.Primary,
    onPrimary = Color.Black,
    secondary = Color(0xFF2D8CFF),
    background = SpinColors.Background,
    onBackground = SpinColors.OnSurface,
    surface = SpinColors.Surface,
    onSurface = SpinColors.OnSurface,
    surfaceVariant = SpinColors.SurfaceHigh,
    onSurfaceVariant = SpinColors.Muted,
    surfaceContainer = SpinColors.Surface,
    surfaceContainerHigh = SpinColors.SurfaceHigh,
    surfaceContainerHighest = SpinColors.SurfaceHigh,
    surfaceContainerLow = SpinColors.Surface,
    outline = SpinColors.Outline,
    error = SpinColors.Bad,
)

val NumberStyle = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)

private val typography = Typography().let {
    it.copy(
        headlineLarge = it.headlineLarge.copy(fontWeight = FontWeight.Bold),
        headlineMedium = it.headlineMedium.copy(fontWeight = FontWeight.Bold),
        titleLarge = it.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
    )
}

@Composable
fun SpinhausTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
