import { describe, it, expect } from 'vitest'
import { yahooChartQuery, yahooDividendQuery, yahooFxHistoryQuery } from './yahooWindow'

const NOW = 1_788_700_000_000 // 2026-09-05T…

describe('yahooChartQuery', () => {
  it('keeps daily bars for bounded ranges', () => {
    expect(yahooChartQuery('1mo')).toBe('interval=1d&range=1mo')
    expect(yahooChartQuery('5y')).toBe('interval=1d&range=5y')
  })

  it('expands max into an explicit epoch..now window with weekly bars', () => {
    expect(yahooChartQuery('max', NOW)).toBe('interval=1wk&period1=0&period2=1788700000')
  })
})

describe('yahooDividendQuery', () => {
  it('asks for weekly bars over the full window — coarser intervals drop events', () => {
    expect(yahooDividendQuery(NOW)).toBe('interval=1wk&period1=0&period2=1788700000&events=div')
  })
})

describe('yahooFxHistoryQuery', () => {
  it('keeps daily bars so per-date conversion is not stale by up to a week', () => {
    expect(yahooFxHistoryQuery(NOW)).toBe('interval=1d&period1=0&period2=1788700000')
  })
})

describe('every builder', () => {
  it('never emits range=max, which Yahoo answers with 3mo bars regardless of interval', () => {
    const all = [yahooChartQuery('max'), yahooDividendQuery(), yahooFxHistoryQuery()]
    all.forEach((q) => expect(q).not.toContain('range=max'))
  })
})
