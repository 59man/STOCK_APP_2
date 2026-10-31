package com.stocktracker.core.model

/**
 * Tickers with no public price/dividend feed anywhere. Ported verbatim from
 * src/data/noFeedTickers.ts, currently empty for the same reason: reserved for
 * a future ticker with genuinely no feed. Prices for these come exclusively
 * from manual entry; all fetches are skipped.
 */
val NO_FEED_TICKERS: Set<String> = emptySet()

/** How to fetch a fund that has a provider-website feed instead of a Yahoo/Stooq listing. */
sealed interface FundProvider {
    data object Onemarkets : FundProvider
    /** [slug] is the fund's URL path segment at fiofondy.cz/cs/podilove-fondy/<slug>. */
    data class FioFund(val slug: String) : FundProvider
}

/** Mirrors src/data/fundProviderTickers.ts; to add a fund, add one line to both. */
val FUND_PROVIDER_TICKERS: Map<String, FundProvider> = mapOf(
    "LU2606422355" to FundProvider.Onemarkets, // OM BlackRock Global Equity Dyn.
    "LU2606421548" to FundProvider.Onemarkets, // OM Fidelity World Equity Income
    "LU2595011649" to FundProvider.Onemarkets, // OM Pictet Global Opport. Alloc.
    "FIOG.PR" to FundProvider.FioFund("globalni-akciovy-fond"), // Fio Global Fond CZK
)

/** Neither no-feed tickers nor provider-priced funds exist on Yahoo: no dividends, history or anchors to ask for. */
fun isOnYahoo(ticker: String): Boolean = ticker.uppercase().let { it !in NO_FEED_TICKERS && it !in FUND_PROVIDER_TICKERS }
