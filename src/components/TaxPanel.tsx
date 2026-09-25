import { useEffect, useMemo, useState } from 'react'
import type { Position } from '../types'
import { fetchFxHistory, fxHistCache, makeConvertAt } from '../utils/chartHistory'
import { isOpenLot } from '../utils/rowDerivation'
import { lotTaxStatus, realizedByYear, SMALL_PROCEEDS_LIMIT_CZK } from '../utils/taxTest'

interface Props {
  positions: Position[]
  convert: (amount: number, from: string, to: string) => number
}

const czk = (v: number) =>
  new Intl.NumberFormat('en-US', { style: 'currency', currency: 'CZK', maximumFractionDigits: 0 }).format(v)

/**
 * Czech 3-year time test: realized gains per year split into taxable and exempt, plus the open
 * lots that pass the test next. CZK at each trade's own date — Czech returns are filed in CZK.
 */
export function TaxPanel({ positions, convert }: Props) {
  const [fxReady, setFxReady] = useState(0)
  const today = new Date().toISOString().slice(0, 10)

  const currencies = useMemo(
    () => [...new Set(positions.map((p) => p.currency))].filter((c) => c !== 'CZK').sort(),
    [positions],
  )
  useEffect(() => {
    let cancelled = false
    Promise.all(currencies.map(fetchFxHistory)).then(() => { if (!cancelled) setFxReady((n) => n + 1) })
    return () => { cancelled = true }
  }, [currencies.join(',')])

  const years = useMemo(() => {
    const convertAt = makeConvertAt(fxHistCache, convert)
    return realizedByYear(positions, (amount, cur, date) => convertAt(amount, cur, 'CZK', date))
    // fxReady re-runs this once the histories land in the module cache
  }, [positions, convert, fxReady])

  const upcoming = useMemo(
    () => positions
      .filter(isOpenLot)
      .map((p) => ({ p, s: lotTaxStatus(p, today) }))
      .filter(({ s }) => s.kind === 'pending')
      .sort((a, b) => a.s.freeFrom.localeCompare(b.s.freeFrom))
      .slice(0, 6),
    [positions, today],
  )

  if (years.length === 0 && upcoming.length === 0) return null

  return (
    <details className="tax-panel">
      <summary>Czech tax — 3-year time test</summary>
      {years.length > 0 && (
        <div className="tax-table-wrap">
          <table className="tax-table">
            <thead>
              <tr><th>Year sold</th><th>Taxable gain</th><th>Exempt gain</th><th>Taxable proceeds</th><th></th></tr>
            </thead>
            <tbody>
              {years.map((y) => (
                <tr key={y.year}>
                  <td>{y.year}</td>
                  <td className={y.gainTaxable >= 0 ? 'gain' : 'loss'}>{czk(y.gainTaxable)}</td>
                  <td className="muted">{czk(y.gainExempt)}</td>
                  <td>{czk(y.proceedsTaxable)}</td>
                  <td>
                    {y.proceedsTaxable > 0 && y.underSmallProceedsLimit && (
                      <span className="tax-badge tax-exempt" title={`Taxable-sale proceeds within ${czk(SMALL_PROCEEDS_LIMIT_CZK)} for the year`}>
                        ≤ 100k
                      </span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {upcoming.length > 0 && (
        <>
          <div className="tax-subtitle">Next lots to pass the test</div>
          <ul className="tax-upcoming">
            {upcoming.map(({ p, s }) => (
              <li key={p.id}>
                <strong>{p.ticker}</strong> <span className="muted">{p.quantity} × bought {p.buyDate}</span>
                <span className="tax-badge tax-pending">tax-free {s.freeFrom}</span>
              </li>
            ))}
          </ul>
        </>
      )}
      <p className="tax-footnote">
        Estimate — not tax advice. Gains converted to CZK at buy- and sell-date rates. Dividends are not included.
      </p>
    </details>
  )
}
