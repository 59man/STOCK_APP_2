// Single source of truth for tickers whose price must be fetched in a
// foreign currency and multiplied by the CZK FX rate.
// Used by: useQuotes · PriceChart · PortfolioPnLChart
// To add a new asset, add one entry here only.

export interface FxEntry {
  /** Yahoo Finance price ticker, URL-encoded */
  priceTicker: string
  /** Yahoo Finance FX pair that returns CZK per 1 unit of foreign currency */
  fxTicker: string
  /** Displayed when Yahoo name lookup fails */
  fallbackName?: string
}

export const FX_CONVERTED_TICKERS: Record<string, FxEntry> = {
  'XAU':     { priceTicker: 'GC%3DF',  fxTicker: 'USDCZK%3DX', fallbackName: 'Gold (XAU)' },
  '4GLD.DE': { priceTicker: '4GLD.DE', fxTicker: 'EURCZK%3DX', fallbackName: 'Xetra-Gold' },
  'EXUS.DE': { priceTicker: 'EXUS.DE', fxTicker: 'EURCZK%3DX', fallbackName: 'iShares MSCI World ex USA' },
}

/** Pre-built Set for fast O(1) membership checks */
export const FX_CONVERTED_SET = new Set(Object.keys(FX_CONVERTED_TICKERS))
