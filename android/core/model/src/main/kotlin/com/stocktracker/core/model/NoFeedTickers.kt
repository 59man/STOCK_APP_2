package com.stocktracker.core.model

/**
 * Tickers with no public price/dividend feed anywhere (Yahoo and Stooq both
 * 404). Ported verbatim from src/data/noFeedTickers.ts — prices come
 * exclusively from manual entry; quote/dividend/history fetches are skipped
 * to avoid guaranteed-404 request noise.
 */
val NO_FEED_TICKERS: Set<String> = setOf(
    "FIOG.PR",      // Fio Global Fond — bank-report prices only
    "LU2606422355", // OM BlackRock Global Equity Dyn.
    "LU2606421548", // OM Fidelity World Equity Income
    "LU2595011649", // OM Pictet Global Opport. Alloc.
)
