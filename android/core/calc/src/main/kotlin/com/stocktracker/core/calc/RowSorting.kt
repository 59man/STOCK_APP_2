package com.stocktracker.core.calc

import com.stocktracker.core.model.PortfolioRow
import com.stocktracker.core.model.SortField
import com.stocktracker.core.model.SortOrder

/**
 * Orders rows for display.
 *
 * Monetary fields are converted to [displayCurrency] before they are compared: sorting a
 * mixed CZK/USD portfolio on raw values compares numbers in different units, so 100 USD would
 * rank below 200 CZK.
 *
 * Ties break on ticker so the ordering is total — otherwise two rows with the same value
 * could swap places between recompositions.
 *
 * Named `sortedForDisplay` rather than `sortedBy` because the latter is a stdlib extension on
 * Iterable and would be shadowed at every call site.
 */
fun List<PortfolioRow>.sortedForDisplay(
    order: SortOrder,
    displayCurrency: String,
    rates: Map<String, Double>,
): List<PortfolioRow> {
    fun money(amount: Double, from: String) = convert(amount, from, displayCurrency, rates)
    val comparator: Comparator<PortfolioRow> = when (order.field) {
        SortField.NAME -> compareBy { it.name.lowercase() }
        SortField.TYPE -> compareBy { it.type.ordinal }
        SortField.VALUE -> compareBy { money(it.currentValue, it.currency) }
        // Already in the display currency, and it is the figure the row shows.
        SortField.TODAY -> compareBy { it.dailyChangeDisplay }
        SortField.TOTAL_RETURN -> compareBy { money(it.totalReturn, it.currency) }
    }
    val directed = if (order.ascending) comparator else comparator.reversed()
    return sortedWith(directed.thenBy { it.ticker })
}
