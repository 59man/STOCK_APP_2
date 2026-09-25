package com.stocktracker.core.model

/** A device-local price alert, as the UI sees it. Persisted by core:database's PriceAlertEntity. */
data class PriceAlert(
    val id: String,
    val ticker: String,
    val above: Boolean,
    val threshold: Double,
    val currency: String,
    /** false after firing, until the price comes back across the threshold. */
    val armed: Boolean,
)
