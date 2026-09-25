import type { Position } from '../types'

export type AssetType = Position['type']

/** Display order for chips and labels. Mirrors `TYPE_ORDER` in core/calc/TypeFilter.kt. */
export const TYPE_ORDER: AssetType[] = ['stock', 'etf', 'fund', 'commodity', 'crypto']

const LABELS: Record<AssetType, string> = { stock: 'Stock', etf: 'ETF', fund: 'Fund', commodity: 'Commodity', crypto: 'Crypto' }
export const typeLabel = (t: AssetType): string => LABELS[t]

/**
 * The filter actually in force: the stored selection minus types no longer held, so a stale
 * stored filter can never hide the whole chart. Empty means All.
 */
export function effectiveTypeFilter(selected: AssetType[], held: AssetType[]): Set<AssetType> {
  const heldSet = new Set(held)
  return new Set(TYPE_ORDER.filter((t) => selected.includes(t) && heldSet.has(t)))
}

export function filterPositionsByType(positions: Position[], filter: Set<AssetType>): Position[] {
  return filter.size === 0 ? positions : positions.filter((p) => filter.has(p.type))
}

/** Adds or removes one type; the result is in display order, and empty means All. */
export function toggleType(selected: AssetType[], t: AssetType): AssetType[] {
  const next = selected.includes(t) ? selected.filter((x) => x !== t) : [...selected, t]
  return TYPE_ORDER.filter((x) => next.includes(x))
}

export function typeFilterLabel(filter: Set<AssetType>): string {
  return TYPE_ORDER.filter((t) => filter.has(t)).map(typeLabel).join(' + ')
}

export function parseStoredTypes(raw: string | null): AssetType[] {
  if (!raw) return []
  try {
    const v: unknown = JSON.parse(raw)
    return Array.isArray(v) ? TYPE_ORDER.filter((t) => v.includes(t)) : []
  } catch {
    return []
  }
}
