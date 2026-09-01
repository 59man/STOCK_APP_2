// Tickers with genuinely no public price/dividend feed anywhere (Yahoo, Stooq,
// and no known provider-website feed either — see fundProviderTickers.ts for
// tickers that do have one). Prices for entries here come exclusively from
// manual entry (useManualPrices); quote, dividend and history fetches are
// skipped to avoid guaranteed-404 request noise.
export const NO_FEED_TICKERS = new Set<string>([])
