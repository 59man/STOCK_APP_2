import { Position } from '../types'
import { ParseResult } from './importParser'
import { batchTickers } from './yahooLookup'
import { applyFifo, RawLot } from './fifoMatcher'

// "OPEN BUY 3/3.4281 @ 37.185" or "CLOSE SELL 3.4281 @ 40.00" → qty
function parseFillQty(comment: string): number | null {
  const m = String(comment).match(/(?:OPEN|CLOSE)\s+(?:BUY|SELL)\s+([\d.]+)(?:\/[\d.]+)?\s*@/)
  return m ? parseFloat(m[1]) : null
}

function isSellComment(comment: string): boolean {
  return /CLOSE\s+SELL/i.test(comment)
}

export async function parseXtbXlsx(buffer: ArrayBuffer, fileName = ''): Promise<ParseResult | null> {
  // XTB statement filenames start with the account currency: EUR_53675935_…, CZK_…
  const accountCurrency = /^([A-Z]{3})_/.exec(fileName)?.[1] ?? 'CZK'
  const XLSX = await import('xlsx')
  const wb = XLSX.read(buffer, { type: 'array', cellDates: true })
  const ws = wb.Sheets['Cash Operations']
  if (!ws) return null

  const rows = XLSX.utils.sheet_to_json<unknown[]>(ws, { header: 1 })
  const headerIdx = rows.findIndex((r) => (r as unknown[])[0] === 'Type')
  if (headerIdx === -1) return null

  const rawLots: RawLot[] = []
  let skipped = 0

  for (let i = headerIdx + 1; i < rows.length; i++) {
    const row = rows[i] as unknown[]
    const type = String(row[0] ?? '')
    if (type !== 'Stock purchase' && type !== 'Stock sale') continue

    const isSell = type === 'Stock sale'
    const xtbTicker = String(row[1] ?? '').trim()
    const name = String(row[2] ?? xtbTicker).trim()
    const time = row[3]
    const amount = Number(row[4])
    const comment = String(row[6] ?? '')

    if (!xtbTicker || !time || isNaN(amount)) { skipped++; continue }
    // Buy amount must be negative; sell must be positive
    if (!isSell && amount >= 0) { skipped++; continue }
    if (isSell && amount <= 0) { skipped++; continue }

    const qty = parseFillQty(comment)
    if (!qty || qty <= 0) { skipped++; continue }

    // Validate comment direction agrees with row type
    if (isSell && !isSellComment(comment) && comment.includes('OPEN')) { skipped++; continue }

    // XTB uses .CZ suffix for Prague exchange; app uses .PR
    const ticker = xtbTicker.replace(/\.CZ$/, '.PR')

    const buyDate = time instanceof Date
      ? time.toISOString().split('T')[0]
      : String(time).split(/[T ]/)[0]

    rawLots.push({
      ticker, name, qty,
      price: Math.abs(amount) / qty,
      date: buyDate,
      currency: accountCurrency,
      broker: 'XTB',
      type: 'stock',
      isSell,
    })
  }

  if (!rawLots.length) return null

  // FIFO: match sells against buys per ticker
  const positions = applyFifo(rawLots)
  if (!positions.length) return null

  // Enrich type via Yahoo (ETF / stock / fund / commodity)
  const typeMap = await batchTickers([...new Set(positions.map(p => p.ticker))])
  positions.forEach(p => { p.type = typeMap[p.ticker]?.type ?? 'stock' })

  return { valid: positions, skipped }
}
