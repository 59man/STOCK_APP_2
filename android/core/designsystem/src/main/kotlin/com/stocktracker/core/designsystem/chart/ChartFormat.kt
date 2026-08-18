package com.stocktracker.core.designsystem.chart

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * "2.7k" for magnitudes >= 1000 (trailing ".0" stripped), a plain 2-decimal
 * number otherwise. Mirrors the Y-axis tickFormatter shared by PriceChart.tsx
 * and PortfolioPnLChart.tsx.
 */
fun formatChartValue(value: Double): String {
    if (abs(value) >= 1000) {
        val thousands = String.format(Locale.US, "%.1f", value / 1000)
        return (if (thousands.endsWith(".0")) thousands.dropLast(2) else thousands) + "k"
    }
    val rounded = (value * 100).roundToLong() / 100.0
    if (rounded == rounded.toLong().toDouble()) return rounded.toLong().toString()
    val text = String.format(Locale.US, "%.2f", rounded)
    return text.trimEnd('0').trimEnd('.')
}

/**
 * Indices to render as X-axis labels, evenly spaced at roughly every
 * [count]/[maxLabels] points. The last index always appears, but *replaces*
 * (rather than joins) the preceding step when the two would otherwise land
 * within half a step of each other — an unconditional "always show the last
 * index" produced overlapping labels whenever count-1 wasn't a multiple of
 * the step.
 */
fun sparseLabelIndices(count: Int, maxLabels: Int = 5): List<Int> {
    if (count <= 0) return emptyList()
    if (count == 1) return listOf(0)
    val step = (count / maxLabels).coerceAtLeast(1)
    val indices = (0 until count step step).toMutableList()
    val lastIndex = count - 1
    if (indices.last() != lastIndex) {
        if (lastIndex - indices.last() >= step / 2) indices.add(lastIndex) else indices[indices.lastIndex] = lastIndex
    }
    return indices
}
