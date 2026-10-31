import { Position } from '../types'
import { parsePdf } from './pdfParser'
import { detectCsvFormat, parseBrokerNumber, parseT212, parseDegiro, usesCommaDecimals } from './csvParser'
import { applyFifo, RawLot } from './fifoMatcher'
import { batchTickers } from './yahooLookup'

export interface ParseResult {
  valid: Position[]
  skipped: number
  dividendTaxOverrides?: Record<string, number>
  manualPrices?: Record<string, { price: number; updatedAt: string }>
  currencyUncertain?: boolean // parser had to guess the lot currency (e.g. XTB file without EUR_/CZK_ prefix)
}

export interface NeedsMapping {
  type: 'needs-mapping'
  rows: unknown[][]
}

export type ParseFileResult = ParseResult | NeedsMapping

export interface ColumnMapping {
  ticker: number | null
  date: number | null
  quantity: number | null
  buyPrice: number | null
  name: number | null
  isin: number | null
  currency: number | null
  broker: number | null
  sellDate: number | null
  sellPrice: number | null
}

export interface MappingDefaults {
  currency: string
  broker: string
  skipRows: number
}

const POSITION_TYPES: string[] = ['stock', 'etf', 'fund', 'commodity', 'crypto']
const isIsoDate = (v: unknown) => typeof v === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(v) && !isNaN(Date.parse(v))

/** Minimal schema guard */
function isValidPosition(p: unknown): p is Omit<Position, 'id'> {
  if (!p || typeof p !== 'object') return false
  const x = p as Record<string, unknown>
  return (
    typeof x.ticker === 'string' && x.ticker.trim().length > 0 &&
    typeof x.quantity === 'number' && isFinite(x.quantity) && x.quantity > 0 &&
    typeof x.buyPrice === 'number' && isFinite(x.buyPrice) && x.buyPrice >= 0 &&
    isIsoDate(x.buyDate) &&
    (x.sellDate === undefined || x.sellDate === null || isIsoDate(x.sellDate))
  )
}

/**
 * Fills what older or hand-edited files leave out, with the same defaults as Android's importer.
 * Android decodes a stored key as a whole, so one lot missing a required field would stop that key
 * syncing altogether.
 */
function withRequiredFields(p: Position): Position {
  return {
    ...p,
    name: typeof p.name === 'string' && p.name ? p.name : p.ticker,
    type: POSITION_TYPES.includes(p.type) ? p.type : 'stock',
    currency: typeof p.currency === 'string' && p.currency ? p.currency : 'USD',
  }
}

export function parsePositionsFromJson(raw: unknown): ParseResult | null {
  let candidates: unknown[] = []
  let dividendTaxOverrides: Record<string, number> | undefined
  let manualPrices: Record<string, { price: number; updatedAt: string }> | undefined

  if (raw && typeof raw === 'object' && !Array.isArray(raw)) {
    const obj = raw as Record<string, unknown>
    if (obj.version === 1 && Array.isArray(obj.positions)) {
      candidates = obj.positions
      if (obj.dividendTaxOverrides && typeof obj.dividendTaxOverrides === 'object' && !Array.isArray(obj.dividendTaxOverrides)) {
        dividendTaxOverrides = obj.dividendTaxOverrides as Record<string, number>
      }
      if (obj.manualPrices && typeof obj.manualPrices === 'object' && !Array.isArray(obj.manualPrices)) {
        manualPrices = obj.manualPrices as Record<string, { price: number; updatedAt: string }>
      }
    }
  }

  if (candidates.length === 0) {
    if (Array.isArray(raw)) {
      candidates = raw
    } else if (raw && typeof raw === 'object') {
      const obj = raw as Record<string, unknown>
      if (typeof obj['stock_tracker_positions'] === 'string') {
        try {
          const parsed = JSON.parse(obj['stock_tracker_positions'])
          if (Array.isArray(parsed)) candidates = parsed
        } catch {}
      }
      if (candidates.length === 0) {
        for (const key of Object.keys(obj)) {
          if (key.startsWith('stock_tracker_positions_') && typeof obj[key] === 'string') {
            try {
              const parsed = JSON.parse(obj[key] as string)
              if (Array.isArray(parsed)) candidates.push(...parsed)
            } catch {}
          }
        }
      }
    }
  }

  if (candidates.length === 0) return null
  const valid = (candidates.filter(isValidPosition) as Position[]).map(withRequiredFields)
  return { valid, skipped: candidates.length - valid.length, dividendTaxOverrides, manualPrices }
}

// ── parseWithMapping ──────────────────────────────────────────────────────────

function getStr(row: unknown[], col: number | null): string {
  if (col === null || col < 0 || col >= row.length) return ''
  return String(row[col] ?? '').trim()
}

function getNum(row: unknown[], col: number | null, commaDecimal: boolean): number {
  return parseBrokerNumber(getStr(row, col), commaDecimal) || 0
}

function parseAnyDate(s: string): string | null {
  const iso = s.match(/(\d{4})-(\d{2})-(\d{2})/)
  if (iso) return `${iso[1]}-${iso[2]}-${iso[3]}`
  const dmy = s.match(/(\d{1,2})[./\-](\d{1,2})[./\-](\d{4})/)
  if (dmy) return `${dmy[3]}-${dmy[2].padStart(2,'0')}-${dmy[1].padStart(2,'0')}`
  const MONTHS: Record<string, string> = {
    jan:'01',feb:'02',mar:'03',apr:'04',may:'05',jun:'06',
    jul:'07',aug:'08',sep:'09',oct:'10',nov:'11',dec:'12',
  }
  const mdy = s.match(/(\w{3,})\s+(\d{1,2}),?\s+(\d{4})/i)
  if (mdy) {
    const mo = MONTHS[mdy[1].slice(0,3).toLowerCase()]
    if (mo) return `${mdy[3]}-${mo}-${mdy[2].padStart(2,'0')}`
  }
  return null
}

export async function parseWithMapping(
  rows: unknown[][],
  mapping: ColumnMapping,
  defaults: MappingDefaults,
): Promise<ParseResult | null> {
  const dataRows = rows.slice(defaults.skipRows)
  const numberCols = [mapping.quantity, mapping.buyPrice, mapping.sellPrice]
  const commaDecimal = usesCommaDecimals(dataRows.flatMap((r) => numberCols.map((c) => getStr(r as unknown[], c))))
  let valid: Position[] = []
  const sells: RawLot[] = []
  let skipped = 0

  for (const rawRow of dataRows) {
    const row = rawRow as unknown[]
    const ticker = getStr(row, mapping.ticker).toUpperCase()
    const dateRaw = getStr(row, mapping.date)
    const qty = getNum(row, mapping.quantity, commaDecimal)
    const price = getNum(row, mapping.buyPrice, commaDecimal)

    if (!ticker || !dateRaw || qty === 0 || price <= 0) { skipped++; continue }
    const buyDate = parseAnyDate(dateRaw)
    if (!buyDate) { skipped++; continue }

    // The wizard has no direction column, but statements commonly write a sale as negative quantity.
    if (qty < 0) {
      sells.push({
        ticker, name: ticker, type: 'stock', qty: -qty, price, date: buyDate,
        currency: getStr(row, mapping.currency) || defaults.currency, broker: '', isSell: true,
      })
      continue
    }

    const sellDateRaw = getStr(row, mapping.sellDate)
    const sellPrice = getNum(row, mapping.sellPrice, commaDecimal)
    const sellDate = sellDateRaw ? parseAnyDate(sellDateRaw) : null

    valid.push({
      id: '',
      ticker,
      name: getStr(row, mapping.name) || ticker,
      type: 'stock',
      quantity: qty,
      buyPrice: price,
      buyDate,
      currency: getStr(row, mapping.currency) || defaults.currency,
      broker: getStr(row, mapping.broker) || defaults.broker || undefined,
      isin: getStr(row, mapping.isin) || undefined,
      ...(sellDate && sellPrice > 0 ? { sellDate, sellPrice } : {}),
    } as Position)
  }

  if (sells.length) {
    const open = valid.filter((p) => !p.sellDate)
    const rawBuys: RawLot[] = open.map((p) => ({
      ticker: p.ticker, name: p.name, type: p.type, qty: p.quantity, price: p.buyPrice, date: p.buyDate,
      currency: p.currency, broker: p.broker ?? '', ...(p.isin ? { isin: p.isin } : {}), isSell: false,
    }))
    valid = [...valid.filter((p) => p.sellDate), ...applyFifo([...rawBuys, ...sells], () => { skipped++ })]
    valid.forEach((p) => { if (!p.broker) delete p.broker })
  }

  if (!valid.length) return null

  const typeMap = await batchTickers([...new Set(valid.map(p => p.ticker))])
  valid.forEach(p => { p.type = typeMap[p.ticker]?.type ?? 'stock' })

  return { valid, skipped }
}

// ── auto-suggest column mapping from header row ───────────────────────────────

export function autoDetectMapping(header: string[]): ColumnMapping {
  const h = header.map(s => String(s).toLowerCase())
  const find = (...kws: string[]) => h.findIndex(c => kws.some(k => c.includes(k)))
  return {
    ticker:    find('ticker', 'symbol', 'kód', 'instrument'),
    date:      find('date', 'datum', 'time', 'čas'),
    quantity:  find('qty', 'quantity', 'množství', 'shares', 'počet', 'volume'),
    buyPrice:  find('price', 'cena', 'buy price', 'open price', 'jednotková'),
    name:      find('name', 'název', 'product', 'instrument'),
    isin:      find('isin'),
    currency:  find('currency', 'měna', 'fx', 'ccy'),
    broker:    find('broker', 'platform', 'source'),
    sellDate:  find('sell date', 'close time', 'close date', 'prodej datum'),
    sellPrice: find('sell price', 'close price', 'prodej cena'),
  }
}

// ── main dispatcher ───────────────────────────────────────────────────────────

export async function parseFile(file: File): Promise<ParseFileResult | null> {
  const ext = file.name.split('.').pop()?.toLowerCase()
  const buf = await file.arrayBuffer()

  if (ext === 'pdf') return parsePdf(buf)

  if (ext === 'xlsx' || ext === 'csv') {
    const XLSX = await import('xlsx')
    const wb = ext === 'csv'
      // raw: keep every cell as the text in the file. Otherwise SheetJS "helpfully" reads 05-03-2024 as
      // a US date and rewrites it, so Degiro rows with a day of 12 or less vanish.
      ? XLSX.read(new TextDecoder().decode(buf), { type: 'string', raw: true })
      : XLSX.read(buf, { type: 'array', cellDates: true })

    if (wb.Sheets['Cash Operations']) {
      const { parseXtbXlsx } = await import('./xlsxParser')
      return parseXtbXlsx(buf, file.name)
    }

    const ws = wb.Sheets[wb.SheetNames[0]]
    const rows = XLSX.utils.sheet_to_json<unknown[]>(ws, { header: 1, raw: false })
    if (!rows.length) return null

    const fmt = detectCsvFormat(rows[0] as string[])
    if (fmt === 't212')   return parseT212(rows)
    if (fmt === 'degiro') return parseDegiro(rows)

    // Unknown — return raw rows for column mapping wizard
    return { type: 'needs-mapping', rows }
  }

  const text = new TextDecoder().decode(buf)
  try {
    return parsePositionsFromJson(JSON.parse(text))
  } catch { return null }
}
