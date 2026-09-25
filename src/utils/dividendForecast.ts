import type { Position } from '../types'
import { getDividendTaxRate, type DividendEvent } from './dividends'
import { isOpenLot } from './rowDerivation'

export interface ForecastEntry { ticker: string; date: string; net: number; currency: string }

/** Same calendar date next year; 29 Feb falls back to 28 Feb. */
function nextYear(date: string): string {
  const y = +date.slice(0, 4) + 1
  const md = date.slice(5)
  const leap = (y % 4 === 0 && y % 100 !== 0) || y % 400 === 0
  return `${y}-${md === '02-29' && !leap ? '02-28' : md}`
}

/**
 * Next 12 months of dividends, estimated by repeating the last 12 months: every event paid in
 * (today − 1 year, today] recurs one year later on the shares held now, net of the default
 * withholding rate. Amounts stay in the event's currency; callers convert. Estimate only.
 * Mirrored by core/calc/DividendForecast.kt against test-fixtures/forecast/cases.json.
 */
export function dividendForecast(positions: Position[], dividends: Map<string, DividendEvent[]>, today: string): ForecastEntry[] {
  const yearAgo = `${+today.slice(0, 4) - 1}${today.slice(4)}`
  const held = new Map<string, { qty: number; currency: string }>()
  positions.filter(isOpenLot).forEach((p) =>
    held.set(p.ticker, { qty: (held.get(p.ticker)?.qty ?? 0) + p.quantity, currency: p.currency }))

  const out: ForecastEntry[] = []
  held.forEach(({ qty, currency: lotCurrency }, ticker) => {
    if (qty <= 0) return
    const rate = getDividendTaxRate(ticker)
    ;(dividends.get(ticker.toUpperCase()) ?? [])
      .filter((e) => e.date > yearAgo && e.date <= today)
      .forEach((e) => {
        const date = nextYear(e.date)
        if (date > today) out.push({ ticker, date, net: qty * e.amount * (1 - rate), currency: e.currency ?? lotCurrency })
      })
  })
  return out.sort((a, b) => a.date.localeCompare(b.date) || a.ticker.localeCompare(b.ticker))
}
