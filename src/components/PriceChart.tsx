import { useEffect, useState } from 'react'
import {
  AreaChart,
  Area,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
} from 'recharts'
import { FX_CONVERTED_TICKERS, FX_CONVERTED_SET } from '../data/fxConvertedTickers'

interface ChartPoint {
  date: string
  price: number
  isoDate?: string
}

interface Props {
  ticker: string
  tickerCurrency: string
  displayCurrency: string
  convert: (amount: number, from: string, to: string) => number
}

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

function parseRaw(json: unknown, yahooRange: string): ChartPoint[] {
  const result = (json as { chart?: { result?: unknown[] } })?.chart?.result?.[0] as {
    timestamp?: number[]
    indicators?: { quote?: { close?: number[] }[] }
  } | undefined
  if (!result) return []
  const ts = result.timestamp ?? []
  const closes = result.indicators?.quote?.[0]?.close ?? []
  const showYear = yahooRange === 'max' || yahooRange === '5y' || yahooRange === '3y'
  return ts
    .map((t, i) => ({
      date: new Date(t * 1000).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: showYear ? '2-digit' : undefined }),
      isoDate: new Date(t * 1000).toISOString().slice(0, 10),
      price: closes[i] ?? 0,
    }))
    .filter((d) => isFinite(d.price) && d.price > 0)
}

async function fetchHistory(ticker: string, yahooRange: string): Promise<{ pts: ChartPoint[]; currency: string | null }> {
  const fx = FX_CONVERTED_TICKERS[ticker.toUpperCase()]
  if (fx) {
    const [priceRes, fxRes] = await Promise.all([
      fetch(`/api/yahoo/v8/finance/chart/${fx.priceTicker}?interval=1d&range=${yahooRange}`),
      fetch(`/api/yahoo/v8/finance/chart/${fx.fxTicker}?interval=1d&range=${yahooRange}`),
    ])
    const [priceJson, fxJson] = await Promise.all([priceRes.json(), fxRes.json()])
    const pricePts = parseRaw(priceJson, yahooRange)
    const fxPts = parseRaw(fxJson, yahooRange)
    const fxByIso = new Map(fxPts.map((p) => [p.isoDate!, p.price]))
    const fxSorted = [...fxByIso.keys()].sort()
    const pts = pricePts.map((pt): ChartPoint | null => {
      let lo = 0, hi = fxSorted.length - 1, fxDate: string | null = null
      while (lo <= hi) {
        const mid = (lo + hi) >> 1
        if (fxSorted[mid] <= pt.isoDate!) { fxDate = fxSorted[mid]; lo = mid + 1 } else hi = mid - 1
      }
      const fxRate = fxDate ? fxByIso.get(fxDate) : undefined
      return fxRate ? { date: pt.date, price: pt.price * fxRate } : null
    }).filter((x): x is ChartPoint => x !== null)
    return { pts, currency: 'CZK' }
  }

  const path = `/api/yahoo/v8/finance/chart/${encodeURIComponent(ticker)}?interval=1d&range=${yahooRange}`
  const res = await fetch(path)
  if (!res.ok) throw new Error(`Yahoo ${res.status}`)
  const json = await res.json()
  const currency = (json as { chart?: { result?: { meta?: { currency?: string } }[] } })
    ?.chart?.result?.[0]?.meta?.currency ?? null
  return { pts: parseRaw(json, yahooRange), currency }
}

export function PriceChart({ ticker, tickerCurrency, displayCurrency, convert }: Props) {
  const [range, setRange] = useState<Range>(
    () => (localStorage.getItem('chart_range_price') as Range | null) ?? '1Y'
  )

  const handleRangeChange = (r: Range) => {
    setRange(r)
    localStorage.setItem('chart_range_price', r)
  }
  const [data, setData] = useState<ChartPoint[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // Fallback if the response carries no meta.currency (FX-converted tickers are pre-converted to CZK)
  const fallbackCurrency = FX_CONVERTED_SET.has(ticker.toUpperCase()) ? 'CZK' : tickerCurrency

  useEffect(() => {
    if (!ticker) return
    setLoading(true)
    setError(null)
    fetchHistory(ticker, RANGE_TO_YAHOO[range])
      .then(({ pts, currency }) => {
        const factor = convert(1, currency ?? fallbackCurrency, displayCurrency)
        setData(factor === 1 ? pts : pts.map((p) => ({ ...p, price: p.price * factor })))
      })
      .catch((e) => setError(e.message))
      .finally(() => setLoading(false))
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ticker, range, displayCurrency])

  const min = data.length ? Math.min(...data.map((d) => d.price)) : 0
  const max = data.length ? Math.max(...data.map((d) => d.price)) : 0
  const gain = data.length >= 2 && data[data.length - 1].price >= data[0].price

  return (
    <div className="chart-container">
      <div className="chart-header">
        <h3>{ticker}</h3>
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

      {loading && <div className="chart-placeholder">Loading chart…</div>}
      {!loading && error && <div className="chart-placeholder error-text">Chart unavailable: {error}</div>}
      {!loading && !error && data.length === 0 && <div className="chart-placeholder">No data.</div>}
      {!loading && !error && data.length > 0 && (
        <ResponsiveContainer width="100%" height={220}>
          <AreaChart data={data} margin={{ top: 4, right: 8, left: 0, bottom: 0 }}>
            <defs>
              <linearGradient id="priceGrad" x1="0" y1="0" x2="0" y2="1">
                <stop offset="5%" stopColor={gain ? '#22c55e' : '#ef4444'} stopOpacity={0.3} />
                <stop offset="95%" stopColor={gain ? '#22c55e' : '#ef4444'} stopOpacity={0} />
              </linearGradient>
            </defs>
            <CartesianGrid strokeDasharray="3 3" stroke="#2a2a3a" />
            <XAxis
              dataKey="date"
              tick={{ fontSize: 10, fill: '#888' }}
              interval={Math.floor(data.length / 6)}
              tickLine={false}
            />
            <YAxis
              domain={[min * 0.98, max * 1.02]}
              tick={{ fontSize: 10, fill: '#888' }}
              tickLine={false}
              axisLine={false}
              tickFormatter={(v) => `${v.toFixed(0)}`}
            />
            <Tooltip
              contentStyle={{ background: '#1e1e2e', border: '1px solid #333', borderRadius: 6 }}
              labelStyle={{ color: '#aaa' }}
              formatter={(v: number) => [
                new Intl.NumberFormat('en-US', { style: 'currency', currency: displayCurrency, minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(v),
                'Price',
              ]}
            />
            <Area
              type="monotone"
              dataKey="price"
              stroke={gain ? '#22c55e' : '#ef4444'}
              strokeWidth={2}
              fill="url(#priceGrad)"
              dot={false}
            />
          </AreaChart>
        </ResponsiveContainer>
      )}
    </div>
  )
}
