import { describe, it, expect, vi, afterEach } from 'vitest'
import { fetchDividendEvents, calcNetDividends } from './dividends'

let requested: string[] = []

/** Responds with `bodies[n]` to the n-th request, repeating the last one thereafter. */
function mockYahoo(bodies: unknown | unknown[], status = 200) {
  const list = Array.isArray(bodies) ? bodies : [bodies]
  requested = []
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => {
      const body = list[Math.min(requested.length, list.length - 1)]
      requested.push(url)
      return { ok: status >= 200 && status < 300, status, json: async () => body }
    }) as unknown as typeof fetch,
  )
}

const chart = (
  currency: string,
  dividends: Record<string, { date: number; amount: number }> | null,
  bars = 500,
) => ({
  chart: {
    result: [
      {
        meta: { currency },
        timestamp: Array.from({ length: bars }, (_, i) => i),
        events: dividends ? { dividends } : undefined,
      },
    ],
  },
})

afterEach(() => vi.unstubAllGlobals())

describe('fetchDividendEvents', () => {
  it('parses events into ISO ex-dates stamped with the feed currency', async () => {
    mockYahoo(chart('USD', { a: { date: 1_716_793_200, amount: 1.24 } }))
    const events = await fetchDividendEvents('JNJ')
    expect(events).toEqual([{ date: '2024-05-27', amount: 1.24, currency: 'USD' }])
  })

  it('normalises GBp pence amounts to GBP', async () => {
    mockYahoo(chart('GBp', { a: { date: 1_716_793_200, amount: 250 } }))
    const [event] = await fetchDividendEvents('ULVR.L')
    expect(event.currency).toBe('GBP')
    expect(event.amount).toBeCloseTo(2.5)
  })

  it('throws on a non-OK response instead of reporting "no dividends"', async () => {
    // useDividends caches whatever resolves, so resolving [] on an HTTP error would
    // pin the ticker at zero dividends for the whole session with no retry.
    mockYahoo({}, 429)
    await expect(fetchDividendEvents('JNJ')).rejects.toThrow('429')
  })

  it('resolves empty for a successful response carrying no dividend events', async () => {
    mockYahoo(chart('EUR', null))
    await expect(fetchDividendEvents('EXUS.DE')).resolves.toEqual([])
  })

  it('merges the static COLT.PR history Yahoo lost, letting a live event win its date', async () => {
    mockYahoo(chart('CZK', { a: { date: 1_782_864_000, amount: 30 } })) // 2026-07-01
    const events = await fetchDividendEvents('CZG.PR') // aliased to COLT.PR
    expect(events.map((e) => e.date)).toEqual([
      '2021-06-25', '2022-06-01', '2023-06-16', '2024-07-03', '2025-07-03', '2026-07-01',
    ])
  })

  it('refetches at daily resolution when every bar carries a dividend', async () => {
    // Yahoo emits at most one dividend per bar, so one-event-per-bar means the weekly
    // interval is saturating and silently dropping payouts (a weekly-distribution ETF).
    const saturated = chart('USD', { a: { date: 1_716_793_200, amount: 0.2 } }, 1)
    const daily = chart('USD', {
      a: { date: 1_716_793_200, amount: 0.2 },
      b: { date: 1_717_398_000, amount: 0.2 },
    })
    mockYahoo([saturated, daily])

    const events = await fetchDividendEvents('QDTE')

    expect(events).toHaveLength(2)
    expect(requested[0]).toContain('interval=1wk')
    expect(requested[1]).toContain('interval=1d')
  })

  it('does not refetch when the weekly response is not saturated', async () => {
    mockYahoo(chart('USD', { a: { date: 1_716_793_200, amount: 1.24 } }))
    await fetchDividendEvents('JNJ')
    expect(requested).toHaveLength(1)
  })
})

describe('calcNetDividends', () => {
  const events = [{ date: '2024-05-27', amount: 10 }]

  it('pays only lots bought on or before the ex-date', () => {
    expect(calcNetDividends([{ buyDate: '2024-05-28', quantity: 5 }], events, 'JNJ')).toBe(0)
    expect(calcNetDividends([{ buyDate: '2024-05-27', quantity: 5 }], events, 'JNJ')).toBeCloseTo(42.5)
  })

  it('does not pay a lot sold on the ex-date, but does pay one sold after', () => {
    const lot = { buyDate: '2020-01-01', quantity: 5 }
    expect(calcNetDividends([{ ...lot, sellDate: '2024-05-27' }], events, 'JNJ')).toBe(0)
    expect(calcNetDividends([{ ...lot, sellDate: '2024-05-28' }], events, 'JNJ')).toBeCloseTo(42.5)
  })

  it('applies a per-event override in place of the country rate', () => {
    const lots = [{ buyDate: '2020-01-01', quantity: 5 }]
    expect(calcNetDividends(lots, events, 'JNJ', { 'JNJ::2024-05-27': 0 })).toBeCloseTo(50)
  })
})
