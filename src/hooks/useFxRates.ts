import { useState, useEffect, useCallback } from 'react'

export type DisplayCurrency = 'CZK' | 'USD' | 'EUR'

// How many CZK equals 1 unit of the given currency
type Rates = { CZK: number; USD: number; EUR: number }

const DEFAULTS: Rates = { CZK: 1, USD: 25.0, EUR: 27.5 }

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
    Promise.all([
      fetchFxRate('USDCZK%3DX'),
      fetchFxRate('EURCZK%3DX'),
    ]).then(([usd, eur]) => {
      setRates({ CZK: 1, USD: usd, EUR: eur })
    }).catch(() => {})
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
