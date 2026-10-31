import { useState, useEffect, useRef } from 'react'
import { Position } from '../types'
import { SEED_POSITIONS } from '../data/seedPositions'
import { newKeySync, pendingBase, readItem, saveMerged, serverBaseline } from '../utils/storage'
import { randomUUID } from '../utils/uuid'

const SEEDED_KEY = 'stock_tracker_seeded'
const SEED_VERSION = '4'
const SEED_VERSION_KEY = 'stock_tracker_seed_version'

function applyMigration(
  existing: Position[],
  seeded: boolean,
  version: string | null,
): { positions: Position[]; changed: boolean } {
  if (existing.length === 0 && !seeded) {
    return { positions: SEED_POSITIONS.map((p) => ({ ...p, id: randomUUID() })), changed: true }
  }
  if (version !== SEED_VERSION) {
    const existingTickers = new Set(existing.map((p) => p.ticker))
    const toAdd = SEED_POSITIONS
      .filter((p) => !existingTickers.has(p.ticker))
      .map((p) => ({ ...p, id: randomUUID() }))
    return { positions: toAdd.length > 0 ? [...existing, ...toAdd] : existing, changed: toAdd.length > 0 }
  }
  return { positions: existing, changed: false }
}

function syncLoad(storageKey: string): Position[] {
  try {
    const raw = localStorage.getItem(storageKey)
    const existing: Position[] = raw ? (JSON.parse(raw) as Position[]) : []
    const seeded = !!localStorage.getItem(SEEDED_KEY)
    const version = localStorage.getItem(SEED_VERSION_KEY)
    const { positions, changed } = applyMigration(existing, seeded, version)
    if (changed) {
      localStorage.setItem(storageKey, JSON.stringify(positions))
      localStorage.setItem(SEEDED_KEY, '1')
      localStorage.setItem(SEED_VERSION_KEY, SEED_VERSION)
    }
    return positions
  } catch {
    return []
  }
}

export function usePortfolio(portfolioId: string) {
  const storageKey = `stock_tracker_positions_${portfolioId}`

  const [positions, setPositions] = useState<Position[]>(() =>
    portfolioId ? syncLoad(storageKey) : []
  )
  const [initialized, setInitialized] = useState(false)
  const sync = useRef(newKeySync(null)).current

  useEffect(() => {
    if (!portfolioId) return
    let cancelled = false
    sync.disposed = false
    readItem(storageKey).then((read) => {
      if (cancelled) return
      // An edit that never reached the server stays on screen; the save below merges it.
      const pending = pendingBase(storageKey)
      sync.saved = pending ?? serverBaseline(read, JSON.stringify(positions), '[]')
      if (pending === null && read.ok && read.value !== null) {
        try {
          const existing: Position[] = JSON.parse(read.value)
          const seeded = !!localStorage.getItem(SEEDED_KEY)
          const version = localStorage.getItem(SEED_VERSION_KEY)
          const { positions: migrated, changed } = applyMigration(existing, seeded, version)
          setPositions(migrated)
          if (changed) {
            localStorage.setItem(SEEDED_KEY, '1')
            localStorage.setItem(SEED_VERSION_KEY, SEED_VERSION)
          }
        } catch (err) {
          console.warn('[usePortfolio] failed to parse server data:', err)
        }
      }
      setInitialized(true)
    })
    return () => {
      cancelled = true
      sync.disposed = true
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useEffect(() => {
    if (!initialized || !portfolioId) return
    void saveMerged(storageKey, JSON.stringify(positions), sync, (json) => setPositions(JSON.parse(json)))
    localStorage.setItem(SEED_VERSION_KEY, SEED_VERSION)
    localStorage.setItem(SEEDED_KEY, '1')
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [positions, initialized])

  const addPosition = (p: Omit<Position, 'id'>) => {
    setPositions((prev) => [...prev, { ...p, id: randomUUID() }])
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
