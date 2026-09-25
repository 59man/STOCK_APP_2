import type { Position } from '../types'
import { lotTaxStatus } from '../utils/taxTest'

/** Czech 3-year time-test status of one lot, shown beside its buy date. */
export function TaxBadge({ lot, today }: { lot: Position; today: string }) {
  const s = lotTaxStatus(lot, today)
  const text =
    s.kind === 'exempt' ? 'tax-free'
    : s.kind === 'taxable' ? 'taxable'
    : s.daysLeft <= 60 ? `tax-free in ${s.daysLeft} d`
    : `tax-free ${s.freeFrom}`
  const title =
    s.kind === 'pending' ? `Held 3 years on ${s.freeFrom} — a sale from then on is exempt (estimate)`
    : s.kind === 'exempt' ? 'Past the 3-year time test (estimate)'
    : 'Sold within 3 years of purchase (estimate)'
  return <span className={`tax-badge tax-${s.kind}`} title={title}>{text}</span>
}
