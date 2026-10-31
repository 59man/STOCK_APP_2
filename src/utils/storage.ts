import { getApiKey } from './apiKey'

const BASE = '/api/persist'

// `{ ok: true, value: null }` means the server answered and has no such key;
// `{ ok: false }` means we do not know what the server holds. Writing back after the second one
// can overwrite real data, so callers that write must not treat the two alike.
export type ReadResult = { ok: true; value: string | null; version?: string } | { ok: false; unauthorized?: true }

export async function readItem(key: string): Promise<ReadResult> {
  try {
    const res = await fetch(`${BASE}/${encodeURIComponent(key)}`, {
      headers: { 'X-API-Key': getApiKey() },
    })
    if (res.status === 401) return { ok: false, unauthorized: true }
    if (!res.ok) return { ok: false }
    const { value, version } = await res.json() as { value: string | null; version?: string }
    return version === undefined ? { ok: true, value } : { ok: true, value, version }
  } catch {
    return { ok: false }
  }
}

export async function getItem(key: string): Promise<string | null> {
  const read = await readItem(key)
  return read.ok ? read.value : localStorage.getItem(key)
}

/**
 * The JSON the server is taken to hold after a load, for [saveMerged] to merge against.
 * An absent key counts as `emptyJson`, so local data gets restored but an empty state is not
 * written. A failed read counts as `localJson`, so nothing is written until the user edits.
 */
export function serverBaseline(read: ReadResult, localJson: string, emptyJson: string): string {
  if (!read.ok) return localJson
  if (read.value === null) return emptyJson
  try {
    return JSON.stringify(JSON.parse(read.value))
  } catch {
    return localJson
  }
}

function recordsOf(value: unknown): Map<string, unknown> | null {
  if (Array.isArray(value)) {
    const ids = value.map((item) => (item as { id?: unknown } | null)?.id)
    if (!ids.every((id) => typeof id === 'string')) return null
    return new Map(value.map((item, i) => [ids[i] as string, item]))
  }
  return value !== null && typeof value === 'object' ? new Map(Object.entries(value)) : null
}

/**
 * Three-way merge of one stored value: records by `id` for arrays, by key for objects. A record
 * only one side changed takes that side; when both changed it, `local` wins, because it is the
 * edit just made here. Android runs the same merge but prompts instead, as nobody is there to
 * ask at the moment a web save lands. Anything unmergeable returns `local` unchanged.
 */
export function mergeJson(baseJson: string, localJson: string, remoteJson: string): string {
  try {
    const local = JSON.parse(localJson)
    const remote = JSON.parse(remoteJson)
    const [b, l, r] = [recordsOf(JSON.parse(baseJson)), recordsOf(local), recordsOf(remote)]
    if (!b || !l || !r || Array.isArray(local) !== Array.isArray(remote)) return localJson
    const same = (x: unknown, y: unknown) => JSON.stringify(x) === JSON.stringify(y)
    const merged = new Map<string, unknown>()
    for (const key of new Set([...l.keys(), ...r.keys()])) {
      const value = same(l.get(key), b.get(key)) ? r.get(key) : l.get(key)
      if (value !== undefined) merged.set(key, value)
    }
    return JSON.stringify(Array.isArray(local) ? [...merged.values()] : Object.fromEntries(merged))
  } catch {
    return localJson
  }
}

/** Names of the records both sides changed differently since `base`, for telling the user. */
export function findClashes(baseJson: string, localJson: string, remoteJson: string): string[] {
  try {
    const [b, l, r] = [baseJson, localJson, remoteJson].map((j) => recordsOf(JSON.parse(j)))
    if (!b || !l || !r) return []
    const text = (x: unknown) => JSON.stringify(x)
    const names = new Set<string>()
    for (const key of new Set([...l.keys(), ...r.keys()])) {
      const [bv, lv, rv] = [b.get(key), l.get(key), r.get(key)]
      if (text(lv) === text(bv) || text(rv) === text(bv) || text(lv) === text(rv)) continue
      const rec = (lv ?? rv) as { ticker?: string; name?: string } | number
      names.add((typeof rec === 'object' && (rec.ticker ?? rec.name)) || key)
    }
    return [...names]
  } catch {
    return []
  }
}

/** What the page shows about saving: keys still unsaved, and records another device also changed. */
export interface SyncStatus {
  unsaved: string[]
  clashes: string[]
}

let status: SyncStatus = { unsaved: [], clashes: [] }
const listeners = new Set<() => void>()

function setStatus(next: SyncStatus) {
  status = next
  listeners.forEach((l) => l())
}

function markUnsaved(key: string, unsaved: boolean) {
  if (status.unsaved.includes(key) === unsaved) return
  setStatus({ ...status, unsaved: unsaved ? [...status.unsaved, key] : status.unsaved.filter((k) => k !== key) })
}

export const getSyncStatus = () => status
export const dismissClashes = () => setStatus({ ...status, clashes: [] })
export function subscribeSyncStatus(listener: () => void) {
  listeners.add(listener)
  return () => { listeners.delete(listener) }
}

/** Save state of one key: `saved` is the JSON the server was last known to hold, the merge base. */
export interface KeySync {
  saved: string | null
  latest: string | null
  running: boolean
  disposed: boolean
  retry?: ReturnType<typeof setTimeout>
}

export function newKeySync(saved: string | null): KeySync {
  return { saved, latest: null, running: false, disposed: false }
}

const pendingKey = (key: string) => `stock_tracker_unsynced_base:${key}`

/**
 * The merge base of an edit that has not reached the server yet, or null. While set, this browser's
 * copy holds work the server has not seen, so a load must merge it rather than take the server's.
 */
export function pendingBase(key: string): string | null {
  return localStorage.getItem(pendingKey(key))
}

/**
 * Saves `json` to localStorage and merges it into what the server holds right now, so an edit from
 * a stale tab does not undo what another device wrote. `apply` shows the result when the merge
 * brought anything in. The POST carries the version it read, so a write landing in between is
 * refused and merged again. A failed save stays pending and is retried here and on the next load.
 */
export async function saveMerged(
  key: string,
  json: string,
  sync: KeySync,
  apply: (merged: string) => void,
  retryMs = 30_000,
): Promise<void> {
  sync.latest = json
  localStorage.setItem(key, json)
  if (sync.running) return // the loop below picks up `latest`
  sync.running = true
  let refused = 0
  try {
    while (sync.latest !== sync.saved) {
      const sending = sync.latest as string
      const base: string = sync.saved ?? sending
      const read = await readItem(key)
      const merged: string = read.ok ? mergeJson(base, sending, read.value ?? base) : sending
      const result = read.ok ? await postItem(key, merged, read.version) : 'failed'
      // Someone wrote between our read and write. Read again; only a stuck server gets past this.
      if (result === 'conflict' && ++refused < MAX_REFUSED) continue
      if (result !== 'ok') {
        localStorage.setItem(pendingKey(key), base)
        markUnsaved(key, true)
        if (retryMs > 0) {
          clearTimeout(sync.retry)
          sync.retry = setTimeout(() => {
            if (!sync.disposed) void saveMerged(key, sync.latest as string, sync, apply, retryMs)
          }, retryMs)
        }
        return
      }
      localStorage.removeItem(pendingKey(key))
      markUnsaved(key, false)
      // A manual price resolves by recency on every device, so there is nothing to tell anyone.
      if (read.ok && read.value !== null && !key.startsWith('stock_tracker_manual_prices_')) {
        const clashes = findClashes(base, sending, read.value)
        if (clashes.length) setStatus({ ...status, clashes: [...new Set([...status.clashes, ...clashes])] })
      }
      // Edited meanwhile: keep the old base, so the next pass sees what this merge brought in as
      // remote additions. Using `merged` as the base would read them as deleted here.
      if (sync.latest !== sending) continue
      sync.saved = merged
      sync.latest = merged
      if (merged !== sending) {
        localStorage.setItem(key, merged)
        apply(merged)
      }
    }
  } finally {
    sync.running = false
  }
}

const MAX_REFUSED = 5

async function postItem(key: string, value: string, baseVersion?: string): Promise<'ok' | 'conflict' | 'failed'> {
  try {
    const res = await fetch(`${BASE}/${encodeURIComponent(key)}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-API-Key': getApiKey() },
      body: JSON.stringify(baseVersion === undefined ? { value } : { value, baseVersion }),
    })
    if (res.status === 409) return 'conflict'
    if (!res.ok) console.warn(`[storage] save failed for "${key}": HTTP ${res.status}`)
    return res.ok ? 'ok' : 'failed'
  } catch {
    return 'failed'
  }
}

export async function setItem(key: string, value: string): Promise<void> {
  localStorage.setItem(key, value)
  await postItem(key, value)
}
