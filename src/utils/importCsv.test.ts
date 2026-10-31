import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { parseBrokerNumber, parseDegiro, parseT212 } from './csvParser'
import { parseFile, parseWithMapping, ParseResult } from './importParser'
import { applyFifo, RawLot } from './fifoMatcher'

beforeEach(() => {
  // Ticker and ISIN lookups find nothing, so parsers keep what the statement said.
  vi.stubGlobal('fetch', async () => new Response(JSON.stringify({ quotes: [] })))
})
afterEach(() => vi.unstubAllGlobals())

// The same table runs in CsvParsersTest.kt.
describe('parseBrokerNumber', () => {
  it.each([
    ['150,25', 150.25],
    ['1,000', 1000],
    ['1,234,567', 1234567],
    ['1,234.56', 1234.56],
    ['1.234,56', 1234.56],
    ['1 234,5', 1234.5],
    ['87.745', 87.745],
    ['-3', -3],
    ['0,5', 0.5],
  ])('reads %s as %d', (raw, expected) => expect(parseBrokerNumber(raw)).toBe(expected))

  it.each(['1.2.3', '', 'abc', '1,2,3'])('rejects %j', (raw) => expect(parseBrokerNumber(raw)).toBeNaN())
})

const raw = (over: Partial<RawLot>): RawLot => ({
  ticker: 'A', name: 'A', qty: 1, price: 10, date: '2024-01-01', currency: 'USD', broker: 'B', type: 'stock', isSell: false, ...over,
})

describe('applyFifo', () => {
  it('does not let a sell close a buy dated after it, and reports what it could not match', () => {
    const unmatched: number[] = []
    const out = applyFifo(
      [raw({ date: '2024-06-01', qty: 5 }), raw({ date: '2024-01-01', qty: 5, isSell: true, price: 20 })],
      (_sell, qty) => unmatched.push(qty),
    )
    expect(out).toHaveLength(1)
    expect(out[0].sellDate).toBeUndefined()
    expect(unmatched).toEqual([5])
  })

  it('still lets a later sell close that buy', () => {
    const out = applyFifo([
      raw({ date: '2024-06-01', qty: 5 }),
      raw({ date: '2024-01-01', qty: 5, isSell: true, price: 20 }),
      raw({ date: '2024-07-01', qty: 5, isSell: true, price: 30 }),
    ])
    expect(out).toHaveLength(1)
    expect(out[0]).toMatchObject({ sellDate: '2024-07-01', sellPrice: 30 })
  })
})

const T212_HEADER = ['Action', 'Time', 'ISIN', 'Ticker', 'Name', 'No. of shares', 'Price / share', 'Currency (Price / share)']

describe('parseT212', () => {
  it('closes lots from sell rows and reads a space-separated time', async () => {
    const res = await parseT212([
      T212_HEADER,
      ['Market buy', '2024-01-02 10:30:00', 'US1', 'ACME', 'Acme', '10', '5', 'USD'],
      ['Market sell', '2024-03-04T09:00:00', 'US1', 'ACME', 'Acme', '4', '7', 'USD'],
    ]) as ParseResult
    const sorted = [...res.valid].sort((a, b) => a.quantity - b.quantity)
    expect(sorted[0]).toMatchObject({ quantity: 4, buyDate: '2024-01-02', sellDate: '2024-03-04', sellPrice: 7 })
    expect(sorted[1]).toMatchObject({ quantity: 6, buyDate: '2024-01-02' })
    expect(sorted[1].sellDate).toBeUndefined()
  })

  it('counts a sell it cannot match as skipped', async () => {
    const res = await parseT212([
      T212_HEADER,
      ['Market buy', '2024-01-02', 'US1', 'ACME', 'Acme', '1', '5', 'USD'],
      ['Market sell', '2024-03-04', 'US1', 'ACME', 'Acme', '2', '7', 'USD'],
    ]) as ParseResult
    expect(res.skipped).toBe(1)
  })
})

const DEGIRO_HEADER = ['Date', 'Product', 'ISIN', 'Description', 'Order ID']

describe('parseDegiro', () => {
  it('reads a thousands quantity whole and applies sells', async () => {
    const res = await parseDegiro([
      DEGIRO_HEADER,
      ['02-01-2024', 'Acme', 'US1', 'Buy 1,000 Acme @ 5.5 USD', 'x'],
      ['03-02-2024', 'Acme', 'US1', 'Sell 400 Acme @ 6 USD', 'y'],
    ]) as ParseResult
    const sorted = [...res.valid].sort((a, b) => a.quantity - b.quantity)
    expect(sorted[0]).toMatchObject({ quantity: 400, sellDate: '2024-02-03', sellPrice: 6 })
    expect(sorted[1]).toMatchObject({ quantity: 600, buyDate: '2024-01-02' })
  })

  it('skips a malformed price rather than failing the import', async () => {
    const res = await parseDegiro([
      DEGIRO_HEADER,
      ['02-01-2024', 'Acme', 'US1', 'Buy 1 Acme @ 1.2.3 USD', 'x'],
      ['02-01-2024', 'Good', 'US2', 'Buy 1 Good @ 2 USD', 'y'],
    ]) as ParseResult
    expect(res.valid).toHaveLength(1)
    expect(res.skipped).toBe(1)
  })
})

describe('parseFile on a CSV', () => {
  it('keeps a Degiro date with a low day as written', async () => {
    const csv = 'Date,Product,ISIN,Description,Order ID\n05-03-2024,Acme,US1,Buy 2 Acme @ 5 USD,x\n'
    const res = await parseFile(new File([csv], 'degiro.csv')) as ParseResult
    expect(res.valid[0].buyDate).toBe('2024-03-05')
  })

  it('reads a semicolon file with a BOM, through the wizard', async () => {
    const res = await parseFile(new File(['﻿ticker;date;qty;price\nACME;2024-03-05;2;150,25\n'], 'x.csv'))
    expect(res).toMatchObject({ type: 'needs-mapping' })
    const rows = (res as { rows: unknown[][] }).rows
    expect(rows[1]).toEqual(['ACME', '2024-03-05', '2', '150,25'])
  })
})

describe('parseWithMapping', () => {
  const mapping = { ticker: 0, date: 1, quantity: 2, buyPrice: 3, name: null, isin: null, currency: null, broker: null, sellDate: null, sellPrice: null }
  const defaults = { currency: 'CZK', broker: '', skipRows: 0 }

  it('reads a comma decimal', async () => {
    const res = await parseWithMapping([['ACME', '2024-03-05', '2', '150,25']], mapping, defaults) as ParseResult
    expect(res.valid[0].buyPrice).toBe(150.25)
  })

  it('reads 1,234 as a decimal when the rest of the file writes comma decimals', async () => {
    const res = await parseWithMapping([
      ['ACME', '2024-03-05', '2', '1,234'],
      ['OTHER', '2024-03-05', '2', '150,25'],
    ], mapping, defaults) as ParseResult
    expect(res.valid[0].buyPrice).toBe(1.234)
  })

  it('reads 1,234 as a thousand when nothing in the file says otherwise', async () => {
    const res = await parseWithMapping([['ACME', '2024-03-05', '2', '1,234']], mapping, defaults) as ParseResult
    expect(res.valid[0].buyPrice).toBe(1234)
  })

  it('treats a negative quantity as a sale of earlier shares', async () => {
    const res = await parseWithMapping([
      ['ACME', '2024-01-05', '10', '5'],
      ['ACME', '2024-03-05', '-4', '7'],
    ], mapping, defaults) as ParseResult
    const sorted = [...res.valid].sort((a, b) => a.quantity - b.quantity)
    expect(sorted[0]).toMatchObject({ quantity: 4, sellDate: '2024-03-05', sellPrice: 7 })
    expect(sorted[1]).toMatchObject({ quantity: 6 })
  })
})
