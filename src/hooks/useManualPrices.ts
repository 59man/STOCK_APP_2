import { useState, useEffect } from 'react'
import { getItem, setItem } from '../utils/storage'

export interface ManualPriceEntry {
  price: number
  updatedAt: string // YYYY-MM-DD
}

type Store = Record<string, ManualPriceEntry>

export function useManualPrices(portfolioId: string) {
  const storageKey = `stock_tracker_manual_prices_${portfolioId}`

  const [prices, setPrices] = useState<Store>(() => {
    if (!portfolioId) return {}
    try {
      const raw = localStorage.getItem(storageKey)
      return raw ? (JSON.parse(raw) as Store) : {}
    } catch { return {} }
  })
  const [initialized, setInitialized] = useState(false)

  useEffect(() => {
    if (!portfolioId) return
    let cancelled = false
    getItem(storageKey).then((raw) => {
      if (cancelled) return
      if (raw !== null) {
        try { setPrices(JSON.parse(raw) as Store) } catch {}
      }
      setInitialized(true)
    }).catch(() => { if (!cancelled) setInitialized(true) })
    return () => { cancelled = true }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    if (!initialized || !portfolioId) return
    setItem(storageKey, JSON.stringify(prices))
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [prices, initialized])

  const setPrice = (ticker: string, price: number) => {
    setPrices((prev) => ({
      ...prev,
      [ticker.toUpperCase()]: { price, updatedAt: new Date().toISOString().slice(0, 10) },
    }))
  }

  const removePrice = (ticker: string) => {
    setPrices((prev) => {
      const next = { ...prev }
      delete next[ticker.toUpperCase()]
      return next
    })
  }

  return { prices, setPrice, removePrice }
}
