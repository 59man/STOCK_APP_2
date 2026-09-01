// Tickers with a public but undocumented provider-website data feed instead
// of a Yahoo/Stooq listing. Auto-fetched via a dedicated server proxy route
// (see server/index.js) instead of manual price entry.
//
// To add a new fund:
// - onemarkets.cz fund: add `{ provider: 'onemarkets' }` keyed by its ISIN
//   (used directly as the ticker) — the server route is already generic
//   over any ISIN, no server change needed.
// - fiofondy.cz fund: add `{ provider: 'fio-fund', slug }`, where `slug` is
//   the fund's URL path segment at fiofondy.cz/cs/podilove-fondy/<slug>
//   (e.g. 'globalni-akciovy-fond', 'eur-globalni-akciovy-fond') — the
//   session-cookie dance is shared across all Fio fund pages, so this is
//   also just a data-entry, no server change needed.
export type FundProviderEntry =
  | { provider: 'onemarkets' }
  | { provider: 'fio-fund'; slug: string }

export const FUND_PROVIDER_TICKERS: Record<string, FundProviderEntry> = {
  'LU2606422355': { provider: 'onemarkets' }, // OM BlackRock Global Equity Dyn.
  'LU2606421548': { provider: 'onemarkets' }, // OM Fidelity World Equity Income
  'LU2595011649': { provider: 'onemarkets' }, // OM Pictet Global Opport. Alloc.
  'FIOG.PR':      { provider: 'fio-fund', slug: 'globalni-akciovy-fond' }, // Fio Global Fond CZK
}

export const FUND_PROVIDER_SET = new Set(Object.keys(FUND_PROVIDER_TICKERS))
