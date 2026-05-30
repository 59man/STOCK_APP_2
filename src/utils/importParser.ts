import { Position } from '../types'

/** Minimal schema guard — rejects positions missing numeric required fields */
function isValidPosition(p: unknown): p is Omit<Position, 'id'> {
  if (!p || typeof p !== 'object') return false
  const x = p as Record<string, unknown>
  return (
    typeof x.ticker === 'string' && x.ticker.trim().length > 0 &&
    typeof x.quantity === 'number' && isFinite(x.quantity) && x.quantity > 0 &&
    typeof x.buyPrice === 'number' && isFinite(x.buyPrice) && x.buyPrice >= 0 &&
    typeof x.buyDate === 'string' && x.buyDate.length > 0
  )
}

/**
 * Parses position data from imported JSON.
 * Handles three formats:
 *   1. Direct Position[] array (export format)
 *   2. { stock_tracker_positions: "..." } (legacy single-key format)
 *   3. { stock_tracker_positions_<uuid>: "..." } (multi-portfolio format)
 *
 * Returns { valid, skipped } — callers should warn if skipped > 0.
 */
export function parsePositionsFromJson(raw: unknown): { valid: Position[]; skipped: number } | null {
  let candidates: unknown[] = []

  if (Array.isArray(raw)) {
    candidates = raw
  } else if (raw && typeof raw === 'object') {
    const obj = raw as Record<string, unknown>

    // Legacy single-key format
    if (typeof obj['stock_tracker_positions'] === 'string') {
      try {
        const parsed = JSON.parse(obj['stock_tracker_positions'])
        if (Array.isArray(parsed)) candidates = parsed
      } catch {}
    }

    // Multi-portfolio format
    if (candidates.length === 0) {
      for (const key of Object.keys(obj)) {
        if (key.startsWith('stock_tracker_positions_') && typeof obj[key] === 'string') {
          try {
            const parsed = JSON.parse(obj[key] as string)
            if (Array.isArray(parsed)) candidates.push(...parsed)
          } catch {}
        }
      }
    }
  }

  if (candidates.length === 0) return null

  const valid = candidates.filter(isValidPosition) as Position[]
  return { valid, skipped: candidates.length - valid.length }
}
