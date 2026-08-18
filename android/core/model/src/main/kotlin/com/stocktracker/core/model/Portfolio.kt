package com.stocktracker.core.model

import kotlinx.serialization.Serializable

/** Mirrors `Portfolio` in src/hooks/usePortfolios.ts. */
@Serializable
data class Portfolio(
    val id: String,
    val name: String,
)
