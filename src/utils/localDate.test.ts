import { describe, expect, it } from 'vitest'
import { localToday, monthsBack } from './localDate'

describe('localToday', () => {
  it('uses the local calendar day, whatever UTC says', () => {
    expect(localToday(new Date(2026, 9, 31, 0, 30))).toBe('2026-10-31')
  })
})

// Same expectations as ChartMathTest's rangeStartDate cases, which run on java.time.
describe('monthsBack', () => {
  it.each([
    ['2026-10-31', 1, '2026-09-30'],
    ['2026-03-31', 1, '2026-02-28'],
    ['2026-01-15', 1, '2025-12-15'],
    ['2024-02-29', 12, '2023-02-28'],
    ['2026-10-31', 60, '2021-10-31'],
  ])('%s minus %i months is %s', (iso, months, expected) => {
    expect(monthsBack(iso, months)).toBe(expected)
  })
})
