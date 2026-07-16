import { describe, it, expect } from 'vitest'
import { parseRevolutTradingLines } from './pdfParser'

const LINES = [
  'EUR Portfolio breakdown',
  'Symbol Company ISIN Quantity Price Value % of Portfolio',
  'AMEW Amundi MSCI World UCITS ETF - EUR (Acc) LU1681043599 0.11165593 €685.32 €76.52 100%',
  'EUR Transactions',
  'Date Symbol Type Quantity Price Side Value Fees Commission',
  '24 Apr 2026 14:22:14 GMT Cash top-up €1.22 €0 €0',
  '24 Apr 2026 14:22:15 GMT AMEW Trade - Market 0.00192301 €634.42 Buy €1.22 €0 €0',
  '24 Apr 2026 14:36:31 GMT AMEW Trade - Market 0.01103544 €634.32 Buy €7 €0 €0',
  'USD Transactions',
  '05 May 2026 08:02:52 GMT TSLA Trade - Market 2 US$1,234.50 Sell US$2,469 US$0 US$0',
]

describe('parseRevolutTradingLines', () => {
  it('extracts trades, currencies and the symbol→ISIN map', () => {
    const { txs, bySymbol, skipped } = parseRevolutTradingLines(LINES)

    expect(txs).toHaveLength(3)
    expect(txs[0]).toEqual({
      date: '2026-04-24', symbol: 'AMEW', qty: 0.00192301, price: 634.42,
      currency: 'EUR', isSell: false,
    })
    expect(txs[2]).toEqual({
      date: '2026-05-05', symbol: 'TSLA', qty: 2, price: 1234.5,
      currency: 'USD', isSell: true,
    })
    expect(bySymbol.AMEW).toEqual({
      isin: 'LU1681043599', name: 'Amundi MSCI World UCITS ETF - EUR (Acc)',
    })
    expect(skipped).toBe(0)
  })
})
