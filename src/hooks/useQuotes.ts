import { useState, useCallback, useRef } from 'react'
import { Quote } from '../types'
import { FX_CONVERTED_TICKERS, FX_CONVERTED_SET } from '../data/fxConvertedTickers'

const CACHE_TTL = 60_000

interface CacheEntry { quote: Quote; ts: number }
const cache = new Map<string, CacheEntry>()

// Shared backoff: after a Yahoo 429, skip Yahoo entirely for a while instead of
// hammering it once per ticker (Stooq fallback and stale cache still apply).
const YAHOO_COOLDOWN_MS = 120_000
let yahooCooldownUntil = 0

function checkYahooCooldown() {
  if (Date.now() < yahooCooldownUntil) throw new Error('Yahoo rate-limited (429) — retry later')
}

function noteYahoo429(): never {
  yahooCooldownUntil = Date.now() + YAHOO_COOLDOWN_MS
  throw new Error('Yahoo rate-limited (429) — retry later')
}

function withTimeout<T>(p: Promise<T>, ms: number): Promise<T> {
  return Promise.race([
    p,
    new Promise<never>((_, rej) => setTimeout(() => rej(new Error('Timeout')), ms)),
  ])
}

// Last completed daily close strictly before the day of the latest bar.
// With range=5d, meta.previousClose is null for European/FX tickers and
// chartPreviousClose is the close before the whole 5d window (~a week old),
// so neither can be trusted for the daily change. FX pairs also append a
// live extra bar for today, so "penultimate bar" is unreliable too — we
// compare bar dates (in exchange-local time via gmtoffset) instead.
function prevDailyClose(result: {
  timestamp?: number[]
  indicators?: { quote?: Array<{ close?: (number | null)[] }> }
  meta?: { gmtoffset?: number }
}): number | null {
  const ts = result?.timestamp ?? []
  const closes = result?.indicators?.quote?.[0]?.close ?? []
  const off = result?.meta?.gmtoffset ?? 0
  const day = (t: number) => new Date((t + off) * 1000).toISOString().slice(0, 10)
  const valid: Array<{ d: string; c: number }> = []
  ts.forEach((t, i) => {
    const c = closes[i]
    if (c != null && isFinite(c)) valid.push({ d: day(t), c })
  })
  if (valid.length === 0) return null
  const lastDay = valid[valid.length - 1].d
  for (let i = valid.length - 1; i >= 0; i--) {
    if (valid[i].d !== lastDay) return valid[i].c
  }
  return null
}

async function fetchFxConvertedQuote(ticker: string): Promise<Quote> {
  checkYahooCooldown()
  const { priceTicker, fxTicker, fallbackName = ticker.toUpperCase() } = FX_CONVERTED_TICKERS[ticker.toUpperCase()]
  const [priceRes, fxRes] = await Promise.all([
    withTimeout(fetch(`/api/yahoo/v8/finance/chart/${priceTicker}?interval=1d&range=5d`), 9000),
    withTimeout(fetch(`/api/yahoo/v8/finance/chart/${fxTicker}?interval=1d&range=5d`), 9000),
  ])
  if (priceRes.status === 429 || fxRes.status === 429) noteYahoo429()
  if (!priceRes.ok) throw new Error(`Price fetch ${priceRes.status}`)
  if (!fxRes.ok) throw new Error(`FX fetch ${fxRes.status}`)
  const [priceJson, fxJson] = await Promise.all([priceRes.json(), fxRes.json()])
  const priceResult = priceJson?.chart?.result?.[0]
  const fxResult = fxJson?.chart?.result?.[0]
  const pm = priceResult?.meta
  const fm = fxResult?.meta
  if (!pm?.regularMarketPrice || !fm?.regularMarketPrice) throw new Error('No price data')
  const price = pm.regularMarketPrice * fm.regularMarketPrice
  const prevPriceClose = prevDailyClose(priceResult) ?? pm.previousClose ?? pm.regularMarketPrice
  const prevFxClose = prevDailyClose(fxResult) ?? fm.previousClose ?? fm.regularMarketPrice
  const prevPrice = prevPriceClose * prevFxClose
  return {
    ticker: ticker.toUpperCase(),
    price,
    change: price - prevPrice,
    changePercent: prevPrice > 0 ? ((price - prevPrice) / prevPrice) * 100 : 0,
    currency: 'CZK',
    name: pm.shortName ?? pm.longName ?? fallbackName,
    lastUpdated: new Date().toISOString(),
  }
}

async function fetchFromYahooProxy(ticker: string): Promise<Quote> {
  checkYahooCooldown()
  const path = `/api/yahoo/v8/finance/chart/${encodeURIComponent(ticker)}?interval=1d&range=1d`
  const res = await withTimeout(fetch(path), 9000)
  if (res.status === 429) noteYahoo429()
  if (!res.ok) throw new Error(`Yahoo ${res.status}`)
  const json = await res.json()
  const meta = json?.chart?.result?.[0]?.meta
  if (!meta?.regularMarketPrice) throw new Error('Yahoo: no data')
  const prev = meta.previousClose ?? meta.chartPreviousClose ?? meta.regularMarketPrice
  return {
    ticker: ticker.toUpperCase(),
    price: meta.regularMarketPrice,
    change: meta.regularMarketPrice - prev,
    changePercent: prev > 0 ? ((meta.regularMarketPrice - prev) / prev) * 100 : 0,
    currency: meta.currency ?? 'CZK',
    name: meta.shortName ?? meta.longName ?? ticker,
    lastUpdated: new Date().toISOString(),
  }
}

async function fetchFromStooq(ticker: string): Promise<Quote> {
  const url = `/api/stooq/q/l/?s=${ticker.toLowerCase()}&f=sd2t2ohlcv&h&e=csv`
  const res = await withTimeout(fetch(url), 9000)
  if (!res.ok) throw new Error(`Stooq ${res.status}`)
  const text = await res.text()
  const lines = text.trim().split('\n')
  if (lines.length < 2 || lines[1].trim() === 'N/D') throw new Error('Stooq: no data')
  const [, date, , open, , , close] = lines[1].split(',')
  const price = parseFloat(close)
  const openNum = parseFloat(open)
  if (!isFinite(price) || price === 0) throw new Error('Stooq: invalid price')
  const suffix = ticker.split('.').pop()?.toUpperCase() ?? ''
  const currency = ['PR', 'CZ'].includes(suffix) ? 'CZK' : suffix === 'VI' ? 'EUR' : 'USD'
  return {
    ticker: ticker.toUpperCase(),
    price,
    change: price - openNum,
    changePercent: openNum > 0 ? ((price - openNum) / openNum) * 100 : 0,
    currency,
    name: ticker.toUpperCase(),
    lastUpdated: date ?? new Date().toISOString(),
  }
}

const SOURCES: Array<(t: string) => Promise<Quote>> = [
  (t) => fetchFromYahooProxy(t),
  (t) => fetchFromStooq(t),
]

async function fetchFromSources(ticker: string): Promise<Quote> {
  let lastErr: Error = new Error('All sources failed')
  for (const source of SOURCES) {
    try {
      return await source(ticker)
    } catch (e) {
      lastErr = e instanceof Error ? e : new Error(String(e))
    }
  }
  throw lastErr
}

async function fetchQuote(ticker: string): Promise<Quote> {
  const key = ticker.toUpperCase()
  const now = Date.now()
  const cached = cache.get(key)
  if (cached && now - cached.ts < CACHE_TTL) return cached.quote

  try {
    // FX-converted tickers are stored in CZK in the portfolio
    const quote = FX_CONVERTED_SET.has(key)
      ? await fetchFxConvertedQuote(ticker)
      : await fetchFromSources(ticker)
    cache.set(key, { quote, ts: now })
    return quote
  } catch (e) {
    // All sources failed — a stale quote (lastUpdated shows its age) beats nothing
    if (cached) return cached.quote
    throw e
  }
}

export function useQuotes() {
  const [quotes, setQuotes] = useState<Map<string, Quote>>(new Map())
  const [loading, setLoading] = useState<Set<string>>(new Set())
  const [errors, setErrors] = useState<Map<string, string>>(new Map())
  const inFlight = useRef<Set<string>>(new Set())

  const fetchTickers = useCallback(async (tickers: string[]) => {
    const toFetch = tickers.filter((t) => !inFlight.current.has(t))
    if (toFetch.length === 0) return

    toFetch.forEach((t) => inFlight.current.add(t))
    setLoading((prev) => {
      const next = new Set(prev); toFetch.forEach((t) => next.add(t)); return next
    })

    await Promise.all(
      toFetch.map(async (ticker) => {
        try {
          const quote = await fetchQuote(ticker)
          setQuotes((prev) => new Map(prev).set(ticker.toUpperCase(), quote))
          setErrors((prev) => { const next = new Map(prev); next.delete(ticker.toUpperCase()); return next })
        } catch (e) {
          setErrors((prev) =>
            new Map(prev).set(ticker.toUpperCase(), e instanceof Error ? e.message : 'Failed')
          )
        } finally {
          inFlight.current.delete(ticker)
          setLoading((prev) => { const next = new Set(prev); next.delete(ticker); return next })
        }
      })
    )
  }, [])

  return { quotes, loading, errors, fetchTickers }
}
