package com.stocktracker.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Mirrors `PositionType` in src/types/index.ts — wire values must stay lowercase. */
@Serializable
enum class PositionType {
    @SerialName("stock") STOCK,
    @SerialName("etf") ETF,
    @SerialName("fund") FUND,
    @SerialName("commodity") COMMODITY,
}

/**
 * One purchase lot. Field-for-field mirror of `Position` in src/types/index.ts.
 * `buyDate`/`sellDate` stay as plain ISO strings — FIFO matching and row
 * derivation both compare dates lexicographically, not as parsed instants.
 */
@Serializable
data class Position(
    val id: String,
    val ticker: String,
    val name: String,
    val type: PositionType,
    val quantity: Double,
    val buyPrice: Double,
    val buyDate: String,
    val currency: String,
    val broker: String? = null,
    val isin: String? = null,
    val sellPrice: Double? = null,
    val sellDate: String? = null,
)
