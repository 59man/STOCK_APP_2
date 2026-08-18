package com.stocktracker.core.designsystem.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.max

private val GridColor = Color(0xFF2A2A3A)
private val AxisLabelColor = Color(0xFF888888)
private val ZeroLineColor = Color(0xFF555555)

/**
 * Single-series area/line chart with a gradient fill, sparse axis labels,
 * and an optional dashed zero-reference line — the shared Canvas primitive
 * behind PriceChart's per-ticker view and PortfolioPnLChart's Total Return
 * view (both AreaChart+Area in the web app).
 */
@Composable
fun AreaLineChart(
    values: List<Double>,
    labels: List<String>,
    color: Color,
    modifier: Modifier = Modifier,
    showZeroLine: Boolean = false,
) {
    if (values.isEmpty()) return
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = AxisLabelColor)

    val minV = values.min()
    val maxV = values.max()
    val pad = max(abs(minV), abs(maxV)) * 0.08
    val domainMin = minV - (if (pad == 0.0) 1000.0 else pad)
    val domainMax = maxV + (if (pad == 0.0) 1000.0 else pad)
    val domainSpan = (domainMax - domainMin).let { if (it == 0.0) 1.0 else it }

    Canvas(modifier = modifier.fillMaxWidth().height(220.dp)) {
        val leftMargin = 42.dp.toPx()
        val bottomMargin = 16.dp.toPx()
        val topMargin = 6.dp.toPx()
        val plotWidth = size.width - leftMargin
        val plotHeight = size.height - bottomMargin - topMargin
        val lastIndex = (values.size - 1).coerceAtLeast(1)

        fun xFor(i: Int) = leftMargin + (i.toFloat() / lastIndex) * plotWidth
        fun yFor(v: Double) = topMargin + ((domainMax - v) / domainSpan).toFloat() * plotHeight

        for (i in 0..3) {
            val y = topMargin + plotHeight * i / 3
            drawLine(GridColor, Offset(leftMargin, y), Offset(size.width, y), strokeWidth = 1f)
        }
        if (showZeroLine && domainMin < 0 && domainMax > 0) {
            val y = yFor(0.0)
            drawLine(
                ZeroLineColor, Offset(leftMargin, y), Offset(size.width, y),
                strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
            )
        }

        val linePath = Path()
        values.forEachIndexed { i, v ->
            val x = xFor(i)
            val y = yFor(v)
            if (i == 0) linePath.moveTo(x, y) else linePath.lineTo(x, y)
        }
        val fillPath = Path().apply {
            addPath(linePath)
            lineTo(xFor(values.size - 1), topMargin + plotHeight)
            lineTo(xFor(0), topMargin + plotHeight)
            close()
        }
        drawPath(fillPath, brush = Brush.verticalGradient(listOf(color.copy(alpha = 0.3f), color.copy(alpha = 0f))))
        drawPath(linePath, color = color, style = Stroke(width = 2.dp.toPx()))

        listOf(domainMax, (domainMax + domainMin) / 2, domainMin).forEach { v ->
            val measured = textMeasurer.measure(formatChartValue(v), style = labelStyle)
            val y = (yFor(v) - measured.size.height / 2f).coerceIn(0f, topMargin + plotHeight - measured.size.height)
            drawText(measured, topLeft = Offset(2.dp.toPx(), y))
        }

        sparseLabelIndices(labels.size).forEach { i ->
            val measured = textMeasurer.measure(labels[i], style = labelStyle)
            // Centered on xFor(i), only clamped to the Canvas's own edges (not leftMargin) — the
            // first/last label is allowed to bleed into the margin, since forcing it flush against
            // leftMargin instead pushes it into its neighbor's space and the two collide instead.
            val x = (xFor(i) - measured.size.width / 2f).coerceIn(0f, size.width - measured.size.width)
            drawText(measured, topLeft = Offset(x, topMargin + plotHeight + 2.dp.toPx()))
        }
    }
}
