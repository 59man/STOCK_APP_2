package com.stocktracker.core.designsystem.chart

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

data class DonutSlice(val label: String, val value: Double, val color: Color)

private const val SweepInDurationMs = 550

@Composable
fun DonutChart(
    slices: List<DonutSlice>,
    modifier: Modifier = Modifier,
    valueFormatter: (Double) -> String = { it.toString() },
    emptyLabel: String = "No data",
) {
    val nonZero = slices.filter { it.value > 0 }
    if (nonZero.isEmpty()) {
        Box(modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
            Text(emptyLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val total = nonZero.sumOf { it.value }
    val textMeasurer = rememberTextMeasurer()

    // Every slice grows from 0 to its full sweep simultaneously (a radial reveal) rather than
    // one continuous sweep across slices in sequence — simpler to compute per-slice and reads
    // just as clearly as a "pie filling in" animation.
    val progress = remember { Animatable(0f) }
    LaunchedEffect(nonZero) {
        progress.snapTo(0f)
        progress.animateTo(1f, animationSpec = tween(SweepInDurationMs))
    }
    val sweepProgress = progress.value

    Column(modifier) {
        Canvas(modifier = Modifier.fillMaxWidth().height(200.dp)) {
            val diameter = kotlin.math.min(size.width, size.height) * 0.92f
            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            var startAngle = -90f
            val gap = if (nonZero.size > 1) 1.5f else 0f

            nonZero.forEach { slice ->
                val sweep = (slice.value / total * 360.0).toFloat()
                drawArc(
                    color = slice.color,
                    startAngle = startAngle,
                    sweepAngle = ((sweep - gap).coerceAtLeast(0f)) * sweepProgress,
                    useCenter = true,
                    topLeft = topLeft,
                    size = Size(diameter, diameter),
                )
                val percent = slice.value / total
                // Gated on the sweep being essentially complete — a percentage label positioned
                // mid-animation, before the wedge has grown into its final angle, would sit in
                // the wrong place relative to the (still-growing) slice.
                if (percent >= 0.05 && sweepProgress > 0.95f) {
                    val midAngleRad = Math.toRadians((startAngle + sweep / 2).toDouble())
                    val labelRadius = diameter / 2f * 0.65f
                    val cx = topLeft.x + diameter / 2f + (labelRadius * cos(midAngleRad)).toFloat()
                    val cy = topLeft.y + diameter / 2f + (labelRadius * sin(midAngleRad)).toFloat()
                    val text = "${(percent * 100).roundToInt()}%"
                    val measured = textMeasurer.measure(
                        text,
                        style = TextStyle(fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold),
                    )
                    drawText(measured, topLeft = Offset(cx - measured.size.width / 2f, cy - measured.size.height / 2f))
                }
                startAngle += sweep
            }
        }
        Column(Modifier.padding(top = 8.dp)) {
            nonZero.forEach { slice ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 3.dp)
                        .background(slice.color.copy(alpha = 0.14f), RoundedCornerShape(20.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Box(Modifier.size(8.dp).background(slice.color, RoundedCornerShape(50)))
                    Text(
                        slice.label,
                        modifier = Modifier.padding(start = 6.dp).weight(1f, fill = false),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    Text(
                        valueFormatter(slice.value),
                        modifier = Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
