import { useState, useEffect, useCallback } from 'react'

export type DisplayCurrency = 'CZK' | 'USD' | 'EUR'

// How many CZK equals 1 unit of the given currency
type Rates = { CZK: number; USD: number; EUR: number; GBP: number; CHF: number; JPY: number; CAD: number; AUD: number }

const DEFAULTS: Rates = { CZK: 1, USD: 25.0, EUR: 27.5, GBP: 29.0, CHF: 28.0, JPY: 0.16, CAD: 18.0, AUD: 16.0 }

const FX_PAIRS: [keyof Rates, string][] = [
  ['USD', 'USDCZK%3DX'],
  ['EUR', 'EURCZK%3DX'],
  ['GBP', 'GBPCZK%3DX'],
  ['CHF', 'CHFCZK%3DX'],
  ['JPY', 'JPYCZK%3DX'],
  ['CAD', 'CADCZK%3DX'],
  ['AUD', 'AUDCZK%3DX'],
]

async function fetchFxRate(fxTicker: string): Promise<number> {
  const res = await fetch(`/api/yahoo/v8/finance/chart/${fxTicker}?interval=1d&range=1d`)
  if (!res.ok) throw new Error(`FX ${res.status}`)
  const json = await res.json()
  const price = json?.chart?.result?.[0]?.meta?.regularMarketPrice
  if (!price || !isFinite(price)) throw new Error('No FX data')
  return price
}

export function useFxRates() {
  const [rates, setRates] = useState<Rates>(DEFAULTS)

  useEffect(() => {
    Promise.all(FX_PAIRS.map(([, ticker]) => fetchFxRate(ticker).catch(() => null)))
      .then((values) => {
        const next = { ...DEFAULTS }
        FX_PAIRS.forEach(([key], i) => { if (values[i] !== null) next[key] = values[i]! })
        setRates(next)
      })
      .catch((err) => {
        console.warn('[useFxRates] failed to fetch FX rates, using defaults:', err instanceof Error ? err.message : err)
      })
  }, [])

  const convert = useCallback(
    (amount: number, from: string, to: string): number => {
      if (from === to || !isFinite(amount)) return amount
      const fromInCzk = rates[from as keyof Rates] ?? 1
      const toInCzk = rates[to as keyof Rates] ?? 1
      return (amount * fromInCzk) / toInCzk
    },
    [rates]
  )

  return { rates, convert }
}
