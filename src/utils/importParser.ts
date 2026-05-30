import { Position } from '../types'

export interface ParseResult {
  valid: Position[]
  skipped: number
  dividendTaxOverrides?: Record<string, number>
  manualPrices?: Record<string, { price: number; updatedAt: string }>
}

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
 * Handles four formats:
 *   1. v1 enhanced export { version: 1, positions, dividendTaxOverrides?, manualPrices? }
 *   2. Direct Position[] array (legacy export format)
 *   3. { stock_tracker_positions: "..." } (legacy single-key format)
 *   4. { stock_tracker_positions_<uuid>: "..." } (multi-portfolio format)
 *
 * Returns { valid, skipped, dividendTaxOverrides?, manualPrices? } — callers warn if skipped > 0.
 */
export function parsePositionsFromJson(raw: unknown): ParseResult | null {
  let candidates: unknown[] = []
  let dividendTaxOverrides: Record<string, number> | undefined
  let manualPrices: Record<string, { price: number; updatedAt: string }> | undefined

  // Format 1: v1 enhanced export
  if (raw && typeof raw === 'object' && !Array.isArray(raw)) {
    const obj = raw as Record<string, unknown>
    if (obj.version === 1 && Array.isArray(obj.positions)) {
      candidates = obj.positions
      if (obj.dividendTaxOverrides && typeof obj.dividendTaxOverrides === 'object' && !Array.isArray(obj.dividendTaxOverrides)) {
        dividendTaxOverrides = obj.dividendTaxOverrides as Record<string, number>
      }
      if (obj.manualPrices && typeof obj.manualPrices === 'object' && !Array.isArray(obj.manualPrices)) {
        manualPrices = obj.manualPrices as Record<string, { price: number; updatedAt: string }>
      }
    }
  }

  // Formats 2-4: legacy
  if (candidates.length === 0) {
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
  }

  if (candidates.length === 0) return null

  const valid = candidates.filter(isValidPosition) as Position[]
  return { valid, skipped: candidates.length - valid.length, dividendTaxOverrides, manualPrices }
}
