import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { getApiKey, hasServedKey } from './apiKey'

const served = globalThis as { __STOCK_TRACKER_API_KEY__?: string }

beforeEach(() => {
  const data = new Map<string, string>()
  vi.stubGlobal('localStorage', {
    getItem: (k: string) => data.get(k) ?? null,
    setItem: (k: string, v: string) => void data.set(k, v),
  })
})

afterEach(() => {
  vi.unstubAllGlobals()
  delete served.__STOCK_TRACKER_API_KEY__
})

describe('getApiKey', () => {
  it('prefers the key the server put in the page, so an old entered key cannot go stale', () => {
    localStorage.setItem('stock_tracker_api_key', 'entered-long-ago')
    served.__STOCK_TRACKER_API_KEY__ = 'from-server'
    expect(getApiKey()).toBe('from-server')
    expect(hasServedKey()).toBe(true)
  })

  it('falls back to an entered key when the server gave none', () => {
    localStorage.setItem('stock_tracker_api_key', 'entered')
    expect(getApiKey()).toBe('entered')
    expect(hasServedKey()).toBe(false)
  })
})
