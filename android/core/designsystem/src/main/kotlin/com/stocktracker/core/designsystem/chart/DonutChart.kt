package com.stocktracker.core.designsystem.chart

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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

/**
 * Donut chart with an in-ring percentage label per slice (≥5%, mirrors
 * InsideLabel's cutoff in PortfolioPieCharts.tsx) and a legend row per slice
 * below — the mobile equivalent of the web's Pie+Legend+hover-Tooltip, since
 * a persistent legend with values does the same job a hover tooltip can't on
 * a touch screen.
 */
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

    Column(modifier) {
        Canvas(modifier = Modifier.fillMaxWidth().height(200.dp)) {
            val diameter = kotlin.math.min(size.width, size.height) * 0.92f
            val strokeWidth = diameter * 0.32f
            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            var startAngle = -90f
            val gap = if (nonZero.size > 1) 1.5f else 0f

            nonZero.forEach { slice ->
                val sweep = (slice.value / total * 360.0).toFloat()
                drawArc(
                    color = slice.color,
                    startAngle = startAngle,
                    sweepAngle = (sweep - gap).coerceAtLeast(0f),
                    useCenter = false,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Butt),
                    topLeft = topLeft,
                    size = Size(diameter, diameter),
                )
                val percent = slice.value / total
                if (percent >= 0.05) {
                    val midAngleRad = Math.toRadians((startAngle + sweep / 2).toDouble())
                    val labelRadius = diameter / 2f - strokeWidth / 2f
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
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                    Box(Modifier.size(8.dp).background(slice.color, RoundedCornerShape(50)))
                    Text(
                        slice.label,
                        modifier = Modifier.padding(start = 6.dp).weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        valueFormatter(slice.value),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
