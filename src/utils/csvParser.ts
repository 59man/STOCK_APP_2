import { Position } from '../types'
import { ParseResult } from './importParser'
import { applyFifo, RawLot } from './fifoMatcher'
import { batchIsins, batchTickers } from './yahooLookup'

// ── Helpers ───────────────────────────────────────────────────────────────────

function colIdx(header: string[], name: string): number {
  return header.findIndex(h => h.trim().toLowerCase() === name.toLowerCase())
}

/**
 * A number as brokers write it: `1,234.56`, `1.234,56`, `150,25`, `1 234`. With both separators the
 * last one is the decimal point. A lone comma is thousands only in the exact `1,000` shape, unless
 * `commaDecimal` says this file writes decimals with a comma: see usesCommaDecimals. Anything else,
 * like `1.2.3`, is NaN. Mirrored by parseBrokerNumber in CsvParsers.kt.
 */
export function parseBrokerNumber(raw: unknown, commaDecimal = false): number {
  const t = String(raw ?? '').replace(/[\s\u00a0]/g, '')
  if (!/^[+-]?[\d.,]*\d[\d.,]*$/.test(t)) return NaN
  const comma = t.lastIndexOf(',')
  const dot = t.lastIndexOf('.')
  if (comma >= 0 && dot >= 0) {
    return comma > dot
      ? Number(t.replace(/\./g, '').replace(',', '.'))
      : Number(t.replace(/,/g, ''))
  }
  if (comma >= 0) {
    return !commaDecimal && /^[+-]?\d{1,3}(,\d{3})+$/.test(t) ? Number(t.replace(/,/g, '')) : Number(t.replace(',', '.'))
  }
  if (commaDecimal && /^[+-]?\d{1,3}(\.\d{3})+$/.test(t)) return Number(t.replace(/\./g, ''))
  return Number(t)
}

/**
 * `1,234` alone could be either number. Another cell in the same file settles it: `150,25` or
 * `1.234,5` cannot be anything but comma decimals. Mirrored by usesCommaDecimals in CsvParsers.kt.
 */
export function usesCommaDecimals(cells: unknown[]): boolean {
  return cells.some((c) => /^[+-]?\d+,(\d{1,2}|\d{4,})$|^[+-]?\d{1,3}(\.\d{3})+,\d+$/.test(String(c ?? '').replace(/[\s\u00a0]/g, '')))
}

// ── Trading 212 ───────────────────────────────────────────────────────────────
// Columns: Action | Time | ISIN | Ticker | Name | No. of shares | Price / share |
//          Currency (Price/share) | Exchange rate | Result | Total | ...

export async function parseT212(rows: unknown[][]): Promise<ParseResult | null> {
  const header = (rows[0] as string[]).map(h => String(h))
  const iAction   = colIdx(header, 'Action')
  const iTime     = colIdx(header, 'Time')
  const iISIN     = colIdx(header, 'ISIN')
  const iTicker   = colIdx(header, 'Ticker')
  const iName     = colIdx(header, 'Name')
  const iQty      = header.findIndex(h => h.toLowerCase().includes('no. of shares'))
  const iPrice    = header.findIndex(h => h.toLowerCase().includes('price / share'))
  const iCurrency = header.findIndex(h => h.toLowerCase().startsWith('currency (price'))

  if (iAction === -1 || iTime === -1 || iQty === -1 || iPrice === -1) return null

  const rawLots: RawLot[] = []
  let skipped = 0

  for (let i = 1; i < rows.length; i++) {
    const row = rows[i] as unknown[]
    const action = String(row[iAction] ?? '').toLowerCase()
    const isSell = action.includes('sell')
    if (!isSell && !action.includes('buy')) { skipped++; continue }

    const ticker   = String(row[iTicker]   ?? '').trim()
    const name     = String(row[iName]     ?? ticker).trim()
    const isin     = String(row[iISIN]     ?? '').trim()
    const date     = String(row[iTime]     ?? '').split(/[T ]/)[0]
    const qty      = parseBrokerNumber(row[iQty])
    const price    = parseBrokerNumber(row[iPrice])
    const currency = String(row[iCurrency] ?? 'USD').trim()

    if (!ticker || !/^\d{4}-\d{2}-\d{2}$/.test(date) || !(qty > 0) || !(price > 0)) { skipped++; continue }

    rawLots.push({
      ticker, name, type: 'stock', qty, price, date, currency, isSell,
      broker: 'Trading 212', ...(isin ? { isin } : {}),
    })
  }

  const valid = applyFifo(rawLots, () => { skipped++ })
  if (!valid.length) return null

  // Enrich types via Yahoo
  const typeMap = await batchTickers(valid.map(p => p.ticker))
  valid.forEach(p => { p.type = typeMap[p.ticker]?.type ?? 'stock' })

  return { valid, skipped }
}

// ── Degiro ────────────────────────────────────────────────────────────────────
// Columns vary by region. Key columns: Date, Product, ISIN, Description, FX, Change, Balance, Order ID
// Description contains "Buy N PRODUCT @ PRICE CURRENCY", or "Sell ...", for trade rows.

export async function parseDegiro(rows: unknown[][]): Promise<ParseResult | null> {
  const header = (rows[0] as string[]).map(h => String(h).trim().toLowerCase())
  const iDate    = header.findIndex(h => h === 'date')
  const iProduct = header.findIndex(h => h === 'product')
  const iISIN    = header.findIndex(h => h === 'isin')
  const iDesc    = header.findIndex(h => h === 'description')

  if (iDate === -1 || iISIN === -1 || iDesc === -1) return null

  const rawLots: RawLot[] = []
  let skipped = 0

  for (let i = 1; i < rows.length; i++) {
    const row = rows[i] as unknown[]
    const isin = String(row[iISIN] ?? '').trim()
    if (!isin) { skipped++; continue }

    const desc = String(row[iDesc] ?? '')
    // "Buy 2 PRODUCT @ 87.745 USD" or "Sell 2 @ 87.745 USD"
    const trade = desc.match(/^(Buy|Sell)\s+([\d.,]+)[^@]*@\s*([\d.,]+)\s*([A-Z]{3})/i)
    if (!trade) { skipped++; continue }

    const qty      = parseBrokerNumber(trade[2])
    const price    = parseBrokerNumber(trade[3])
    const currency = trade[4]
    if (!(qty > 0) || !(price > 0)) { skipped++; continue }

    // Date: "DD-MM-YYYY" or "DD/MM/YYYY"
    const rawDate = String(row[iDate] ?? '')
    const dm = rawDate.match(/(\d{1,2})[-/](\d{1,2})[-/](\d{4})/)
    if (!dm) { skipped++; continue }
    const date = `${dm[3]}-${dm[2].padStart(2,'0')}-${dm[1].padStart(2,'0')}`

    const name = iProduct !== -1 ? String(row[iProduct] ?? isin).trim() : isin

    rawLots.push({
      ticker: isin, name, type: 'stock', qty, price, date, currency,
      broker: 'Degiro', isin, isSell: trade[1].toLowerCase() === 'sell',
    })
  }

  const valid = applyFifo(rawLots, () => { skipped++ })
  if (!valid.length) return null

  // Resolve ISIN → ticker + type
  const isinMap = await batchIsins([...new Set(valid.map(p => p.isin as string))])
  valid.forEach(p => {
    const info = isinMap[p.isin as string]
    if (info) { p.ticker = info.ticker; p.type = info.type }
  })

  return { valid, skipped }
}

// ── Format detector ───────────────────────────────────────────────────────────

export function detectCsvFormat(header: string[]): 't212' | 'degiro' | null {
  const h = header.join(',').toLowerCase()
  if (h.includes('no. of shares') && h.includes('price / share')) return 't212'
  if (h.includes('order id') && h.includes('isin') && h.includes('description')) return 'degiro'
  return null
}
