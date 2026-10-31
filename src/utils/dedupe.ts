import { Position } from '../types'

const EPS = 1e-6
const isClosed = (p: Position) => p.sellPrice != null && !!p.sellDate
const buyKey = (p: Position) =>
  [p.ticker.toUpperCase(), p.buyDate, p.buyPrice.toFixed(6), (p.broker ?? '').toLowerCase()].join('|')

export interface ImportPlan {
  /** The whole portfolio after the import. */
  positions: Position[]
  added: number
  duplicates: number
}

/**
 * Merges a parsed statement into a portfolio, so re-importing one, or one overlapping an earlier
 * one, counts nothing twice. Matches shares of the same buy rather than whole lots, because FIFO
 * splits a buy differently once a later statement contains its sale. A closed incoming lot closes
 * the matching open shares rather than being dropped, so a newer statement can record a sale.
 * Mirrors planImport in Dedupe.kt.
 */
export function planImport(existing: Position[], incoming: Position[]): ImportPlan {
  // `unclaimed`: shares of this lot no incoming lot has matched yet.
  const entries = existing.map((p) => ({ lot: { ...p }, unclaimed: p.quantity }))
  const fresh: Position[] = []
  let duplicates = 0

  // Closed lots first, so they claim open shares before an open lot from the same statement does.
  for (const lot of [...incoming.filter(isClosed), ...incoming.filter((p) => !isClosed(p))]) {
    const claim = (need: number, matches: (p: Position) => boolean, shrink: boolean): number => {
      for (const e of entries) {
        if (need <= EPS) break
        if (buyKey(e.lot) !== buyKey(lot) || !matches(e.lot) || e.unclaimed <= EPS) continue
        const qty = Math.min(e.unclaimed, need)
        e.unclaimed -= qty
        if (shrink) e.lot.quantity -= qty
        need -= qty
      }
      return need
    }

    const unmatched = isClosed(lot)
      ? claim(lot.quantity, (p) => isClosed(p) && p.sellDate === lot.sellDate && p.sellPrice === lot.sellPrice, false)
      : claim(lot.quantity, () => true, false)

    if (unmatched <= EPS) {
      duplicates++
      continue
    }
    if (isClosed(lot)) claim(unmatched, (p) => !isClosed(p), true)
    fresh.push({ ...lot, quantity: unmatched })
  }

  return {
    positions: [...entries.map((e) => e.lot).filter((p) => p.quantity > EPS), ...fresh],
    added: fresh.length,
    duplicates,
  }
}
