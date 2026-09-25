package com.stocktracker.core.calc

import com.stocktracker.core.model.Position
import com.stocktracker.core.model.PositionType

/** Display order for chips and labels. Mirrors `TYPE_ORDER` in src/utils/typeFilter.ts. */
val TYPE_ORDER: List<PositionType> = listOf(
    PositionType.STOCK, PositionType.ETF, PositionType.FUND, PositionType.COMMODITY, PositionType.CRYPTO,
)

fun typeLabel(type: PositionType): String = when (type) {
    PositionType.STOCK -> "Stock"
    PositionType.ETF -> "ETF"
    PositionType.FUND -> "Fund"
    PositionType.COMMODITY -> "Commodity"
    PositionType.CRYPTO -> "Crypto"
}

/**
 * The filter actually in force: the stored selection minus types no longer held, so a stale
 * stored filter can never hide the whole chart. Empty means All.
 */
fun effectiveTypeFilter(selected: Set<PositionType>, held: Set<PositionType>): Set<PositionType> =
    TYPE_ORDER.filter { it in selected && it in held }.toCollection(LinkedHashSet())

fun filterPositionsByType(positions: List<Position>, filter: Set<PositionType>): List<Position> =
    if (filter.isEmpty()) positions else positions.filter { it.type in filter }

/** Adds or removes one type; the result is in display order, and empty means All. */
fun toggleType(selected: Set<PositionType>, type: PositionType): Set<PositionType> {
    val next = if (type in selected) selected - type else selected + type
    return TYPE_ORDER.filter { it in next }.toCollection(LinkedHashSet())
}

fun typeFilterLabel(filter: Set<PositionType>): String =
    TYPE_ORDER.filter { it in filter }.joinToString(" + ") { typeLabel(it) }
