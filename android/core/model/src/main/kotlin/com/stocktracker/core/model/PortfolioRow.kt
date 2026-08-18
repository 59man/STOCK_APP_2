package com.stocktracker.core.model

import kotlinx.serialization.Serializable

/**
 * One aggregated ticker row, computed by core:calc — never persisted or synced.
 * Mirrors `PortfolioRow` in src/types/index.ts.
 */
@Serializable
data class PortfolioRow(
    val ids: List<String>,
    val ticker: String,
    val name: String,
    val type: PositionType,
    val currency: String,
    val nativeCurrency: String,
    val lots: Int,
    val positions: List<Position>,
    val totalQuantity: Double,
    val avgBuyPrice: Double,
    val firstBuyDate: String,
    val currentPrice: Double,
    val currentValue: Double,
    val costBasis: Double,
    val pnl: Double,
    val pnlPercent: Double,
    val dividendIncome: Double,
    val totalReturn: Double,
    val loading: Boolean,
    val error: String? = null,
    val priceIsManual: Boolean,
    val manualPriceDate: String? = null,
    val irr: Double? = null,
    val isClosed: Boolean,
    val dailyChange: Double,
)
