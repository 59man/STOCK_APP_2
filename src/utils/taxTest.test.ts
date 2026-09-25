import { describe, expect, it } from 'vitest'
import cases from '../../test-fixtures/tax/cases.json'
import { lotTaxStatus, realizedByYear, timeTestDate } from './taxTest'
import type { Position } from '../types'

describe('Czech time test (shared cases)', () => {
  it.each(cases.freeFrom)('free from %j', ({ buyDate, expected }) => {
    expect(timeTestDate(buyDate)).toBe(expected)
  })

  it.each(cases.status)('status %j', ({ lot, today, kind, daysLeft }) => {
    const s = lotTaxStatus(lot, today)
    expect(s.kind).toBe(kind)
    if (daysLeft !== undefined) expect(s.daysLeft).toBe(daysLeft)
  })

  it('sums realized gains per year in CZK at trade-date rates', () => {
    const fx = cases.years.fx as unknown as Record<string, [string, number][]>
    const czkAt = (amount: number, cur: string, date: string) => {
      if (cur === 'CZK') return amount
      const rate = [...fx[cur]].reverse().find(([d]) => d <= date)![1]
      return amount * rate
    }
    const got = realizedByYear(cases.years.positions as Position[], czkAt)
    expect(got).toEqual(cases.years.expected)
  })
})
