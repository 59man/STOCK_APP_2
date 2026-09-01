// Tickers with a public but undocumented provider-website data feed instead
// of a Yahoo/Stooq listing. Auto-fetched via a dedicated server proxy route
// (see server/index.js) instead of manual price entry.
// The three LU entries are the raw ISIN used directly as the app's ticker.
export const FUND_PROVIDER_TICKERS: Record<string, { provider: 'onemarkets' | 'fio-fund' }> = {
  'LU2606422355': { provider: 'onemarkets' }, // OM BlackRock Global Equity Dyn.
  'LU2606421548': { provider: 'onemarkets' }, // OM Fidelity World Equity Income
  'LU2595011649': { provider: 'onemarkets' }, // OM Pictet Global Opport. Alloc.
  'FIOG.PR':      { provider: 'fio-fund' },   // Fio Global Fond CZK
}

export const FUND_PROVIDER_SET = new Set(Object.keys(FUND_PROVIDER_TICKERS))
