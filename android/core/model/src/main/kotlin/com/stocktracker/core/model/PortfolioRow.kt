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
    /** Shares still held (open lots). [totalQuantity] also counts sold lots. */
    val openQuantity: Double = totalQuantity,
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
    /** Price-only change in row currency, kept for existing consumers. */
    val dailyChange: Double,
    /** Headline today's change in the DISPLAY currency, anchored to local midnight, FX included. */
    val dailyChangeDisplay: Double = 0.0,
    val dailyChangePercent: Double = 0.0,
    /** Broker-style figure: price move only, FX held at today's rate, in the display currency. */
    val dailyPriceOnlyDisplay: Double = 0.0,
    /** Which rule produced the number — the UI marks a fallback and explains a zero. */
    val dailyChangeMethod: String = "anchored",
    /** Epoch seconds of the last trade, for the "closed since Fri" hint. */
    val lastTradedAt: Long? = null,
)
