import { useEffect, useState, useMemo } from 'react'
import {
  AreaChart, Area, LineChart, Line, Legend, XAxis, YAxis,
  CartesianGrid, Tooltip, ReferenceLine, ResponsiveContainer,
} from 'recharts'
import { Position, Quote } from '../types'
import { DividendEvent, getDividendTaxRate } from '../utils/dividends'
import { FX_CONVERTED_TICKERS, FX_CONVERTED_SET } from '../data/fxConvertedTickers'
import { NO_FEED_TICKERS } from '../data/noFeedTickers'
import { proxyFetch } from '../utils/proxyFetch'

interface ChartPoint {
  label: string
  pnl: number
  costBasis: number
  currentValue: number
}

type PnlView = 'return' | 'value'

type Range = '1M' | '3M' | '6M' | '1Y' | '3Y' | '5Y' | 'All'

const RANGES: Range[] = ['1M', '3M', '6M', '1Y', '3Y', '5Y', 'All']

const RANGE_TO_YAHOO: Record<Range, string> = {
  '1M': '1mo',
  '3M': '3mo',
  '6M': '6mo',
  '1Y': '1y',
  '3Y': '3y',
  '5Y': '5y',
  'All': 'max',
}

type TickerHistory = [string, number][]

function parseHistory(json: unknown): TickerHistory {
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
const yahooHistCurrency = new Map<string, string>()

// Historical FX rates (CUR → CZK daily closes, range=max) so each chart date
// converts at that date's rate instead of today's spot. Fetched once per
// currency per session; range-independent, so cached at module level.
const fxHistCache = new Map<string, TickerHistory>()

async function fetchFxHistory(cur: string): Promise<void> {
  if (fxHistCache.has(cur)) return
  try {
    const res = await proxyFetch(`/api/yahoo/v8/finance/chart/${cur}CZK%3DX?interval=1d&range=max`)
    if (res.ok) fxHistCache.set(cur, parseHistory(await res.json()))
  } catch { /* chartData falls back to spot convert() */ }
}

// Currency the fetched history is in for each ticker
function histCurrency(ticker: string, posCurrency: string): string {
  const t = ticker.toUpperCase()
  return FX_CONVERTED_SET.has(t) ? 'CZK' : yahooHistCurrency.get(t) ?? posCurrency
}

function fxMerge(priceHist: TickerHistory, fxHist: TickerHistory): TickerHistory {
  return priceHist.map(([date, price]): [string, number] | null => {
    const rate = priceAt(fxHist, date)
    return rate !== null ? [date, price * rate] : null
  }).filter((x): x is [string, number] => x !== null)
}

async function fetchYahooHistory(ticker: string, yahooRange: string): Promise<TickerHistory> {
  const fx = FX_CONVERTED_TICKERS[ticker.toUpperCase()]
  if (fx) {
    const [priceRes, fxRes] = await Promise.all([
      proxyFetch(`/api/yahoo/v8/finance/chart/${fx.priceTicker}?interval=1d&range=${yahooRange}`),
      proxyFetch(`/api/yahoo/v8/finance/chart/${fx.fxTicker}?interval=1d&range=${yahooRange}`),
    ])
    const [priceJson, fxJson] = await Promise.all([priceRes.json(), fxRes.json()])
    return fxMerge(parseHistory(priceJson), parseHistory(fxJson))
  }

  const path = `/api/yahoo/v8/finance/chart/${encodeURIComponent(ticker)}?interval=1d&range=${yahooRange}`
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
function priceAt(history: TickerHistory, date: string): number | null {
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
function interpolateDaily(knots: TickerHistory): TickerHistory {
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

function fmtCurrency(v: number, currency: string) {
  return new Intl.NumberFormat('en-US', {
    style: 'currency', currency, maximumFractionDigits: 0,
  }).format(v)
}

function rangeStartDate(range: Range): string {
  const d = new Date()
  switch (range) {
    case '1M': d.setMonth(d.getMonth() - 1); break
    case '3M': d.setMonth(d.getMonth() - 3); break
    case '6M': d.setMonth(d.getMonth() - 6); break
    case '1Y': d.setFullYear(d.getFullYear() - 1); break
    case '3Y': d.setFullYear(d.getFullYear() - 3); break
    case '5Y': d.setFullYear(d.getFullYear() - 5); break
    case 'All': return '0000-00-00'
  }
  return d.toISOString().slice(0, 10)
}

interface Props {
  positions: Position[]
  dividends: Map<string, DividendEvent[]>
  manualPrices?: Record<string, { price: number; updatedAt?: string }>
  quotes?: Map<string, Quote>
  displayCurrency: string
  convert: (amount: number, from: string, to: string) => number
  taxOverrides?: Record<string, number>
}

export function PortfolioPnLChart({ positions, dividends, manualPrices, quotes, displayCurrency, convert, taxOverrides }: Props) {
  const tickers = useMemo(() => [...new Set(positions.map((p) => p.ticker))], [positions])
  const [range, setRange] = useState<Range>(
    () => (localStorage.getItem('chart_range_portfolio') as Range | null) ?? 'All'
  )

  const handleRangeChange = (r: Range) => {
    setRange(r)
    localStorage.setItem('chart_range_portfolio', r)
  }

  const [view, setView] = useState<PnlView>(
    () => (localStorage.getItem('chart_view_portfolio') as PnlView | null) ?? 'return'
  )

  const handleViewChange = (v: PnlView) => {
    setView(v)
    localStorage.setItem('chart_view_portfolio', v)
  }

  const [histories, setHistories] = useState<Map<string, TickerHistory>>(new Map())
  const [fxHistories, setFxHistories] = useState<Map<string, TickerHistory>>(new Map())
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const yahooRange = RANGE_TO_YAHOO[range]

  useEffect(() => {
    if (tickers.length === 0) return
    setLoading(true)
    setError(null)
    Promise.all(
      tickers.map((t) =>
        NO_FEED_TICKERS.has(t.toUpperCase())
          ? Promise.resolve([t, []] as [string, TickerHistory])
          : fetchYahooHistory(t, yahooRange)
              .then((h) => [t, h] as [string, TickerHistory])
              .catch(() => [t, []] as [string, TickerHistory])
      )
    )
      .then(async (entries) => {
        // FX histories for every currency in play (histCurrency reads the
        // yahooHistCurrency cache the ticker fetches above just filled).
        // USD + EUR always: they're selectable display currencies.
        const needed = new Set(['USD', 'EUR'])
        positions.forEach((p) => {
          needed.add(p.currency)
          needed.add(histCurrency(p.ticker, p.currency))
        })
        needed.delete('CZK')
        await Promise.all([...needed].map(fetchFxHistory))
        setFxHistories(new Map(fxHistCache))
        setHistories(new Map(entries))
        setLoading(false)
      })
      .catch((e) => { setError(e.message); setLoading(false) })
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tickers.join(','), yahooRange])

  // For tickers with no Yahoo history but a manual price, build a synthetic history
  // using the actual buy-date prices as anchors (each lot starts at P&L = 0) and
  // the manual price anchored at the date it was entered (updatedAt). Anchoring
  // at "today" would make the funds' entire P&L appear as a fake one-day jump on
  // the final chart bar, drifting forward every day. The knots are then linearly
  // interpolated day-by-day (interpolateDaily) so the gain ramps up smoothly
  // across the whole holding period instead of sitting flat then jumping on the
  // single day the price was entered.
  // Also injects live quote prices as today's final bar so the chart matches the
  // table's live intraday total return (rather than lagging behind at yesterday's close).
  const effectiveHistories = useMemo(() => {
    const map = new Map(histories)
    const today = new Date().toISOString().slice(0, 10)
    // Skip live-price injection on weekends for ordinary exchange-traded
    // tickers: markets are closed, quotes hold Friday's stale close, and
    // injecting today's date would create a phantom non-trading-day bar.
    // FX-converted tickers (XAU/4GLD.DE/EXUS.DE) are exempt: their quote comes
    // from a careful price×FX multiplication (fetchFxConvertedQuote) that's
    // more reliable than the chart's raw historical fxMerge regardless of the
    // calendar day, and XAU specifically tracks COMEX gold futures, which keep
    // moving through the weekend — always prefer the live value for these three.
    const dow = new Date().getDay()
    const isWeekend = dow === 0 || dow === 6
    tickers.forEach((t) => {
      const existing = map.get(t)
      if (existing && existing.length > 0) {
        // Inject live quote price as today's final point so chart matches table.
        // ponytail: skip quotes whose currency mismatches the history (rare Stooq
        // fallback during a Yahoo cooldown) instead of converting — chart just
        // lags at yesterday's close for that ticker until Yahoo recovers.
        const histCur = FX_CONVERTED_SET.has(t.toUpperCase()) ? 'CZK' : yahooHistCurrency.get(t.toUpperCase())
        const skipWeekend = isWeekend && !FX_CONVERTED_SET.has(t.toUpperCase())
        const liveQuote = !skipWeekend ? quotes?.get(t.toUpperCase()) : undefined
        if (liveQuote && (!histCur || liveQuote.currency === histCur) && liveQuote.price > 0 && isFinite(liveQuote.price)) {
          const hist = [...existing]
          if (hist[hist.length - 1][0] === today) {
            hist[hist.length - 1] = [today, liveQuote.price]
          } else {
            hist.push([today, liveQuote.price])
          }
          map.set(t, hist)
        }
        return
      }
      // No Yahoo/Stooq history (e.g. a fund-provider-only ticker like the LU
      // funds or FIOG.PR) — an auto-fetched live quote is more current than
      // a possibly-stale leftover manual price, so prefer it when available.
      const liveQuote = quotes?.get(t.toUpperCase())
      const mp = manualPrices?.[t.toUpperCase()]
      if (!liveQuote && !mp) return                     // no price data at all

      // Collect unique buy-date → buy-price knots from individual lots
      const knots = new Map<string, number>()
      positions
        .filter((p) => p.ticker.toUpperCase() === t.toUpperCase())
        .forEach((p) => { if (!knots.has(p.buyDate)) knots.set(p.buyDate, p.buyPrice) })

      // Latest-known-price knot (wins over a same-day buy knot); lots bought
      // after that date still start at P&L = 0 via their own knot.
      if (liveQuote && liveQuote.price > 0 && isFinite(liveQuote.price)) {
        knots.set(today, liveQuote.price)
      } else if (mp) {
        knots.set(mp.updatedAt?.slice(0, 10) ?? today, mp.price)
      }
      const sortedKnots: TickerHistory = [...knots.entries()]
        .sort(([a], [b]) => a.localeCompare(b))
      map.set(t, interpolateDaily(sortedKnots))
    })
    return map
  }, [histories, manualPrices, quotes, positions, tickers])

  const firstBuyDate = useMemo(() =>
    positions.length === 0 ? '0000-00-00'
      : positions.reduce((min, p) => p.buyDate < min ? p.buyDate : min, positions[0].buyDate)
  , [positions])

  const chartData = useMemo<ChartPoint[]>(() => {
    if (effectiveHistories.size === 0) return []

    const cutoff = range === 'All' ? firstBuyDate : rangeStartDate(range)

    const dateSet = new Set<string>()
    effectiveHistories.forEach((h) => h.forEach(([d]) => { if (d >= cutoff) dateSet.add(d) }))
    const sortedDates = [...dateSet].sort()
    if (sortedDates.length === 0) return []

    // Convert at the FX rate of a specific date (step-lookup, forward-filled).
    // Falls back to today's spot convert() when a rate is missing (fetch failed
    // or date precedes the FX series).
    const fxAt = (cur: string, date: string): number | null =>
      cur === 'CZK' ? 1 : priceAt(fxHistories.get(cur) ?? [], date)
    const convertAt = (amount: number, from: string, to: string, date: string): number => {
      if (from === to) return amount
      const f = fxAt(from, date)
      const t = fxAt(to, date)
      return f !== null && t !== null ? (amount * f) / t : convert(amount, from, to)
    }

    return sortedDates.map((date) => {
      // Price-based P&L — convert each position's P&L from its native history
      // currency at each date's own FX rate, so historical points don't drift
      // with today's spot rate.
      let pricePnl = 0
      let costBasis = 0
      let currentValue = 0
      positions.forEach((pos) => {
        if (pos.buyDate > date) return

        // If this lot was sold on or before this date, use the frozen realized gain
        // so the chart matches the table's realizedPnl (not live market price).
        // Sold lots contribute 0 to costBasis/currentValue — those track capital
        // currently deployed in open positions, not lifetime invested capital.
        if (pos.sellDate && pos.sellDate <= date && pos.sellPrice != null) {
          pricePnl += convertAt((pos.sellPrice - pos.buyPrice) * pos.quantity, pos.currency, displayCurrency, pos.sellDate)
          return
        }

        // Cost basis doesn't need a price feed — count it even if the ticker's
        // history failed to load (e.g. a Yahoo rate-limit cooldown) or a manual
        // price hasn't been entered yet, so the lot never silently vanishes from
        // the chart the way it would if this were gated behind a price lookup.
        const costBasisInDisplay = convertAt(pos.buyPrice * pos.quantity, pos.currency, displayCurrency, pos.buyDate)
        costBasis += costBasisInDisplay

        const h = effectiveHistories.get(pos.ticker)
        const price = h && h.length > 0 ? priceAt(h, date) : null
        if (price === null) {
          // No price available for this date — value the lot at cost, mirroring
          // the table's currentPrice ?? avgBuyPrice fallback, so Current Value
          // never disagrees with Cost Basis just because a feed is down.
          // pricePnl simply omits this lot's unrealized gain, same as before.
          currentValue += costBasisInDisplay
          return
        }
        const hCurrency = histCurrency(pos.ticker, pos.currency)
        // Normalise buy price to history currency at the buy date's rate — that is
        // the actual exchange the trade settled at (e.g. EUR paid for JPY shares).
        const buyInHistCurrency = convertAt(pos.buyPrice, pos.currency, hCurrency, pos.buyDate)
        const lotPricePnl = convertAt((price - buyInHistCurrency) * pos.quantity, hCurrency, displayCurrency, date)
        pricePnl += lotPricePnl
        // Current Value = frozen Cost Basis + this lot's Total Return price gain,
        // rather than an independent price(date)*qty conversion at the date's own
        // rate. A plain re-conversion would silently include currency-translation
        // gain/loss (buy-date rate vs. date's rate) that Total Return's pricePnl
        // does not — the two views would then disagree on a foreign-currency lot
        // whose native price never moved. Reusing lotPricePnl guarantees
        // currentValue - costBasis === pricePnl for this lot, exactly, at every date.
        currentValue += costBasisInDisplay + lotPricePnl
      })

      // Dividend P&L — converted at the ex-date's rate (frozen thereafter)
      let divPnl = 0
      positions.forEach((pos) => {
        const divs = dividends.get(pos.ticker.toUpperCase()) ?? []
        const defaultRate = getDividendTaxRate(pos.ticker)
        for (const div of divs) {
          if (div.date > date) break
          // Only count dividends received while the lot was held (matches calcNetDividends)
          if (pos.buyDate <= div.date && (!pos.sellDate || pos.sellDate > div.date)) {
            const rate = taxOverrides?.[`${pos.ticker.toUpperCase()}::${div.date}`] ?? defaultRate
            divPnl += convertAt(pos.quantity * div.amount * (1 - rate), div.currency ?? pos.currency, displayCurrency, div.date)
          }
        }
      })

      return {
        label: new Date(date).toLocaleDateString('en-US', {
          month: 'short', day: 'numeric',
          year: (range === 'All' || range === '5Y' || range === '3Y') ? '2-digit' : undefined,
        }),
        pnl: Math.round(pricePnl + divPnl),
        costBasis: Math.round(costBasis),
        currentValue: Math.round(currentValue),
      }
    })
  }, [effectiveHistories, fxHistories, positions, dividends, range, firstBuyDate, taxOverrides, displayCurrency, convert])

  const values = chartData.map((d) => d.pnl)
  const minVal = values.length ? Math.min(...values) : 0
  const maxVal = values.length ? Math.max(...values) : 0
  const finalPnl = values[values.length - 1] ?? 0
  const color = finalPnl >= 0 ? '#22c55e' : '#ef4444'
  const pad = Math.max(Math.abs(maxVal), Math.abs(minVal)) * 0.08 || 1000

  const valueMax = chartData.length
    ? Math.max(...chartData.map((d) => Math.max(d.costBasis, d.currentValue)))
    : 0
  const valuePad = valueMax * 0.08 || 1000

  return (
    <div className="chart-container">
      <div className="chart-header">
        <div>
          <h3>{view === 'return' ? 'Portfolio Total Return' : 'Portfolio Value'}</h3>
          <span style={{ fontSize: 10, color: '#666' }}>
            {view === 'return'
              ? 'price P&L + net dividends (after withholding tax)'
              : 'capital in open positions vs. mark-to-market value'}
          </span>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
          {view === 'return' && !loading && values.length > 0 && (
            <span className={finalPnl >= 0 ? 'gain' : 'loss'} style={{ fontSize: 13, fontWeight: 600 }}>
              {fmtCurrency(finalPnl, displayCurrency)}
            </span>
          )}
          <div className="pie-group-toggle">
            <button
              className={`pie-group-btn${view === 'return' ? ' active' : ''}`}
              onClick={() => handleViewChange('return')}
            >
              Total Return
            </button>
            <button
              className={`pie-group-btn${view === 'value' ? ' active' : ''}`}
              onClick={() => handleViewChange('value')}
            >
              Portfolio Value
            </button>
          </div>
          <div className="range-tabs">
            {RANGES.map((r) => (
              <button
                key={r}
                className={`range-tab${range === r ? ' active' : ''}`}
                onClick={() => handleRangeChange(r)}
              >
                {r}
              </button>
            ))}
          </div>
        </div>
      </div>

      {loading && <div className="chart-placeholder">Loading portfolio history…</div>}
      {!loading && error && <div className="chart-placeholder error-text">History error: {error}</div>}
      {!loading && !error && chartData.length === 0 && <div className="chart-placeholder">No data for this range.</div>}
      {!loading && !error && chartData.length > 0 && view === 'return' && (
        <ResponsiveContainer width="100%" height={260}>
          <AreaChart data={chartData} margin={{ top: 8, right: 8, left: 8, bottom: 0 }}>
            <defs>
              <linearGradient id="pnlGrad" x1="0" y1="0" x2="0" y2="1">
                <stop offset="5%"  stopColor={color} stopOpacity={0.3} />
                <stop offset="95%" stopColor={color} stopOpacity={0} />
              </linearGradient>
            </defs>
            <CartesianGrid strokeDasharray="3 3" stroke="#2a2a3a" />
            <XAxis
              dataKey="label"
              tick={{ fontSize: 10, fill: '#888' }}
              interval="preserveStartEnd"
              minTickGap={30}
              tickLine={false}
            />
            <YAxis
              domain={[minVal - pad, maxVal + pad]}
              tick={{ fontSize: 10, fill: '#888' }}
              tickLine={false}
              axisLine={false}
              tickFormatter={(v) =>
                Math.abs(v) >= 1000
                  ? `${(v / 1000).toFixed(1).replace(/\.0$/, '')}k`
                  : parseFloat(v.toFixed(2)).toString()
              }
              width={42}
            />
            <ReferenceLine y={0} stroke="#555" strokeDasharray="4 4" />
            <Tooltip
              contentStyle={{ background: '#1e1e2e', border: '1px solid #333', borderRadius: 6 }}
              labelStyle={{ color: '#aaa' }}
              formatter={(v: number) => [fmtCurrency(v, displayCurrency), 'Total Return']}
            />
            <Area
              type="monotone"
              dataKey="pnl"
              stroke={color}
              strokeWidth={2}
              fill="url(#pnlGrad)"
              dot={false}
            />
          </AreaChart>
        </ResponsiveContainer>
      )}
      {!loading && !error && chartData.length > 0 && view === 'value' && (
        <ResponsiveContainer width="100%" height={260}>
          <LineChart data={chartData} margin={{ top: 8, right: 8, left: 8, bottom: 0 }}>
            <CartesianGrid strokeDasharray="3 3" stroke="#2a2a3a" />
            <XAxis
              dataKey="label"
              tick={{ fontSize: 10, fill: '#888' }}
              interval="preserveStartEnd"
              minTickGap={30}
              tickLine={false}
            />
            <YAxis
              domain={[0, valueMax + valuePad]}
              tick={{ fontSize: 10, fill: '#888' }}
              tickLine={false}
              axisLine={false}
              tickFormatter={(v) =>
                Math.abs(v) >= 1000
                  ? `${(v / 1000).toFixed(1).replace(/\.0$/, '')}k`
                  : parseFloat(v.toFixed(2)).toString()
              }
              width={42}
            />
            <Tooltip
              contentStyle={{ background: '#1e1e2e', border: '1px solid #333', borderRadius: 6 }}
              labelStyle={{ color: '#aaa' }}
              formatter={(v: number, name: string) => [fmtCurrency(v, displayCurrency), name]}
            />
            <Legend wrapperStyle={{ fontSize: 11 }} />
            <Line type="monotone" dataKey="costBasis" name="Cost Basis" stroke="#64748b" strokeWidth={2} dot={false} />
            <Line type="monotone" dataKey="currentValue" name="Current Value" stroke="#3b82f6" strokeWidth={2} dot={false} />
          </LineChart>
        </ResponsiveContainer>
      )}
    </div>
  )
}
