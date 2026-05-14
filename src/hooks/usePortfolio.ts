import { useState, useEffect } from 'react'
import { Position } from '../types'
import { SEED_POSITIONS } from '../data/seedPositions'
import { getItem, setItem } from '../utils/storage'

const STORAGE_KEY = 'stock_tracker_positions'
const SEEDED_KEY = 'stock_tracker_seeded'
const SEED_VERSION = '4'
const SEED_VERSION_KEY = 'stock_tracker_seed_version'

function applyMigration(
  existing: Position[],
  seeded: boolean,
  version: string | null,
): { positions: Position[]; changed: boolean } {
  if (existing.length === 0 && !seeded) {
    return { positions: SEED_POSITIONS.map((p) => ({ ...p, id: crypto.randomUUID() })), changed: true }
  }
  if (version !== SEED_VERSION) {
    const existingTickers = new Set(existing.map((p) => p.ticker))
    const toAdd = SEED_POSITIONS
      .filter((p) => !existingTickers.has(p.ticker))
      .map((p) => ({ ...p, id: crypto.randomUUID() }))
    return { positions: toAdd.length > 0 ? [...existing, ...toAdd] : existing, changed: toAdd.length > 0 }
  }
  return { positions: existing, changed: false }
}

function syncLoad(): Position[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    const existing: Position[] = raw ? (JSON.parse(raw) as Position[]) : []
    const seeded = !!localStorage.getItem(SEEDED_KEY)
    const version = localStorage.getItem(SEED_VERSION_KEY)
    const { positions, changed } = applyMigration(existing, seeded, version)
    if (changed) {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(positions))
      localStorage.setItem(SEEDED_KEY, '1')
      localStorage.setItem(SEED_VERSION_KEY, SEED_VERSION)
    }
    return positions
  } catch {
    return []
  }
}

export function usePortfolio() {
  const [positions, setPositions] = useState<Position[]>(syncLoad)
  const [initialized, setInitialized] = useState(false)

  // Async: load from server on mount; fall back to sync-loaded state if server has no data
  useEffect(() => {
    let cancelled = false
    getItem(STORAGE_KEY).then((raw) => {
      if (cancelled) return
      if (raw !== null) {
        try {
          const existing: Position[] = JSON.parse(raw)
          const seeded = !!localStorage.getItem(SEEDED_KEY)
          const version = localStorage.getItem(SEED_VERSION_KEY)
          const { positions: migrated, changed } = applyMigration(existing, seeded, version)
          setPositions(migrated)
          if (changed) {
            localStorage.setItem(SEEDED_KEY, '1')
            localStorage.setItem(SEED_VERSION_KEY, SEED_VERSION)
          }
        } catch { /* keep sync state */ }
      }
      setInitialized(true)
    }).catch(() => { if (!cancelled) setInitialized(true) })
    return () => { cancelled = true }
  }, [])

  // Persist to server + localStorage after initialization
  useEffect(() => {
    if (!initialized) return
    const json = JSON.stringify(positions)
    setItem(STORAGE_KEY, json)
    localStorage.setItem(SEED_VERSION_KEY, SEED_VERSION)
    localStorage.setItem(SEEDED_KEY, '1')
  }, [positions, initialized])

  const addPosition = (p: Omit<Position, 'id'>) => {
    setPositions((prev) => [...prev, { ...p, id: crypto.randomUUID() }])
  }

  const removePositions = (ids: string[]) => {
    const set = new Set(ids)
    setPositions((prev) => prev.filter((p) => !set.has(p.id)))
  }

  const updatePosition = (id: string, updates: Partial<Omit<Position, 'id'>>) => {
    setPositions((prev) => prev.map((p) => (p.id === id ? { ...p, ...updates } : p)))
  }

  return { positions, addPosition, removePositions, updatePosition }
}
