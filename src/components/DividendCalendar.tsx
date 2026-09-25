import { useMemo } from 'react'
import type { Position } from '../types'
import type { DividendEvent } from '../utils/dividends'
import { dividendForecast } from '../utils/dividendForecast'

interface Props {
  positions: Position[]
  dividends: Map<string, DividendEvent[]>
  displayCurrency: string
  convert: (amount: number, from: string, to: string) => number
}

const fmt = (v: number, currency: string) =>
  new Intl.NumberFormat('en-US', { style: 'currency', currency, maximumFractionDigits: 2 }).format(v)

/** Upcoming dividends, estimated from last year's payments on today's holdings. */
export function DividendCalendar({ positions, dividends, displayCurrency, convert }: Props) {
  const today = new Date().toISOString().slice(0, 10)
  const entries = useMemo(() => dividendForecast(positions, dividends, today), [positions, dividends, today])
  if (entries.length === 0) return null
  const total = entries.reduce((s, e) => s + convert(e.net, e.currency, displayCurrency), 0)

  return (
    <details className="tax-panel div-calendar">
      <summary>
        Dividend calendar — next 12 months <span className="muted">(estimated, {fmt(total, displayCurrency)} net)</span>
      </summary>
      <div className="tax-table-wrap">
        <table className="tax-table">
          <thead>
            <tr><th>Est. date</th><th>Ticker</th><th>Net</th></tr>
          </thead>
          <tbody>
            {entries.map((e) => (
              <tr key={`${e.ticker}-${e.date}`}>
                <td>{e.date}</td>
                <td>{e.ticker}</td>
                <td className="gain">{fmt(convert(e.net, e.currency, displayCurrency), displayCurrency)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="tax-footnote">
        Estimated by repeating each holding's last 12 months of payments on the shares held today, after default
        withholding tax. Real dates and amounts will differ.
      </p>
    </details>
  )
}
