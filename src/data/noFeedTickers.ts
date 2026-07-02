// Tickers with no public price/dividend feed anywhere (Yahoo and Stooq both 404).
// Prices come exclusively from manual entry (useManualPrices); quote, dividend and
// history fetches are skipped for these to avoid guaranteed-404 request noise.
export const NO_FEED_TICKERS = new Set([
  'FIOG.PR',      // Fio Global Fond — bank-report prices only
  'LU2606422355', // OM BlackRock Global Equity Dyn.
  'LU2606421548', // OM Fidelity World Equity Income
  'LU2595011649', // OM Pictet Global Opport. Alloc.
])
