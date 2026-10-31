import { useState, useEffect, useRef } from 'react'
import { newKeySync, pendingBase, readItem, saveMerged, serverBaseline } from '../utils/storage'

// key = `${TICKER}::${YYYY-MM-DD}`, value = custom withholding tax rate (0.0 – 1.0)
type Store = Record<string, number>

/** Moves a ticker's overrides with it on rename; one the new ticker already has for a date stays. */
export function renameTickerTaxes(store: Store, oldTicker: string, newTicker: string): Store {
  const prefix = `${oldTicker.toUpperCase()}::`
  const next: Store = {}
  const moved = Object.entries(store).filter(([k]) => k.startsWith(prefix))
  for (const [k, rate] of Object.entries(store)) if (!k.startsWith(prefix)) next[k] = rate
  for (const [k, rate] of moved) {
    const target = `${newTicker.toUpperCase()}::${k.slice(prefix.length)}`
    if (!(target in next)) next[target] = rate
  }
  return moved.length ? next : store
}

export function useManualDividendTaxes(portfolioId: string) {
  const storageKey = `stock_tracker_div_tax_${portfolioId}`

  const [overrides, setOverrides] = useState<Store>(() => {
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
      sync.saved = pending ?? serverBaseline(read, JSON.stringify(overrides), '{}')
      if (pending === null && read.ok && read.value !== null) {
        try { setOverrides(JSON.parse(read.value) as Store) } catch {}
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
    void saveMerged(storageKey, JSON.stringify(overrides), sync, (json) => setOverrides(JSON.parse(json)))
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [overrides, initialized])

  const setDivTax = (ticker: string, date: string, rate: number) => {
    setOverrides((prev) => ({
      ...prev,
      [`${ticker.toUpperCase()}::${date}`]: rate,
    }))
  }

  const clearDivTax = (ticker: string, date: string) => {
    setOverrides((prev) => {
      const next = { ...prev }
      delete next[`${ticker.toUpperCase()}::${date}`]
      return next
    })
  }

  const renameTicker = (oldTicker: string, newTicker: string) => {
    setOverrides((prev) => renameTickerTaxes(prev, oldTicker, newTicker))
  }

  return { taxOverrides: overrides, setDivTax, clearDivTax, renameTicker }
}
