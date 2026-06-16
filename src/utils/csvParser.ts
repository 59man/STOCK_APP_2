import { Position } from '../types'
import { ParseResult } from './importParser'
import { batchIsins, batchTickers } from './yahooLookup'

// ── Helpers ───────────────────────────────────────────────────────────────────

function colIdx(header: string[], name: string): number {
  return header.findIndex(h => h.trim().toLowerCase() === name.toLowerCase())
}

function parseDecimal(s: unknown): number {
  return parseFloat(String(s ?? '').replace(/,/g, ''))
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

  const valid: Position[] = []
  let skipped = 0

  for (let i = 1; i < rows.length; i++) {
    const row = rows[i] as unknown[]
    const action = String(row[iAction] ?? '').toLowerCase()
    if (!action.includes('buy')) { skipped++; continue }

    const ticker   = String(row[iTicker]   ?? '').trim()
    const name     = String(row[iName]     ?? ticker).trim()
    const isin     = String(row[iISIN]     ?? '').trim()
    const timeStr  = String(row[iTime]     ?? '')
    const qty      = parseDecimal(row[iQty])
    const price    = parseDecimal(row[iPrice])
    const currency = String(row[iCurrency] ?? 'USD').trim()

    if (!ticker || !timeStr || qty <= 0 || price <= 0) { skipped++; continue }

    valid.push({
      id: '', ticker, name, type: 'stock',
      quantity: qty, buyPrice: price,
      buyDate: timeStr.split('T')[0],
      currency, broker: 'Trading 212',
      ...(isin ? { isin } : {}),
    } as Position)
  }

  if (!valid.length) return null

  // Enrich types via Yahoo
  const typeMap = await batchTickers(valid.map(p => p.ticker))
  valid.forEach(p => { p.type = typeMap[p.ticker]?.type ?? 'stock' })

  return { valid, skipped }
}

// ── Degiro ────────────────────────────────────────────────────────────────────
// Columns vary by region. Key columns: Date, Product, ISIN, Description, FX, Change, Balance, Order ID
// Description contains "Buy N PRODUCT @ PRICE CURRENCY" for buy rows.

export async function parseDegiro(rows: unknown[][]): Promise<ParseResult | null> {
  const header = (rows[0] as string[]).map(h => String(h).trim().toLowerCase())
  const iDate    = header.findIndex(h => h === 'date')
  const iProduct = header.findIndex(h => h === 'product')
  const iISIN    = header.findIndex(h => h === 'isin')
  const iDesc    = header.findIndex(h => h === 'description')

  if (iDate === -1 || iISIN === -1 || iDesc === -1) return null

  const valid: Position[] = []
  let skipped = 0

  for (let i = 1; i < rows.length; i++) {
    const row = rows[i] as unknown[]
    const isin = String(row[iISIN] ?? '').trim()
    if (!isin) { skipped++; continue }

    const desc = String(row[iDesc] ?? '')
    // "Buy 2 PRODUCT @ 87.745 USD" or "Buy 2 @ 87.745 USD"
    const buyM = desc.match(/^Buy\s+([\d.]+)[^@]*@\s*([\d.]+)\s*([A-Z]{3})/i)
    if (!buyM) { skipped++; continue }

    const qty      = parseFloat(buyM[1])
    const price    = parseFloat(buyM[2])
    const currency = buyM[3]

    // Date: "DD-MM-YYYY" or "DD/MM/YYYY"
    const rawDate = String(row[iDate] ?? '')
    const dm = rawDate.match(/(\d{1,2})[-/](\d{1,2})[-/](\d{4})/)
    if (!dm) { skipped++; continue }
    const buyDate = `${dm[3]}-${dm[2].padStart(2,'0')}-${dm[1].padStart(2,'0')}`

    const name = iProduct !== -1 ? String(row[iProduct] ?? isin).trim() : isin

    valid.push({
      id: '', ticker: isin, name, type: 'stock',
      quantity: qty, buyPrice: price, buyDate, currency,
      broker: 'Degiro', isin,
    } as Position)
  }

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
