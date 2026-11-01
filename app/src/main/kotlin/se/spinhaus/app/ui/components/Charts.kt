package se.spinhaus.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import se.spinhaus.app.ui.theme.SpinColors
import se.spinhaus.core.ride.Route
import se.spinhaus.core.training.PowerZones
import se.spinhaus.core.workout.Step
import se.spinhaus.core.workout.StepKind
import kotlin.math.max

/**
 * Workout profile: one bar per step coloured by power zone.
 * @param absolute values are watts instead of FTP fractions
 * @param progressS draw a cursor at this workout time
 * @param actual (workoutT, watts) samples drawn as a line
 */
@Composable
fun WorkoutChart(
    steps: List<Step>,
    ftp: Int,
    absolute: Boolean,
    modifier: Modifier = Modifier,
    height: Dp = 120.dp,
    progressS: Double? = null,
    actual: List<Pair<Int, Int>> = emptyList(),
) {
    val total = steps.lastOrNull()?.endS ?: 0
    Canvas(modifier.fillMaxWidth().height(height)) {
        if (total <= 0) return@Canvas
        fun frac(v: Double) = if (absolute) v / max(1, ftp) else v
        val maxFrac = max(1.3, steps.maxOf { max(frac(it.from ?: 0.6), frac(it.to ?: 0.6)) } * 1.08)
        val w = size.width
        val h = size.height
        fun x(t: Double) = (t / total * w).toFloat()
        fun y(f: Double) = (h - (f / maxFrac) * h).toFloat()
        for (s in steps) {
            val x0 = x(s.startS.toDouble())
            val x1 = x(s.endS.toDouble())
            when (s.kind) {
                StepKind.FREE -> drawRect(SpinColors.Muted.copy(alpha = 0.35f), Offset(x0, y(0.5)), Size(x1 - x0, h - y(0.5)))
                StepKind.MAX -> {
                    val top = y(frac(s.from ?: 1.2).coerceAtLeast(1.2))
                    drawRect(SpinColors.powerZones[5].copy(alpha = 0.55f), Offset(x0, top), Size(x1 - x0, h - top))
                }
                else -> {
                    val f0 = frac(s.from ?: 0.5)
                    val f1 = frac(s.to ?: f0)
                    val zone = PowerZones.zoneOfFraction((f0 + f1) / 2)
                    val p = Path().apply {
                        moveTo(x0, h); lineTo(x0, y(f0)); lineTo(x1, y(f1)); lineTo(x1, h); close()
                    }
                    drawPath(p, SpinColors.powerZone(zone))
                }
            }
            // separator
            drawLine(SpinColors.Background, Offset(x1, 0f), Offset(x1, h), strokeWidth = 1f)
        }
        // FTP line
        val yf = y(1.0)
        drawLine(Color.White.copy(alpha = 0.25f), Offset(0f, yf), Offset(w, yf), strokeWidth = 1.5f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
        if (actual.size > 1) {
            val path = Path()
            var started = false
            for ((t, watts) in actual) {
                val px = x(t.toDouble())
                val py = y(watts.toDouble() / max(1, ftp)).coerceIn(0f, h)
                if (!started) { path.moveTo(px, py); started = true } else path.lineTo(px, py)
            }
            drawPath(path, Color.White, style = Stroke(width = 2f))
        }
        if (progressS != null) {
            val px = x(progressS.coerceIn(0.0, total.toDouble()))
            drawRect(Color.Black.copy(alpha = 0.35f), Offset(0f, 0f), Size(px, h))
            drawLine(Color.White, Offset(px, 0f), Offset(px, h), strokeWidth = 3f, cap = StrokeCap.Round)
        }
    }
}

/** Elevation profile of a route with the rider's position. */
@Composable
fun ElevationChart(
    route: Route,
    positionM: Double?,
    modifier: Modifier = Modifier,
    height: Dp = 100.dp,
) {
    Canvas(modifier.fillMaxWidth().height(height)) {
        val pts = route.points
        val minE = pts.minOf { it.second } - 5
        val maxE = max(pts.maxOf { it.second } + 5, minE + 40)
        val w = size.width
        val h = size.height
        fun x(d: Double) = (d / route.lengthM * w).toFloat()
        fun y(e: Double) = (h - (e - minE) / (maxE - minE) * h).toFloat()
        val fill = Path().apply {
            moveTo(0f, h)
            for ((d, e) in pts) lineTo(x(d), y(e))
            lineTo(w, h); close()
        }
        drawPath(fill, SpinColors.Primary.copy(alpha = 0.25f))
        val line = Path().apply {
            pts.forEachIndexed { i, (d, e) -> if (i == 0) moveTo(x(d), y(e)) else lineTo(x(d), y(e)) }
        }
        drawPath(line, SpinColors.Primary, style = Stroke(width = 3f))
        if (positionM != null) {
            val d = if (route.loop) positionM % route.lengthM else positionM.coerceAtMost(route.lengthM)
            val px = x(d)
            val py = y(route.elevationAt(d))
            drawLine(Color.White.copy(alpha = 0.6f), Offset(px, 0f), Offset(px, h), strokeWidth = 2f)
            drawCircle(Color.White, radius = 9f, center = Offset(px, py))
            drawCircle(SpinColors.Primary, radius = 6f, center = Offset(px, py))
        }
    }
}

/** Line chart of one or more series sharing the x-axis (index). Nulls break the line. */
@Composable
fun SeriesChart(
    series: List<Pair<List<Float?>, Color>>,
    modifier: Modifier = Modifier,
    height: Dp = 140.dp,
    minY: Float? = null,
    maxY: Float? = null,
    fillFirst: Boolean = false,
    guideY: Float? = null,
) {
    Canvas(modifier.fillMaxWidth().height(height)) {
        val all = series.flatMap { it.first }.filterNotNull()
        if (all.isEmpty()) return@Canvas
        val lo = minY ?: all.min()
        val hi = max(maxY ?: all.max(), lo + 1f)
        val n = series.maxOf { it.first.size }
        if (n < 2) return@Canvas
        val w = size.width
        val h = size.height
        fun x(i: Int) = i.toFloat() / (n - 1) * w
        fun y(v: Float) = h - (v - lo) / (hi - lo) * h
        if (guideY != null && guideY in lo..hi) {
            drawLine(Color.White.copy(alpha = 0.3f), Offset(0f, y(guideY)), Offset(w, y(guideY)), 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
        }
        series.forEachIndexed { si, (values, color) ->
            drawSeries(values, color, ::x, ::y, fill = fillFirst && si == 0, h = h)
        }
    }
}

private fun DrawScope.drawSeries(values: List<Float?>, color: Color, x: (Int) -> Float, y: (Float) -> Float, fill: Boolean, h: Float) {
    val path = Path()
    var open = false
    var firstX = 0f
    var lastX = 0f
    values.forEachIndexed { i, v ->
        if (v == null) { open = false; return@forEachIndexed }
        if (!open) { path.moveTo(x(i), y(v)); open = true; if (i == 0) firstX = x(i) } else path.lineTo(x(i), y(v))
        lastX = x(i)
    }
    if (fill) {
        val f = Path().apply {
            addPath(path)
            lineTo(lastX, h); lineTo(firstX, h); close()
        }
        drawPath(f, color.copy(alpha = 0.2f))
    }
    drawPath(path, color, style = Stroke(width = 2.5f))
}

/** Horizontal stacked bar (e.g. time in zones). */
@Composable
fun StackedBar(values: List<Int>, colors: List<Color>, modifier: Modifier = Modifier, height: Dp = 18.dp) {
    Canvas(modifier.fillMaxWidth().height(height)) {
        val total = values.sum().toFloat()
        if (total <= 0) return@Canvas
        var x = 0f
        values.forEachIndexed { i, v ->
            val w = v / total * size.width
            drawRect(colors[i % colors.size], Offset(x, 0f), Size(w, size.height))
            x += w
        }
    }
}

/** Simple vertical bar chart. */
@Composable
fun BarChart(values: List<Float>, color: Color, modifier: Modifier = Modifier, height: Dp = 120.dp, highlightLast: Boolean = true) {
    Canvas(modifier.fillMaxWidth().height(height)) {
        if (values.isEmpty()) return@Canvas
        val mx = max(values.max(), 1f)
        val slot = size.width / values.size
        values.forEachIndexed { i, v ->
            val bh = v / mx * size.height
            val c = if (highlightLast && i == values.lastIndex) color else color.copy(alpha = 0.6f)
            drawRect(c, Offset(i * slot + slot * 0.15f, size.height - bh), Size(slot * 0.7f, bh))
        }
    }
}
