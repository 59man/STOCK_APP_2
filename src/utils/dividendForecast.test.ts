import { describe, expect, it } from 'vitest'
import cases from '../../test-fixtures/forecast/cases.json'
import { dividendForecast } from './dividendForecast'
import type { Position } from '../types'
import type { DividendEvent } from './dividends'

describe('dividendForecast (shared case)', () => {
  it('repeats the last 12 months on the shares held now', () => {
    const got = dividendForecast(
      cases.positions as Position[],
      new Map(Object.entries(cases.dividends as Record<string, DividendEvent[]>)),
      cases.today,
    )
    expect(got.map((e) => e.date)).toEqual(cases.expected.map((e) => e.date))
    got.forEach((e, i) => {
      expect(e.ticker).toBe(cases.expected[i].ticker)
      expect(e.currency).toBe(cases.expected[i].currency)
      expect(e.net).toBeCloseTo(cases.expected[i].net, 9)
    })
  })
})
