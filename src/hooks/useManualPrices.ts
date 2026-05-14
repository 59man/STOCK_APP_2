import { useState, useEffect } from 'react'
import { getItem, setItem } from '../utils/storage'

const STORAGE_KEY = 'stock_tracker_manual_prices'

export interface ManualPriceEntry {
  price: number
  updatedAt: string // YYYY-MM-DD
}

type Store = Record<string, ManualPriceEntry>

export function useManualPrices() {
  const [prices, setPrices] = useState<Store>(() => {
    try {
      const raw = localStorage.getItem(STORAGE_KEY)
      return raw ? (JSON.parse(raw) as Store) : {}
    } catch { return {} }
  })
  const [initialized, setInitialized] = useState(false)

  useEffect(() => {
    let cancelled = false
    getItem(STORAGE_KEY).then((raw) => {
      if (cancelled) return
      if (raw !== null) {
        try { setPrices(JSON.parse(raw) as Store) } catch { /* keep sync state */ }
      }
      setInitialized(true)
    }).catch(() => { if (!cancelled) setInitialized(true) })
    return () => { cancelled = true }
  }, [])

  useEffect(() => {
    if (!initialized) return
    setItem(STORAGE_KEY, JSON.stringify(prices))
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
