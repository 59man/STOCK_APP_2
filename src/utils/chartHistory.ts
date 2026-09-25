import { FX_CONVERTED_TICKERS, FX_CONVERTED_SET } from '../data/fxConvertedTickers'
import { proxyFetch } from './proxyFetch'
import { yahooChartQuery, yahooFxHistoryQuery } from './yahooWindow'

/**
 * Daily price and FX histories for the charts and the tax panel, moved out of
 * PortfolioPnLChart so every consumer shares one module-level cache per session.
 */

export type TickerHistory = [string, number][]

export function parseHistory(json: unknown): TickerHistory {
  const result = (json as { chart?: { result?: unknown[] } })?.chart?.result?.[0] as {
    timestamp?: number[]
    indicators?: { quote?: { close?: number[] }[] }
  } | undefined
  if (!result) return []
  const ts = result.timestamp ?? []
  const closes = result.indicators?.quote?.[0]?.close ?? []
  return ts
    .map((t, i): [string, number] | null => {
      const date = new Date(t * 1000).toISOString().slice(0, 10)
      const price = closes[i]
      return price && isFinite(price) && price > 0 ? [date, price] : null
    })
    .filter((x): x is [string, number] => x !== null)
    .sort(([a], [b]) => a.localeCompare(b))
}

// ponytail: module-level cache of Yahoo meta.currency per ticker — filled by
// fetchYahooHistory before setHistories fires, so chartData always sees it.
// Avoids threading currency through every history map.
export const yahooHistCurrency = new Map<string, string>()

// Historical FX rates (CUR → CZK daily closes, full history) so each chart date
// converts at that date's rate instead of today's spot. Fetched once per
// currency per session; range-independent, so cached at module level.
export const fxHistCache = new Map<string, TickerHistory>()

export async function fetchFxHistory(cur: string): Promise<void> {
  if (fxHistCache.has(cur)) return
  try {
    const res = await proxyFetch(`/api/yahoo/v8/finance/chart/${cur}CZK%3DX?${yahooFxHistoryQuery()}`)
    if (res.ok) fxHistCache.set(cur, parseHistory(await res.json()))
  } catch { /* chartData falls back to spot convert() */ }
}

// Currency the fetched history is in for each ticker
export function histCurrency(ticker: string, posCurrency: string): string {
  const t = ticker.toUpperCase()
  return FX_CONVERTED_SET.has(t) ? 'CZK' : yahooHistCurrency.get(t) ?? posCurrency
}

function fxMerge(priceHist: TickerHistory, fxHist: TickerHistory): TickerHistory {
  return priceHist.map(([date, price]): [string, number] | null => {
    const rate = priceAt(fxHist, date)
    return rate !== null ? [date, price * rate] : null
  }).filter((x): x is [string, number] => x !== null)
}

export async function fetchYahooHistory(ticker: string, yahooRange: string): Promise<TickerHistory> {
  const fx = FX_CONVERTED_TICKERS[ticker.toUpperCase()]
  if (fx) {
    const [priceRes, fxRes] = await Promise.all([
      proxyFetch(`/api/yahoo/v8/finance/chart/${fx.priceTicker}?${yahooChartQuery(yahooRange)}`),
      proxyFetch(`/api/yahoo/v8/finance/chart/${fx.fxTicker}?${yahooChartQuery(yahooRange)}`),
    ])
    const [priceJson, fxJson] = await Promise.all([priceRes.json(), fxRes.json()])
    return fxMerge(parseHistory(priceJson), parseHistory(fxJson))
  }

  const path = `/api/yahoo/v8/finance/chart/${encodeURIComponent(ticker)}?${yahooChartQuery(yahooRange)}`
  const res = await proxyFetch(path)
  if (!res.ok) throw new Error(`Yahoo history ${res.status}`)
  const json = await res.json()
  let metaCurrency = (json as { chart?: { result?: { meta?: { currency?: string } }[] } })
    ?.chart?.result?.[0]?.meta?.currency
  let hist = parseHistory(json)
  // Yahoo reports LSE prices in pence (GBp) — normalise to GBP
  if (metaCurrency === 'GBp') {
    metaCurrency = 'GBP'
    hist = hist.map(([d, p]): [string, number] => [d, p / 100])
  }
  if (metaCurrency) yahooHistCurrency.set(ticker.toUpperCase(), metaCurrency)
  return hist
}

// Step lookup: the last entry at-or-before `date`, falling back to the first
// entry after `date` when that's actually closer. A round-the-clock feed's
// live/final bar (e.g. USDCZK=X) can be timestamped just past UTC midnight,
// landing one calendar day later than a same-session bar it needs to line up
// with (e.g. GC=F, gold futures, trading nearly 24/7) — plain forward-fill
// would then grab the previous day's stale value instead.
export function priceAt(history: TickerHistory, date: string): number | null {
  let lo = 0, hi = history.length - 1, beforeIdx = -1
  while (lo <= hi) {
    const mid = (lo + hi) >> 1
    if (history[mid][0] <= date) { beforeIdx = mid; lo = mid + 1 } else hi = mid - 1
  }
  const before = beforeIdx >= 0 ? history[beforeIdx] : null
  const after = lo < history.length ? history[lo] : null
  if (!before) return after ? after[1] : null
  if (!after) return before[1]
  const dayMs = 86400000
  const beforeGap = new Date(date).getTime() - new Date(before[0]).getTime()
  const afterGap = new Date(after[0]).getTime() - new Date(date).getTime()
  return afterGap <= dayMs && afterGap <= beforeGap ? after[1] : before[1]
}

// Linearly interpolate day-by-day between sorted (date, price) knots, so a
// manual-priced fund's gain accrues gradually across its whole holding period
// instead of sitting flat then jumping on the single day the price was last
// entered (priceAt is a step lookup — it only shows a smooth ramp if the
// underlying series actually has a point for every day in between).
export function interpolateDaily(knots: TickerHistory): TickerHistory {
  if (knots.length <= 1) return knots
  const out: TickerHistory = []
  for (let i = 0; i < knots.length - 1; i++) {
    const [startDate, startPrice] = knots[i]
    const [endDate, endPrice] = knots[i + 1]
    const startMs = new Date(startDate).getTime()
    const endMs = new Date(endDate).getTime()
    const totalDays = Math.round((endMs - startMs) / 86400000)
    if (totalDays <= 0) {
      out.push([startDate, startPrice])
      continue
    }
    for (let d = 0; d < totalDays; d++) {
      const date = new Date(startMs + d * 86400000).toISOString().slice(0, 10)
      out.push([date, startPrice + (endPrice - startPrice) * (d / totalDays)])
    }
  }
  out.push(knots[knots.length - 1])
  return out
}


/**
 * Converts at a given date's own FX rate (CZK-based histories in `fxHistories`), falling back
 * to today's spot `convert` when a rate is missing. Same rule PortfolioPnLChart always used.
 */
export function makeConvertAt(
  fxHistories: Map<string, TickerHistory>,
  spot: (amount: number, from: string, to: string) => number,
) {
  const fxAt = (cur: string, date: string): number | null =>
    cur === 'CZK' ? 1 : priceAt(fxHistories.get(cur) ?? [], date)
  return (amount: number, from: string, to: string, date: string): number => {
    if (from === to) return amount
    const f = fxAt(from, date)
    const t = fxAt(to, date)
    return f !== null && t !== null ? (amount * f) / t : spot(amount, from, to)
  }
}
