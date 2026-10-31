import { describe, expect, it } from 'vitest'
import { parsePositionsFromJson } from './importParser'

const lot = { id: '1', ticker: 'ACME', name: 'Acme', type: 'stock', quantity: 1, buyPrice: 10, buyDate: '2024-01-02', currency: 'USD' }

describe('parsePositionsFromJson', () => {
  it('accepts a complete lot unchanged', () => {
    const res = parsePositionsFromJson([lot])
    expect(res).toMatchObject({ skipped: 0 })
    expect(res?.valid[0]).toEqual(lot)
  })

  it('fills fields an older file leaves out, which would otherwise stop the key syncing to Android', () => {
    const { name: _n, type: _t, currency: _c, ...bare } = lot
    expect(parsePositionsFromJson([bare])?.valid[0]).toMatchObject({ name: 'ACME', type: 'stock', currency: 'USD' })
    expect(parsePositionsFromJson([{ ...lot, type: 'bond' }])?.valid[0].type).toBe('stock')
  })

  it.each([
    ['a loose buy date', { ...lot, buyDate: '2024-1-2' }],
    ['a loose sell date', { ...lot, sellPrice: 12, sellDate: '2.1.2024' }],
    ['no quantity', { ...lot, quantity: 0 }],
  ])('skips a lot with %s', (_name, bad) => {
    expect(parsePositionsFromJson([lot, bad])).toMatchObject({ skipped: 1 })
  })
})
