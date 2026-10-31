import { describe, expect, it } from 'vitest'
import { parseRaw } from './PriceChart'

describe('parseRaw', () => {
  it('dates a bar by its exchange\'s own day, so FX and price series line up', () => {
    const stampedEveningBefore = Date.UTC(2026, 9, 29, 23, 0, 0) / 1000
    const json = {
      chart: { result: [{ meta: { gmtoffset: 3600 }, timestamp: [stampedEveningBefore], indicators: { quote: [{ close: [25] }] } }] },
    }
    expect(parseRaw(json, '1y')[0].isoDate).toBe('2026-10-30')
  })
})
