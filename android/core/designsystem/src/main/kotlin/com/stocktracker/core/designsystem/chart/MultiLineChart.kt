package com.stocktracker.core.designsystem.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val GridColor = Color(0xFF2A2A3A)
private val AxisLabelColor = Color(0xFF888888)

data class ChartSeries(val name: String, val values: List<Double>, val color: Color)

/**
 * Two-or-more-series line chart (no fill) with a legend row below — the
 * Canvas primitive behind PortfolioPnLChart's "Portfolio Value" view
 * (Cost Basis vs. Current Value), a plain LineChart+Line pair in the web app.
 */
@Composable
fun MultiLineChart(
    series: List<ChartSeries>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    domainMinFixedAtZero: Boolean = true,
) {
    val allValues = series.flatMap { it.values }
    if (allValues.isEmpty()) return
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = AxisLabelColor)

    val maxV = allValues.max()
    val minV = if (domainMinFixedAtZero) 0.0 else allValues.min()
    val pad = (maxV * 0.08).let { if (it == 0.0) 1000.0 else it }
    val domainMin = minV
    val domainMax = maxV + pad
    val domainSpan = (domainMax - domainMin).let { if (it == 0.0) 1.0 else it }

    Column(modifier) {
        Canvas(modifier = Modifier.fillMaxWidth().height(220.dp)) {
            val leftMargin = 42.dp.toPx()
            val bottomMargin = 16.dp.toPx()
            val topMargin = 6.dp.toPx()
            val plotWidth = size.width - leftMargin
            val plotHeight = size.height - bottomMargin - topMargin
            val lastIndex = (labels.size - 1).coerceAtLeast(1)

            fun xFor(i: Int) = leftMargin + (i.toFloat() / lastIndex) * plotWidth
            fun yFor(v: Double) = topMargin + ((domainMax - v) / domainSpan).toFloat() * plotHeight

            for (i in 0..3) {
                val y = topMargin + plotHeight * i / 3
                drawLine(GridColor, Offset(leftMargin, y), Offset(size.width, y), strokeWidth = 1f)
            }

            series.forEach { s ->
                val path = Path()
                s.values.forEachIndexed { i, v ->
                    val x = xFor(i)
                    val y = yFor(v)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color = s.color, style = Stroke(width = 2.dp.toPx()))
            }

            listOf(domainMax, (domainMax + domainMin) / 2, domainMin).forEach { v ->
                val measured = textMeasurer.measure(formatChartValue(v), style = labelStyle)
                val y = (yFor(v) - measured.size.height / 2f).coerceIn(0f, topMargin + plotHeight - measured.size.height)
                drawText(measured, topLeft = Offset(2.dp.toPx(), y))
            }

            sparseLabelIndices(labels.size).forEach { i ->
                val measured = textMeasurer.measure(labels[i], style = labelStyle)
                val x = (xFor(i) - measured.size.width / 2f).coerceIn(0f, size.width - measured.size.width)
                drawText(measured, topLeft = Offset(x, topMargin + plotHeight + 2.dp.toPx()))
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            series.forEach { s -> LegendEntry(s.name, s.color) }
        }
    }
}

@Composable
private fun RowScope.LegendEntry(label: String, color: Color) {
    Row(modifier = Modifier.padding(end = 16.dp)) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .padding(top = 3.dp)
                .background(color, RoundedCornerShape(50)),
        )
        Text(
            label,
            modifier = Modifier.padding(start = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
