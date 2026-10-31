import { useState, useEffect, useRef } from 'react'
import { newKeySync, pendingBase, readItem, saveMerged, serverBaseline } from '../utils/storage'

export interface ManualPriceEntry {
  price: number
  updatedAt: string // ISO timestamp; older entries are a plain YYYY-MM-DD
}

type Store = Record<string, ManualPriceEntry>

/** Moves a ticker's manual price with it on rename; a price the new ticker already has stays. */
export function renameTickerPrices(store: Store, oldTicker: string, newTicker: string): Store {
  const { [oldTicker.toUpperCase()]: moved, ...rest } = store
  if (!moved) return store
  return { [newTicker.toUpperCase()]: moved, ...rest }
}

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
  const sync = useRef(newKeySync(null)).current

  useEffect(() => {
    if (!portfolioId) return
    let cancelled = false
    sync.disposed = false
    readItem(storageKey).then((read) => {
      if (cancelled) return
      // An edit that never reached the server stays on screen; the save below merges it.
      const pending = pendingBase(storageKey)
      sync.saved = pending ?? serverBaseline(read, JSON.stringify(prices), '{}')
      if (pending === null && read.ok && read.value !== null) {
        try { setPrices(JSON.parse(read.value) as Store) } catch {}
      }
      setInitialized(true)
    })
    return () => {
      cancelled = true
      sync.disposed = true
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    if (!initialized || !portfolioId) return
    void saveMerged(storageKey, JSON.stringify(prices), sync, (json) => setPrices(JSON.parse(json)))
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [prices, initialized])

  const setPrice = (ticker: string, price: number) => {
    setPrices((prev) => ({
      ...prev,
      [ticker.toUpperCase()]: { price, // A full timestamp: Android resolves a same-ticker conflict by comparing these, and a plain
      // date loses to any time on the same day.
      updatedAt: new Date().toISOString() },
    }))
  }

  const removePrice = (ticker: string) => {
    setPrices((prev) => {
      const next = { ...prev }
      delete next[ticker.toUpperCase()]
      return next
    })
  }

  const renameTicker = (oldTicker: string, newTicker: string) => {
    setPrices((prev) => renameTickerPrices(prev, oldTicker, newTicker))
  }

  return { prices, setPrice, removePrice, renameTicker }
}
