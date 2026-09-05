import { describe, it, expect, vi, afterEach } from 'vitest'
import { fetchDividendEvents, calcNetDividends } from './dividends'

function mockYahoo(body: unknown, status = 200) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => ({
      ok: status >= 200 && status < 300,
      status,
      json: async () => body,
    })) as unknown as typeof fetch,
  )
}

const chart = (currency: string, dividends: Record<string, { date: number; amount: number }> | null) => ({
  chart: { result: [{ meta: { currency }, events: dividends ? { dividends } : undefined }] },
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
