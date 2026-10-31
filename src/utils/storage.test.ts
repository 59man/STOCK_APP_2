import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { dismissClashes, findClashes, getItem, getSyncStatus, mergeJson, newKeySync, pendingBase, readItem, saveMerged, serverBaseline } from './storage'

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status })
}

beforeEach(() => {
  const data = new Map<string, string>()
  vi.stubGlobal('localStorage', {
    getItem: (k: string) => data.get(k) ?? null,
    setItem: (k: string, v: string) => void data.set(k, v),
    removeItem: (k: string) => void data.delete(k),
  })
})

afterEach(() => vi.unstubAllGlobals())

describe('readItem', () => {
  it('reports a key the server does not have as a successful null', async () => {
    vi.stubGlobal('fetch', async () => jsonResponse({ value: null }))
    expect(await readItem('k')).toEqual({ ok: true, value: null })
  })

  it('reports the stored value', async () => {
    vi.stubGlobal('fetch', async () => jsonResponse({ value: '[1]' }))
    expect(await readItem('k')).toEqual({ ok: true, value: '[1]' })
  })

  it('reports a non-OK response as a failed read, not as an absent key', async () => {
    vi.stubGlobal('fetch', async () => jsonResponse({ error: 'nope' }, 500))
    expect(await readItem('k')).toEqual({ ok: false })
  })

  it('reports a dead network as a failed read', async () => {
    vi.stubGlobal('fetch', async () => { throw new TypeError('network') })
    expect(await readItem('k')).toEqual({ ok: false })
  })
})

describe('getItem', () => {
  it('still falls back to localStorage when the read fails', async () => {
    localStorage.setItem('k', 'local')
    vi.stubGlobal('fetch', async () => jsonResponse({}, 500))
    expect(await getItem('k')).toBe('local')
  })
})

describe('serverBaseline', () => {
  it('after a failed read treats local state as already saved, so nothing is written blindly', () => {
    expect(serverBaseline({ ok: false }, '[{"id":"stale"}]', '[]')).toBe('[{"id":"stale"}]')
  })

  it('for an absent key is the empty value, so local data is restored but an empty state is not written', () => {
    expect(serverBaseline({ ok: true, value: null }, '[{"id":"a"}]', '[]')).toBe('[]')
  })

  it('normalises server JSON the same way the hooks stringify state, so a formatting difference is not a change', () => {
    expect(serverBaseline({ ok: true, value: '[ {"id": "a", "qty": 10.0} ]' }, '[]', '[]')).toBe('[{"id":"a","qty":10}]')
  })

  it('for malformed server JSON treats local state as already saved', () => {
    expect(serverBaseline({ ok: true, value: '{broken' }, '{"a":1}', '{}')).toBe('{"a":1}')
  })
})

describe('mergeJson', () => {
  const a = { id: 'a', v: 1 }
  const b = { id: 'b', v: 1 }
  const c = { id: 'c', v: 1 }
  const merge = (base: unknown, local: unknown, remote: unknown) =>
    JSON.parse(mergeJson(JSON.stringify(base), JSON.stringify(local), JSON.stringify(remote)))

  it('keeps a lot the phone added while this tab edited another', () => {
    expect(merge([a], [a, b], [a, c])).toEqual([a, b, c])
  })

  it('applies a delete from either side', () => {
    expect(merge([a, b], [a], [a, b])).toEqual([a])
    expect(merge([a, b], [a, b], [a])).toEqual([a])
  })

  it('takes the side that changed a record', () => {
    expect(merge([a], [a], [{ id: 'a', v: 2 }])).toEqual([{ id: 'a', v: 2 }])
  })

  it('lets the edit just made here win when both sides changed one record', () => {
    expect(merge([a], [{ id: 'a', v: 3 }], [{ id: 'a', v: 2 }])).toEqual([{ id: 'a', v: 3 }])
  })

  it('merges objects by key, for manual prices and tax overrides', () => {
    expect(merge({ X: 1 }, { X: 1, Y: 2 }, { X: 1, Z: 3 })).toEqual({ X: 1, Y: 2, Z: 3 })
    expect(merge({ X: 1, Y: 2 }, { Y: 2 }, { X: 1, Y: 2 })).toEqual({ Y: 2 })
  })

  it('returns local untouched when any side is not mergeable JSON', () => {
    expect(mergeJson('{broken', '[1]', '[]')).toBe('[1]')
  })
})

describe('saveMerged', () => {
  const lot = (id: string) => ({ id })
  const json = (...ids: string[]) => JSON.stringify(ids.map(lot))

  /** A fake persist server; `failing` makes every request fail like a dead network. */
  function server(initial: string | null) {
    const state = { value: initial, failing: false, posts: [] as string[] }
    vi.stubGlobal('fetch', async (_url: string, init?: RequestInit) => {
      if (state.failing) throw new TypeError('network')
      if (init?.method === 'POST') {
        state.value = JSON.parse(init.body as string).value
        state.posts.push(state.value as string)
        return jsonResponse({ ok: true })
      }
      return jsonResponse({ value: state.value })
    })
    return state
  }

  it('merges with what the server holds now, then shows the result here', async () => {
    const s = server(json('a', 'phone'))
    const sync = newKeySync(json('a'))
    const shown: string[] = []

    await saveMerged('k', json('a', 'web'), sync, (j) => shown.push(j))

    expect(JSON.parse(s.value!).map((l: { id: string }) => l.id).sort()).toEqual(['a', 'phone', 'web'])
    expect(shown).toEqual([s.value])
    expect(sync.saved).toBe(s.value)
  })

  it('does not post when state already matches the server', async () => {
    const s = server(json('a'))
    await saveMerged('k', json('a'), newKeySync(json('a')), () => {})
    expect(s.posts).toEqual([])
  })

  it('still updates localStorage on every save', async () => {
    server(json('a'))
    await saveMerged('k', json('a'), newKeySync(json('a')), () => {})
    expect(localStorage.getItem('k')).toBe(json('a'))
  })

  it('remembers the base after a failed write, so a reload can still merge the edit', async () => {
    const s = server(json('a'))
    s.failing = true
    const sync = newKeySync(json('a'))

    await saveMerged('k', json('a', 'offline'), sync, () => {}, 0)

    expect(pendingBase('k')).toBe(json('a'))
    expect(localStorage.getItem('k')).toBe(json('a', 'offline'))
    expect(s.posts).toEqual([])
    sync.disposed = true
  })

  it('clears the remembered base once the write lands', async () => {
    const s = server(json('a'))
    s.failing = true
    const sync = newKeySync(json('a'))
    await saveMerged('k', json('a', 'offline'), sync, () => {}, 0)

    s.failing = false
    await saveMerged('k', json('a', 'offline'), sync, () => {}, 0)

    expect(s.value).toBe(json('a', 'offline'))
    expect(pendingBase('k')).toBeNull()
    sync.disposed = true
  })

  it('restores local data when the server lost the key', async () => {
    const s = server(null)
    await saveMerged('k', json('a'), newKeySync('[]'), () => {})
    expect(s.value).toBe(json('a'))
  })

  it('does not drop an edit made while a save was in flight, nor the remote record it merged', async () => {
    const s = server(json('a', 'phone'))
    const sync = newKeySync(json('a'))
    const shown: string[] = []
    const apply = (j: string) => shown.push(j)

    const first = saveMerged('k', json('a', 'one'), sync, apply)
    const second = saveMerged('k', json('a', 'one', 'two'), sync, apply)
    await Promise.all([first, second])

    const ids = JSON.parse(s.value!).map((l: { id: string }) => l.id).sort()
    expect(ids).toEqual(['a', 'one', 'phone', 'two'])
    expect(shown[shown.length - 1]).toBe(s.value)
  })

  it('merges again when another write lands between its read and its write', async () => {
    let value = json('a')
    let refusals = 1
    const posts: string[] = []
    vi.stubGlobal('fetch', async (_url: string, init?: RequestInit) => {
      if (init?.method !== 'POST') return jsonResponse({ value, version: value })
      if (refusals-- > 0) {
        value = json('a', 'phone') // the phone got in first
        return jsonResponse({ error: 'changed' }, 409)
      }
      const body = JSON.parse(init.body as string)
      expect(body.baseVersion).toBe(value)
      value = body.value
      posts.push(body.value)
      return jsonResponse({ ok: true })
    })

    await saveMerged('k', json('a', 'web'), newKeySync(json('a')), () => {})

    expect(JSON.parse(value).map((l: { id: string }) => l.id).sort()).toEqual(['a', 'phone', 'web'])
    expect(posts).toHaveLength(1)
  })

  it('gives up on a server that keeps refusing, leaving the edit pending', async () => {
    let posts = 0
    vi.stubGlobal('fetch', async (_url: string, init?: RequestInit) => {
      if (init?.method !== 'POST') return jsonResponse({ value: json('a'), version: 'v' })
      posts++
      return jsonResponse({ error: 'changed' }, 409)
    })

    await saveMerged('stuck', json('a', 'web'), newKeySync(json('a')), () => {}, 0)

    expect(posts).toBe(5)
    expect(pendingBase('stuck')).toBe(json('a'))
  })

  it('shows an unsaved key until it lands', async () => {
    const s = server(json('a'))
    s.failing = true
    const sync = newKeySync(json('a'))
    await saveMerged('shown', json('a', 'b'), sync, () => {}, 0)
    expect(getSyncStatus().unsaved).toContain('shown')

    s.failing = false
    await saveMerged('shown', json('a', 'b'), sync, () => {}, 0)
    expect(getSyncStatus().unsaved).not.toContain('shown')
  })

  it('reports a record both sides changed, by ticker', async () => {
    dismissClashes()
    const [mine, theirs] = [{ id: 'a', ticker: 'ACME', qty: 2 }, { id: 'a', ticker: 'ACME', qty: 3 }]
    server(JSON.stringify([theirs]))
    await saveMerged('positions', JSON.stringify([mine]), newKeySync(JSON.stringify([{ id: 'a', ticker: 'ACME', qty: 1 }])), () => {})
    expect(getSyncStatus().clashes).toEqual(['ACME'])
  })

  it('does not report a manual price, which resolves by recency', async () => {
    dismissClashes()
    server('{"X":{"price":3}}')
    await saveMerged('stock_tracker_manual_prices_p', '{"X":{"price":2}}', newKeySync('{"X":{"price":1}}'), () => {})
    expect(getSyncStatus().clashes).toEqual([])
  })
})

describe('findClashes', () => {
  it('ignores one-sided changes and identical changes', () => {
    const b = '[{"id":"a","v":1}]'
    expect(findClashes(b, '[{"id":"a","v":2}]', b)).toEqual([])
    expect(findClashes(b, b, '[{"id":"a","v":2}]')).toEqual([])
    expect(findClashes(b, '[{"id":"a","v":2}]', '[{"id":"a","v":2}]')).toEqual([])
  })
})
