import { describe, expect, it, vi, beforeEach } from 'vitest'

const proxyFetch = vi.fn()
vi.mock('../utils/proxyFetch', () => ({ proxyFetch: (...a: unknown[]) => proxyFetch(...a) }))

import { resolveAnchor } from './useDailyAnchors'

const response = (status: number, body: unknown = {}) =>
  ({ status, ok: status >= 200 && status < 300, json: async () => body }) as Response

describe('resolveAnchor', () => {
  beforeEach(() => proxyFetch.mockReset())

  it('resolves a definitive miss (404) to a null anchor that may be cached', async () => {
    proxyFetch.mockResolvedValue(response(404))
    await expect(resolveAnchor('NOPE', 1_790_000_000)).resolves.toEqual({ price: null, lastTradedAt: null })
  })

  it('throws on a rate limit so the caller retries instead of caching a null for the day', async () => {
    proxyFetch.mockResolvedValue(response(429))
    await expect(resolveAnchor('AAPL', 1_790_000_000)).rejects.toThrow('429')
  })

  it('anchors at the current price when nothing traded since midnight', async () => {
    proxyFetch.mockResolvedValue(response(200, { chart: { result: [{ meta: { regularMarketTime: 100, regularMarketPrice: 42 } }] } }))
    await expect(resolveAnchor('AAPL', 200)).resolves.toEqual({ price: 42, lastTradedAt: 100 })
  })
})
