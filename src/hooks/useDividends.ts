import { useState, useCallback, useRef } from 'react'
import { DividendEvent, fetchDividendEvents } from '../utils/dividends'

const cache = new Map<string, DividendEvent[]>()

export function useDividends() {
  const [dividends, setDividends] = useState<Map<string, DividendEvent[]>>(new Map())
  const inFlight = useRef<Set<string>>(new Set())

  const fetchTickers = useCallback(async (tickers: string[]) => {
    // Immediately populate from cache
    const cached = tickers.filter((t) => cache.has(t.toUpperCase()))
    if (cached.length > 0) {
      setDividends((prev) => {
        const next = new Map(prev)
        cached.forEach((t) => next.set(t.toUpperCase(), cache.get(t.toUpperCase())!))
        return next
      })
    }

    const toFetch = tickers.filter((t) => !inFlight.current.has(t) && !cache.has(t.toUpperCase()))
    if (toFetch.length === 0) return

    toFetch.forEach((t) => inFlight.current.add(t))

    await Promise.all(
      toFetch.map(async (ticker) => {
        try {
          const events = await fetchDividendEvents(ticker)
          cache.set(ticker.toUpperCase(), events)
          setDividends((prev) => new Map(prev).set(ticker.toUpperCase(), events))
        } catch {
          cache.set(ticker.toUpperCase(), [])
        } finally {
          inFlight.current.delete(ticker)
        }
      })
    )
  }, [])

  return { dividends, fetchTickers }
}
