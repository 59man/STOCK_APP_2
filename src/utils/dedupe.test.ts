import { describe, expect, it } from 'vitest'
import { planImport } from './dedupe'
import { Position } from '../types'

const lot = (id: string, quantity: number, sold?: { sellDate: string; sellPrice: number }, overrides: Partial<Position> = {}): Position => ({
  id, ticker: 'ACME', name: 'Acme', type: 'stock', quantity, buyPrice: 10, buyDate: '2024-01-02',
  currency: 'USD', broker: 'XTB', ...sold, ...overrides,
})
const sale = { sellDate: '2024-06-01', sellPrice: 15 }
const shape = (ps: Position[]) => ps.map((p) => [p.id, p.quantity, p.sellDate ?? null]).sort()

// The same table runs in DedupeTest.kt.
describe('planImport', () => {
  it('adds everything to an empty portfolio', () => {
    expect(planImport([], [lot('n', 5)])).toMatchObject({ added: 1, duplicates: 0 })
  })

  it('drops an identical re-import', () => {
    const plan = planImport([lot('e', 10)], [lot('n', 10)])
    expect(shape(plan.positions)).toEqual([['e', 10, null]])
    expect(plan).toMatchObject({ added: 0, duplicates: 1 })
  })

  it('counts a split lot once when the same shares come back split', () => {
    const existing = [lot('c', 4, sale), lot('o', 6)]
    const plan = planImport(existing, [lot('n1', 4, sale), lot('n2', 6)])
    expect(shape(plan.positions)).toEqual(shape(existing))
    expect(plan).toMatchObject({ added: 0, duplicates: 2 })
  })

  it('closes open shares when a newer statement contains the sale', () => {
    const plan = planImport([lot('e', 10)], [lot('n1', 4, sale), lot('n2', 6)])
    expect(shape(plan.positions)).toEqual([['e', 6, null], ['n1', 4, sale.sellDate]])
    expect(plan).toMatchObject({ added: 1, duplicates: 1 })
  })

  it('removes an open lot its sale closes entirely', () => {
    const plan = planImport([lot('e', 10)], [lot('n', 10, sale)])
    expect(shape(plan.positions)).toEqual([['n', 10, sale.sellDate]])
  })

  it('does not reopen shares when an older statement arrives after a newer one', () => {
    const existing = [lot('c', 4, sale), lot('o', 6)]
    const plan = planImport(existing, [lot('n', 10)])
    expect(shape(plan.positions)).toEqual(shape(existing))
    expect(plan).toMatchObject({ added: 0, duplicates: 1 })
  })

  it('adds only the shares beyond what is already there', () => {
    const plan = planImport([lot('e', 10)], [lot('n', 15)])
    expect(shape(plan.positions)).toEqual([['e', 10, null], ['n', 5, null]])
  })

  it.each([
    ['ticker', { ticker: 'OTHER' }],
    ['buy date', { buyDate: '2024-01-03' }],
    ['price', { buyPrice: 11 }],
    ['broker', { broker: 'Revolut' }],
  ])('treats a different %s as a different buy', (_field, overrides) => {
    expect(planImport([lot('e', 10)], [lot('n', 10, undefined, overrides)])).toMatchObject({ added: 1, duplicates: 0 })
  })
})
