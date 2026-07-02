import { describe, it, expect } from 'vitest'
import { xirr } from './xirr'
import { applyFifo, RawLot } from './fifoMatcher'
import { calcNetDividends } from './dividends'

describe('xirr', () => {
  it('returns ~10% for 1000 → 1100 over one year', () => {
    const r = xirr([
      { date: new Date('2020-01-01'), amount: -1000 },
      { date: new Date('2021-01-01'), amount: 1100 },
    ])
    expect(r).not.toBeNull()
    expect(r!).toBeCloseTo(0.1, 2)
  })

  it('handles losses', () => {
    const r = xirr([
      { date: new Date('2020-01-01'), amount: -1000 },
      { date: new Date('2021-01-01'), amount: 900 },
    ])
    expect(r!).toBeCloseTo(-0.1, 2)
  })

  it('returns null with fewer than 2 cash flows', () => {
    expect(xirr([{ date: new Date(), amount: -1000 }])).toBeNull()
  })
})

const lot = (over: Partial<RawLot>): RawLot => ({
  ticker: 'AAA', name: 'AAA', qty: 10, price: 100, date: '2024-01-02',
  currency: 'CZK', broker: '', type: 'stock', isSell: false, ...over,
})

describe('applyFifo', () => {
  it('passes buys through when there are no sells', () => {
    const res = applyFifo([lot({}), lot({ date: '2024-02-02' })])
    expect(res).toHaveLength(2)
    expect(res.every((p) => !p.sellDate)).toBe(true)
  })

  it('splits a partial sell into closed + open remainder', () => {
    const res = applyFifo([lot({}), lot({ isSell: true, qty: 4, price: 150, date: '2024-06-01' })])
    const closed = res.find((p) => p.sellDate)
    const open = res.find((p) => !p.sellDate)
    expect(closed).toMatchObject({ quantity: 4, buyPrice: 100, sellPrice: 150, sellDate: '2024-06-01' })
    expect(open!.quantity).toBeCloseTo(6)
  })

  it('consumes oldest buys first across lots', () => {
    const res = applyFifo([
      lot({ qty: 5, date: '2024-01-02', price: 100 }),
      lot({ qty: 5, date: '2024-02-02', price: 200 }),
      lot({ isSell: true, qty: 7, price: 300, date: '2024-03-02' }),
    ])
    const closed = res.filter((p) => p.sellDate).sort((a, b) => a.buyDate.localeCompare(b.buyDate))
    expect(closed).toHaveLength(2)
    expect(closed[0]).toMatchObject({ quantity: 5, buyPrice: 100 })
    expect(closed[1]).toMatchObject({ quantity: 2, buyPrice: 200 })
    const open = res.filter((p) => !p.sellDate)
    expect(open).toHaveLength(1)
    expect(open[0]).toMatchObject({ buyPrice: 200 })
    expect(open[0].quantity).toBeCloseTo(3)
  })
})

describe('calcNetDividends', () => {
  const divs = [{ date: '2024-05-01', amount: 10 }]

  it('applies the default 15% CZ withholding', () => {
    expect(calcNetDividends([{ buyDate: '2024-01-01', quantity: 10 }], divs, 'KOMB.PR')).toBeCloseTo(85)
  })

  it('ignores lots bought after the ex-date', () => {
    expect(calcNetDividends([{ buyDate: '2024-06-01', quantity: 10 }], divs, 'KOMB.PR')).toBe(0)
  })

  it('ignores lots sold before the ex-date', () => {
    expect(
      calcNetDividends([{ buyDate: '2024-01-01', quantity: 10, sellDate: '2024-04-01' }], divs, 'KOMB.PR')
    ).toBe(0)
  })

  it('uses country-specific rates (Italy 26% for UCG.MI)', () => {
    expect(calcNetDividends([{ buyDate: '2024-01-01', quantity: 10 }], divs, 'UCG.MI')).toBeCloseTo(74)
  })

  it('honours per-event tax overrides', () => {
    expect(
      calcNetDividends([{ buyDate: '2024-01-01', quantity: 10 }], divs, 'KOMB.PR', { 'KOMB.PR::2024-05-01': 0 })
    ).toBeCloseTo(100)
  })
})
