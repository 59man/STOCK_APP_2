import { useState, useCallback, useRef } from 'react'
import { Quote } from '../types'
import { FX_CONVERTED_TICKERS, FX_CONVERTED_SET } from '../data/fxConvertedTickers'

const CACHE_TTL = 60_000

interface CacheEntry { quote: Quote; ts: number }
const cache = new Map<string, CacheEntry>()

function withTimeout<T>(p: Promise<T>, ms: number): Promise<T> {
  return Promise.race([
    p,
    new Promise<never>((_, rej) => setTimeout(() => rej(new Error('Timeout')), ms)),
  ])
}

async function fetchFxConvertedQuote(ticker: string): Promise<Quote> {
  const { priceTicker, fxTicker, fallbackName = ticker.toUpperCase() } = FX_CONVERTED_TICKERS[ticker.toUpperCase()]
  const [priceRes, fxRes] = await Promise.all([
    withTimeout(fetch(`/api/yahoo/v8/finance/chart/${priceTicker}?interval=1d&range=1d`), 9000),
    withTimeout(fetch(`/api/yahoo/v8/finance/chart/${fxTicker}?interval=1d&range=1d`), 9000),
  ])
  if (priceRes.status === 429 || fxRes.status === 429) throw new Error('Yahoo rate-limited (429) — retry later')
  if (!priceRes.ok) throw new Error(`Price fetch ${priceRes.status}`)
  if (!fxRes.ok) throw new Error(`FX fetch ${fxRes.status}`)
  const [priceJson, fxJson] = await Promise.all([priceRes.json(), fxRes.json()])
  const pm = priceJson?.chart?.result?.[0]?.meta
  const fm = fxJson?.chart?.result?.[0]?.meta
  if (!pm?.regularMarketPrice || !fm?.regularMarketPrice) throw new Error('No price data')
  const price = pm.regularMarketPrice * fm.regularMarketPrice
  const prevPrice = (pm.previousClose ?? pm.regularMarketPrice) * (fm.previousClose ?? fm.regularMarketPrice)
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
  const path = `/api/yahoo/v8/finance/chart/${encodeURIComponent(ticker)}?interval=1d&range=1d`
  const res = await withTimeout(fetch(path), 9000)
  if (res.status === 429) throw new Error('Yahoo rate-limited (429) — retry later')
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

async function fetchQuote(ticker: string): Promise<Quote> {
  const key = ticker.toUpperCase()
  const now = Date.now()
  const cached = cache.get(key)
  if (cached && now - cached.ts < CACHE_TTL) return cached.quote

  // Tickers needing FX conversion (stored in CZK in the portfolio)
  if (FX_CONVERTED_SET.has(key)) {
    const quote = await fetchFxConvertedQuote(ticker)
    cache.set(key, { quote, ts: now })
    return quote
  }

  let lastErr: Error = new Error('All sources failed')
  for (const source of SOURCES) {
    try {
      const quote = await source(ticker)
      cache.set(key, { quote, ts: now })
      return quote
    } catch (e) {
      lastErr = e instanceof Error ? e : new Error(String(e))
    }
  }
  throw lastErr
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
