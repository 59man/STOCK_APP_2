import { useState, useEffect } from 'react'
import { getItem, setItem } from '../utils/storage'

// key = `${TICKER}::${YYYY-MM-DD}`, value = custom withholding tax rate (0.0 – 1.0)
type Store = Record<string, number>

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

  useEffect(() => {
    if (!portfolioId) return
    let cancelled = false
    getItem(storageKey).then((raw) => {
      if (cancelled) return
      if (raw !== null) {
        try { setOverrides(JSON.parse(raw) as Store) } catch {}
      }
      setInitialized(true)
    }).catch(() => { if (!cancelled) setInitialized(true) })
    return () => { cancelled = true }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    if (!initialized || !portfolioId) return
    setItem(storageKey, JSON.stringify(overrides))
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

  return { taxOverrides: overrides, setDivTax, clearDivTax }
}
