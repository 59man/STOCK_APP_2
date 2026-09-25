package com.stocktracker.core.calc

import com.stocktracker.core.model.PortfolioRow

/**
 * The web table's per-position columns that the Android detail screen lacked (Qty, Avg Buy,
 * First Buy, Lots, Broker, ISIN, Return %), plus the bought/sold split behind the quantity.
 * Money stays in the row currency; the screen converts for display.
 */
data class PositionFacts(
    val quantityHeld: Double,
    val quantityBought: Double,
    val quantitySold: Double,
    val avgBuyPrice: Double,
    val avgBuyCurrency: String,
    val firstBuyDate: String,
    val lots: Int,
    val brokers: List<String>,
    val isin: String?,
    val returnPercent: Double?,
)

fun positionFacts(row: PortfolioRow): PositionFacts {
    val byDate = row.positions.sortedBy { it.buyDate }
    return PositionFacts(
        quantityHeld = row.positions.filter(::isOpenLot).sumOf { it.quantity },
        quantityBought = row.positions.sumOf { it.quantity },
        quantitySold = row.positions.filter(::isClosedLot).sumOf { it.quantity },
        avgBuyPrice = row.avgBuyPrice,
        avgBuyCurrency = row.currency,
        firstBuyDate = row.firstBuyDate,
        lots = row.lots,
        brokers = byDate.mapNotNull { it.broker?.trim()?.takeIf(String::isNotEmpty) }.distinct(),
        isin = byDate.firstNotNullOfOrNull { it.isin?.trim()?.takeIf(String::isNotEmpty) },
        returnPercent = if (row.costBasis > 0) row.totalReturn / row.costBasis * 100 else null,
    )
}
