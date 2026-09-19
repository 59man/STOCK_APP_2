import { useCallback, useEffect, useRef, useState } from 'react'
import { proxyFetch } from '../utils/proxyFetch'
import { FX_CONVERTED_TICKERS } from '../data/fxConvertedTickers'
import { FUND_PROVIDER_SET } from '../data/fundProviderTickers'
import {
  Bar,
  localMidnightEpoch,
  needsIntraday,
  resolveAnchorFromDaily,
  resolveAnchorFromIntraday,
} from '../utils/anchorResolution'

/**
 * The price each ticker was worth at midnight in the user's own zone, and the FX rate at the
 * same instant.
 *
 * An anchor is a property of the day, not of the moment — it only changes when the local date
 * rolls over. So it is fetched at most once per ticker per local day and cached at module
 * level, exactly like the quote cache, and a whole refresh cycle costs nothing.
 */

export interface Anchor {
  /** Native currency; null when it could not be resolved. */
  price: number | null
  /** Epoch seconds of the instrument's last trade, used for the "closed since" hint. */
  lastTradedAt: number | null
}

const anchorCache = new Map<string, Anchor>()
const fxAnchorCache = new Map<string, number | null>()

/** Cache key: an anchor belongs to a ticker *and* a local calendar day. */
function keyFor(ticker: string, midnight: number): string {
  return `${ticker.toUpperCase()}::${midnight}`
}

interface ChartMeta {
  regularMarketTime?: number
  regularMarketPrice?: number
  currentTradingPeriod?: { regular?: { start: number; end: number } }
}

interface ChartResult {
  meta?: ChartMeta
  timestamp?: number[]
  indicators?: { quote?: { close?: (number | null)[] }[] }
}

async function fetchChart(ticker: string, query: string): Promise<ChartResult | null> {
  // decode-then-encode rather than a bare encode: FX_CONVERTED_TICKERS stores its symbols
  // already percent-encoded ("GC%3DF"), and encoding those a second time yields "GC%253DF",
  // which 404s. Round-tripping makes the call idempotent for both conventions.
  const symbol = encodeURIComponent(decodeURIComponent(ticker))
  const res = await proxyFetch(`/api/yahoo/v8/finance/chart/${symbol}?${query}`)
  if (!res.ok) return null
  const json = await res.json()
  return json?.chart?.result?.[0] ?? null
}

function barsOf(result: ChartResult | null): Bar[] {
  const stamps = result?.timestamp ?? []
  const closes = result?.indicators?.quote?.[0]?.close ?? []
  const bars: Bar[] = []
  for (let i = 0; i < stamps.length; i++) {
    const close = closes[i]
    if (typeof close === 'number' && Number.isFinite(close)) bars.push({ epoch: stamps[i], close })
  }
  return bars
}

/**
 * The three-step resolution from the design doc.
 *
 * Step 1 is the one that matters for cost: on a weekend, and every weekday before the open,
 * nothing has traded since local midnight and the anchor is simply the current price — so the
 * common case performs a single small request and never touches intraday data at all.
 */
async function resolveAnchor(ticker: string, midnight: number, expandFx = true): Promise<Anchor> {
  const key = ticker.toUpperCase()

  // Fund-provider tickers are priced through their provider's own endpoint and do not exist on
  // Yahoo at all. Asking anyway produced three 404s each on every load; they fall back to the
  // exchange-session figure, which the row marks.
  if (FUND_PROVIDER_SET.has(key)) return { price: null, lastTradedAt: null }

  // FX-converted tickers (XAU, 4GLD.DE, EXUS.DE) have no single Yahoo symbol either: their
  // price is a foreign quote multiplied by an FX pair, so the anchor is the product of the two
  // anchors — exactly how useQuotes builds the live price.
  // expandFx guards against a self-referential entry: 4GLD.DE and EXUS.DE name *themselves*
  // as their own price ticker (only the currency conversion differs), so recursing without it
  // never terminates.
  const fx = expandFx ? FX_CONVERTED_TICKERS[key] : undefined
  if (fx) {
    const [price, rate] = await Promise.all([
      resolveAnchor(fx.priceTicker, midnight, false),
      resolveAnchor(fx.fxTicker, midnight, false),
    ])
    if (price.price === null || rate.price === null) return { price: null, lastTradedAt: price.lastTradedAt }
    return { price: price.price * rate.price, lastTradedAt: price.lastTradedAt }
  }

  const daily = await fetchChart(ticker, 'interval=1d&range=5d')
  const meta = daily?.meta
  const lastTradedAt = meta?.regularMarketTime ?? null

  if (daily === null) return { price: null, lastTradedAt: null }

  if (lastTradedAt !== null && lastTradedAt <= midnight) {
    // Step 1 — nothing has traded today, so the anchor is where we already are.
    return { price: meta?.regularMarketPrice ?? null, lastTradedAt }
  }

  const regular = meta?.currentTradingPeriod?.regular
  if (!needsIntraday(regular ? { regular } : null, midnight)) {
    // Step 2 — daily bars are enough; a bar is stamped at its session open, so the session's
    // length is what turns that into a session end.
    const sessionLength = regular ? regular.end - regular.start : 0
    const price = resolveAnchorFromDaily(barsOf(daily), midnight, sessionLength)
    if (price !== null) return { price, lastTradedAt }
  }

  // Step 3 — a session was in progress at local midnight (always so for crypto), which daily
  // bars cannot answer.
  const intraday = await fetchChart(ticker, 'interval=5m&range=2d')
  return { price: resolveAnchorFromIntraday(barsOf(intraday), midnight), lastTradedAt }
}

async function resolveFxAnchor(from: string, to: string, midnight: number): Promise<number | null> {
  if (from === to) return 1
  const pair = `${from}${to}=X`
  const intraday = await fetchChart(pair, 'interval=5m&range=2d')
  const fromIntraday = resolveAnchorFromIntraday(barsOf(intraday), midnight)
  if (fromIntraday !== null) return fromIntraday
  // FX closes over the weekend, so the 2-day intraday window can be entirely empty; daily
  // bars still carry Friday's rate, which is the correct anchor for a Saturday.
  const daily = await fetchChart(pair, 'interval=1d&range=5d')
  return resolveAnchorFromDaily(barsOf(daily), midnight, 0)
}

export function useDailyAnchors(tickers: string[], zone: string, currencies: string[], displayCurrency: string) {
  const [, forceRender] = useState(0)
  const inFlight = useRef(new Set<string>())

  const midnight = localMidnightEpoch(new Date(), zone)

  useEffect(() => {
    let cancelled = false

    async function run() {
      const wanted = tickers.filter((t) => {
        const key = keyFor(t, midnight)
        return !anchorCache.has(key) && !inFlight.current.has(key)
      })
      const wantedFx = currencies.filter((c) => {
        const key = keyFor(`${c}->${displayCurrency}`, midnight)
        return !fxAnchorCache.has(key) && !inFlight.current.has(key)
      })
      if (wanted.length === 0 && wantedFx.length === 0) return

      await Promise.all([
        ...wanted.map(async (ticker) => {
          const key = keyFor(ticker, midnight)
          inFlight.current.add(key)
          try {
            anchorCache.set(key, await resolveAnchor(ticker, midnight))
          } catch {
            // A failed anchor is not an error state: dailyChange falls back to the
            // exchange-session figure and the row is marked accordingly.
            anchorCache.set(key, { price: null, lastTradedAt: null })
          } finally {
            inFlight.current.delete(key)
          }
        }),
        ...wantedFx.map(async (currency) => {
          const key = keyFor(`${currency}->${displayCurrency}`, midnight)
          inFlight.current.add(key)
          try {
            fxAnchorCache.set(key, await resolveFxAnchor(currency, displayCurrency, midnight))
          } catch {
            fxAnchorCache.set(key, null)
          } finally {
            inFlight.current.delete(key)
          }
        }),
      ])
      if (!cancelled) forceRender((n) => n + 1)
    }

    void run()
    return () => {
      cancelled = true
    }
  }, [tickers.join(','), currencies.join(','), displayCurrency, midnight])

  const anchorFor = useCallback(
    (ticker: string): Anchor | undefined => anchorCache.get(keyFor(ticker, midnight)),
    [midnight],
  )

  const fxAnchorFor = useCallback(
    (from: string): number | null | undefined => {
      if (from === displayCurrency) return 1
      return fxAnchorCache.get(keyFor(`${from}->${displayCurrency}`, midnight))
    },
    [midnight, displayCurrency],
  )

  return { anchorFor, fxAnchorFor }
}
