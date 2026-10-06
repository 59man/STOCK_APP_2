package com.stocktracker.core.designsystem.chart

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.max

private const val DrawInDurationMs = 550

/**
 * Single-series area/line chart with a gradient fill, sparse axis labels,
 * and an optional dashed zero-reference line — the shared Canvas primitive
 * behind PriceChart's per-ticker view and PortfolioPnLChart's Total Return
 * view (both AreaChart+Area in the web app).
 *
 * Tapping or dragging sideways selects the nearest point and shows a crosshair with that
 * point's [pointLabels] entry and its value; a second tap on the same point hides it.
 */
@Composable
fun AreaLineChart(
    values: List<Double>,
    labels: List<String>,
    color: Color,
    modifier: Modifier = Modifier,
    showZeroLine: Boolean = false,
    /** Optional second series drawn as a dashed line on the same axes (e.g. a benchmark). */
    overlay: List<Double>? = null,
    overlayColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    /** Full per-point label for the touch tooltip (axis [labels] are abbreviated); defaults to [labels]. */
    pointLabels: List<String> = labels,
    valueFormatter: (Double) -> String = ::formatChartValue,
    /** Names the [overlay] value in the touch tooltip; the overlay row is omitted when null. */
    overlayLabel: String? = null,
) {
    if (values.isEmpty()) return
    val overlayPoints = overlay?.takeIf { it.size == values.size }
    val textMeasurer = rememberTextMeasurer()
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val axisLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val zeroLineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
    val labelStyle = TextStyle(fontSize = 10.sp, color = axisLabelColor)
    val tooltipBackground = MaterialTheme.colorScheme.surfaceVariant
    val tooltipStyle = TextStyle(fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
    val crosshairColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)

    // Reset whenever the data changes: an index into the previous range means nothing now.
    var selected by remember(values) { mutableStateOf<Int?>(null) }

    // Draws the line/fill left-to-right on first composition and whenever the underlying
    // data changes (e.g. a new range selection), instead of popping in fully rendered.
    val progress = remember { Animatable(0f) }
    LaunchedEffect(values) {
        progress.snapTo(0f)
        progress.animateTo(1f, animationSpec = tween(DrawInDurationMs))
    }
    val drawProgress = progress.value

    val minV = minOf(values.min(), overlayPoints?.min() ?: values.min())
    val maxV = maxOf(values.max(), overlayPoints?.max() ?: values.max())
    val pad = max(abs(minV), abs(maxV)) * 0.08
    val domainMin = minV - (if (pad == 0.0) 1000.0 else pad)
    val domainMax = maxV + (if (pad == 0.0) 1000.0 else pad)
    val domainSpan = (domainMax - domainMin).let { if (it == 0.0) 1.0 else it }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp)
            .pointerInput(values) {
                val leftMargin = ChartLeftMargin.toPx()
                detectTapGestures { offset ->
                    val i = nearestPointIndex(offset.x, leftMargin, size.width - leftMargin, values.size)
                    selected = if (selected == i) null else i
                }
            }
            .pointerInput(values) {
                val leftMargin = ChartLeftMargin.toPx()
                fun select(x: Float) {
                    selected = nearestPointIndex(x, leftMargin, size.width - leftMargin, values.size)
                }
                // Horizontal only, so a vertical swipe still scrolls the screen the chart sits in.
                detectHorizontalDragGestures(onDragStart = { select(it.x) }) { change, _ ->
                    change.consume()
                    select(change.position.x)
                }
            },
    ) {
        val leftMargin = ChartLeftMargin.toPx()
        val bottomMargin = 16.dp.toPx()
        val topMargin = 6.dp.toPx()
        val plotWidth = size.width - leftMargin
        val plotHeight = size.height - bottomMargin - topMargin
        val lastIndex = (values.size - 1).coerceAtLeast(1)

        fun xFor(i: Int) = leftMargin + (i.toFloat() / lastIndex) * plotWidth
        fun yFor(v: Double) = topMargin + ((domainMax - v) / domainSpan).toFloat() * plotHeight

        for (i in 0..3) {
            val y = topMargin + plotHeight * i / 3
            drawLine(gridColor, Offset(leftMargin, y), Offset(size.width, y), strokeWidth = 1f)
        }
        if (showZeroLine && domainMin < 0 && domainMax > 0) {
            val y = yFor(0.0)
            drawLine(
                zeroLineColor, Offset(leftMargin, y), Offset(size.width, y),
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
        clipRect(right = leftMargin + plotWidth * drawProgress) {
            drawPath(fillPath, brush = Brush.verticalGradient(listOf(color.copy(alpha = 0.3f), color.copy(alpha = 0f))))
            drawPath(linePath, color = color, style = Stroke(width = 2.dp.toPx()))
            overlayPoints?.let { pts ->
                val overlayPath = Path()
                pts.forEachIndexed { i, v -> if (i == 0) overlayPath.moveTo(xFor(i), yFor(v)) else overlayPath.lineTo(xFor(i), yFor(v)) }
                drawPath(
                    overlayPath, color = overlayColor,
                    style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
                )
            }
        }

        listOf(domainMax, (domainMax + domainMin) / 2, domainMin).forEach { v ->
            val measured = textMeasurer.measure(formatChartValue(v), style = labelStyle)
            val y = (yFor(v) - measured.size.height / 2f).coerceIn(0f, topMargin + plotHeight - measured.size.height)
            drawText(measured, topLeft = Offset(2.dp.toPx(), y))
        }

        // sparseLabelIndices only spaces indices evenly — it knows nothing about how wide each
        // label's actual text renders, so a short label ("Jan 1 '24") next to a wide one ("Aug 28
        // '26") can still collide even at "even" index spacing. Placing right-to-left and
        // dropping anything that would overlap the nearest label already placed to its right
        // fixes that while keeping the rightmost (most recent / "today") label always visible,
        // same priority sparseLabelIndices itself already gives the last index.
        var claimedLeftEdge = Float.MAX_VALUE
        sparseLabelIndices(labels.size).reversed().forEach { i ->
            val measured = textMeasurer.measure(labels[i], style = labelStyle)
            // Centered on xFor(i), only clamped to the Canvas's own edges (not leftMargin) — the
            // first/last label is allowed to bleed into the margin, since forcing it flush against
            // leftMargin instead pushes it into its neighbor's space and the two collide instead.
            val x = (xFor(i) - measured.size.width / 2f).coerceIn(0f, size.width - measured.size.width)
            val right = x + measured.size.width
            if (right + 4.dp.toPx() <= claimedLeftEdge) {
                drawText(measured, topLeft = Offset(x, topMargin + plotHeight + 2.dp.toPx()))
                claimedLeftEdge = x
            }
        }

        selected?.takeIf { it in values.indices }?.let { i ->
            val x = xFor(i)
            drawLine(crosshairColor, Offset(x, topMargin), Offset(x, topMargin + plotHeight), strokeWidth = 1.dp.toPx())
            drawCircle(color, radius = 4.dp.toPx(), center = Offset(x, yFor(values[i])))
            val overlayValue = overlayPoints?.getOrNull(i)
            if (overlayValue != null) {
                drawCircle(overlayColor, radius = 3.dp.toPx(), center = Offset(x, yFor(overlayValue)))
            }

            val lines = buildList {
                add(pointLabels.getOrNull(i) ?: labels.getOrNull(i).orEmpty())
                add(valueFormatter(values[i]))
                if (overlayValue != null && overlayLabel != null) add("$overlayLabel: ${valueFormatter(overlayValue)}")
            }.map { textMeasurer.measure(it, style = tooltipStyle) }
            val padding = 6.dp.toPx()
            val boxWidth = lines.maxOf { it.size.width } + padding * 2
            val boxHeight = lines.sumOf { it.size.height } + padding * 2
            // Beside the crosshair rather than on it, flipping sides near the right edge.
            val gap = 8.dp.toPx()
            val boxX = (if (x + gap + boxWidth <= size.width) x + gap else x - gap - boxWidth)
                .coerceIn(0f, (size.width - boxWidth).coerceAtLeast(0f))
            drawRoundRect(
                tooltipBackground,
                topLeft = Offset(boxX, topMargin),
                size = Size(boxWidth, boxHeight),
                cornerRadius = CornerRadius(6.dp.toPx()),
            )
            var lineY = topMargin + padding
            lines.forEach { line ->
                drawText(line, topLeft = Offset(boxX + padding, lineY))
                lineY += line.size.height
            }
        }
    }
}

private val ChartLeftMargin = 42.dp
