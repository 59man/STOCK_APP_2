import { Position } from '../types'

export interface QuoteInfo {
  ticker: string
  type: Position['type']
}

function mapType(quoteType: string | undefined): Position['type'] {
  switch (quoteType) {
    case 'ETF': return 'etf'
    case 'MUTUALFUND': return 'fund'
    case 'COMMODITY': return 'commodity'
    default: return 'stock'
  }
}

async function query(q: string): Promise<{ symbol: string; quoteType: string } | null> {
  try {
    const r = await fetch(`/api/yahoo/v1/finance/search?q=${encodeURIComponent(q)}&lang=en-US`)
    if (!r.ok) return null
    const d = await r.json()
    const quotes: { symbol: string; quoteType: string }[] = d?.quotes ?? []
    return quotes.find(q => q.quoteType !== 'OPTION') ?? quotes[0] ?? null
  } catch { return null }
}

/** Resolve an ISIN → { ticker, type } via Yahoo search */
export async function lookupIsin(isin: string): Promise<QuoteInfo> {
  const hit = await query(isin)
  return { ticker: hit?.symbol ?? isin, type: mapType(hit?.quoteType) }
}

/** Resolve a ticker → { ticker, type } via Yahoo search (ticker unchanged, only type enriched) */
export async function lookupTicker(ticker: string): Promise<QuoteInfo> {
  const hit = await query(ticker)
  return { ticker, type: mapType(hit?.quoteType) }
}

/** Batch-resolve a list of ISINs. Returns map of isin → QuoteInfo */
export async function batchIsins(isins: string[]): Promise<Record<string, QuoteInfo>> {
  const unique = [...new Set(isins)]
  const results = await Promise.all(unique.map(async i => [i, await lookupIsin(i)] as const))
  return Object.fromEntries(results)
}

/** Batch-resolve a list of tickers for type enrichment. Returns map of ticker → QuoteInfo */
export async function batchTickers(tickers: string[]): Promise<Record<string, QuoteInfo>> {
  const unique = [...new Set(tickers)]
  const results = await Promise.all(unique.map(async t => [t, await lookupTicker(t)] as const))
  return Object.fromEntries(results)
}
