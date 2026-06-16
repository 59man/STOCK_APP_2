import { ParseResult } from './importParser'
import { Position } from '../types'
import { batchIsins } from './yahooLookup'
import { applyFifo, RawLot } from './fifoMatcher'

type PDFItem = { str: string; transform: number[] }

// eslint-disable-next-line @typescript-eslint/no-explicit-any
async function getLines(pdf: any): Promise<string[]> {
  const out: string[] = []
  for (let p = 1; p <= pdf.numPages; p++) {
    const page = await pdf.getPage(p)
    const { items } = await page.getTextContent()
    const ti = (items as PDFItem[]).filter(x => x.str.trim())

    ti.sort((a, b) => {
      const dy = b.transform[5] - a.transform[5]
      return Math.abs(dy) > 3 ? dy : a.transform[4] - b.transform[4]
    })

    let curY = ti[0]?.transform[5] ?? 0
    let parts: string[] = []
    for (const item of ti) {
      if (Math.abs(item.transform[5] - curY) > 3) {
        if (parts.length) out.push(parts.join(' ').trim())
        curY = item.transform[5]
        parts = [item.str]
      } else {
        parts.push(item.str)
      }
    }
    if (parts.length) out.push(parts.join(' ').trim())
  }
  return out.filter(Boolean)
}

function czn(s: string): number {
  return parseFloat(s.trim().replace(/\s/g, '').replace(',', '.'))
}
const CZ_NUM = /-?[\d][\d ]*,\d{2}/g
const DATE_RE = /(\d{1,2}\.\d{1,2}\.\d{4})\s+(\d{2}:\d{2})/
const ISIN_RE = /[A-Z]{2}[A-Z0-9]{10}/

// ── Fio banka ────────────────────────────────────────────────────────────────

async function parseFio(lines: string[]): Promise<ParseResult> {
  type RawTx = { date: string; isin: string; name: string; qty: number; unitPrice: number; isSell: boolean }
  const txs: RawTx[] = []
  let skipped = 0

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]
    const dm = line.match(DATE_RE)
    if (!dm) continue

    const isinMatch = line.match(ISIN_RE)
    if (!isinMatch) continue

    const isin = isinMatch[0]
    const afterDate = line.slice(dm[0].length).trim()
    const isinIdx = afterDate.indexOf(isin)
    const name = afterDate.slice(0, isinIdx).trim()
    const afterIsin = afterDate.slice(isinIdx + isin.length)

    const nums = [...afterIsin.matchAll(CZ_NUM)].map(m => czn(m[0]))
    if (!nums.length || nums[0] <= 0) { skipped++; continue } // qty=0 = dividend
    const qty = nums[0]

    const nextLine = lines[i + 1] ?? ''
    const isBuy = nextLine.includes('Nákup')
    const isSell = nextLine.includes('Prodej')
    if (!isBuy && !isSell) { skipped++; continue }

    const priceNums = [...nextLine.matchAll(CZ_NUM)].map(m => czn(m[0]))
    const unitPrice = priceNums[priceNums.length - 1]
    if (!unitPrice || unitPrice <= 0) { skipped++; continue }

    const [d, m, y] = dm[1].split('.')
    txs.push({ date: `${y}-${m.padStart(2, '0')}-${d.padStart(2, '0')}`, isin, name, qty, unitPrice, isSell })
  }

  if (!txs.length) return { valid: [], skipped }

  const isinMap = await batchIsins([...new Set(txs.map(t => t.isin))])

  // Build raw lots, then FIFO-match sells against buys
  const rawLots: RawLot[] = txs.map(tx => ({
    ticker: isinMap[tx.isin]?.ticker ?? tx.isin,
    name: tx.name || tx.isin,
    qty: tx.qty,
    price: tx.unitPrice,
    date: tx.date,
    currency: 'CZK',
    broker: 'Fio banka',
    isin: tx.isin,
    type: isinMap[tx.isin]?.type ?? 'stock',
    isSell: tx.isSell,
  }))

  const valid = applyFifo(rawLots)
  return { valid, skipped }
}

// ── Revolut XAU ──────────────────────────────────────────────────────────────

function parseRevolut(lines: string[]): ParseResult {
  const MONTHS: Record<string, string> = {
    Jan:'01',Feb:'02',Mar:'03',Apr:'04',May:'05',Jun:'06',
    Jul:'07',Aug:'08',Sep:'09',Oct:'10',Nov:'11',Dec:'12',
  }
  const valid: Position[] = []

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]
    if (!line.includes('Exchanged to XAU')) continue

    const dm = line.match(/(\w{3})\s+(\d{1,2}),\s+(\d{4})/)
    if (!dm) continue
    const month = MONTHS[dm[1]]
    if (!month) continue
    const buyDate = `${dm[3]}-${month}-${dm[2].padStart(2, '0')}`

    const xauM = line.match(/([\d.]+)\s+XAU/)
    const qty = xauM ? parseFloat(xauM[1]) : 0
    if (qty <= 0) continue

    let czk = 0
    for (let j = i + 1; j <= i + 6 && j < lines.length; j++) {
      const cm = lines[j].match(/([\d,]+\.\d{2})\s+CZK/)
      if (cm) { czk = parseFloat(cm[1].replace(/,/g, '')); break }
    }
    if (czk <= 0) continue

    valid.push({
      id: '', ticker: 'XAU', name: 'Gold (XAU)', type: 'commodity',
      quantity: qty, buyPrice: czk / qty, buyDate, currency: 'CZK', broker: 'Revolut',
    })
  }

  return { valid, skipped: 0 }
}

// ── Generic PDF heuristic ─────────────────────────────────────────────────────
// Best-effort: find ISIN + nearby date + amount patterns in any broker PDF.
// Results are marked with broker = 'Unknown (verify)' so user knows to review.

const ANY_DATE = [
  /(\d{4}-\d{2}-\d{2})/,                             // YYYY-MM-DD
  /(\d{1,2}[./\-]\d{1,2}[./\-]\d{4})/,              // D.M.YYYY / D/M/YYYY / D-M-YYYY
  /(\w{3,9}\s+\d{1,2},?\s+\d{4})/,                  // Month DD YYYY
]
const ANY_NUM = /\b\d+(?:[., ]\d+)*\b/g
const MONTHS_EN: Record<string, string> = {
  january:'01',february:'02',march:'03',april:'04',may:'05',june:'06',
  july:'07',august:'08',september:'09',october:'10',november:'11',december:'12',
  jan:'01',feb:'02',mar:'03',apr:'04',may2:'05',jun:'06',
  jul:'07',aug:'08',sep:'09',oct:'10',nov:'11',dec:'12',
}

function parseAnyDate(s: string): string | null {
  // Try YYYY-MM-DD
  const iso = s.match(/(\d{4})-(\d{2})-(\d{2})/)
  if (iso) return `${iso[1]}-${iso[2]}-${iso[3]}`

  // Try D.M.YYYY or D/M/YYYY or D-M-YYYY
  const dmy = s.match(/(\d{1,2})[./\-](\d{1,2})[./\-](\d{4})/)
  if (dmy) return `${dmy[3]}-${dmy[2].padStart(2,'0')}-${dmy[1].padStart(2,'0')}`

  // Try Month DD YYYY
  const mdy = s.match(/(\w+)\s+(\d{1,2}),?\s+(\d{4})/)
  if (mdy) {
    const mo = MONTHS_EN[mdy[1].toLowerCase()]
    if (mo) return `${mdy[3]}-${mo}-${mdy[2].padStart(2,'0')}`
  }
  return null
}

async function parseGeneric(lines: string[]): Promise<ParseResult> {
  const BUY_KW = /\b(buy|nákup|kauf|achat|purchase|acqui|entrada|compra)\b/i

  type Hit = { isin: string; date: string; price: number; qty: number; name: string }
  const hits: Hit[] = []
  let skipped = 0

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]
    const isinMatch = line.match(ISIN_RE)
    if (!isinMatch) continue

    // Check ±3 lines for a buy keyword
    const window = lines.slice(Math.max(0, i - 2), i + 4).join(' ')
    if (!BUY_KW.test(window)) { skipped++; continue }

    // Find date in same line or adjacent lines
    let date: string | null = null
    for (let j = i - 2; j <= i + 2 && !date; j++) {
      if (j < 0 || j >= lines.length) continue
      for (const pat of ANY_DATE) {
        const m = lines[j].match(pat)
        if (m) { date = parseAnyDate(m[0]); break }
      }
    }
    if (!date) { skipped++; continue }

    // Extract all positive numbers from the window
    const nums = [...window.matchAll(ANY_NUM)]
      .map(m => parseFloat(m[0].replace(/[\s,]/g, '').replace(',', '.')))
      .filter(n => isFinite(n) && n > 0)
      .sort((a, b) => a - b)

    if (nums.length < 2) { skipped++; continue }
    // Heuristic: smallest plausible value = quantity, largest nearby = price
    const qty = nums[0] <= 1000 ? nums[0] : 1
    const price = nums[nums.length - 1]

    const isin = isinMatch[0]
    // Name: anything before ISIN on the line (trimmed)
    const nameRaw = line.slice(0, line.indexOf(isin)).replace(DATE_RE, '').trim()

    hits.push({ isin, date, price, qty, name: nameRaw || isin })
  }

  if (!hits.length) return { valid: [], skipped }

  const isinMap = await batchIsins([...new Set(hits.map(h => h.isin))])

  const valid = hits.map(h => ({
    id: '',
    ticker: isinMap[h.isin]?.ticker ?? h.isin,
    name: h.name || h.isin,
    type: isinMap[h.isin]?.type ?? 'stock',
    quantity: h.qty,
    buyPrice: h.price,
    buyDate: h.date,
    currency: 'USD',   // ponytail: unknown — user must verify
    broker: 'Unknown (verify)',
    isin: h.isin,
  } as Position))

  return { valid, skipped }
}

// ── Public entry point ────────────────────────────────────────────────────────

export async function parsePdf(buffer: ArrayBuffer): Promise<ParseResult | null> {
  const { getDocument, GlobalWorkerOptions } = await import('pdfjs-dist')
  GlobalWorkerOptions.workerSrc = new URL(
    'pdfjs-dist/build/pdf.worker.min.mjs',
    import.meta.url
  ).href
  const pdf = await getDocument({ data: new Uint8Array(buffer) }).promise
  const lines = await getLines(pdf)
  const flat = lines.join(' ')

  if (flat.includes('Fio banka') || flat.includes('FIOBCZPP')) return parseFio(lines)
  if (flat.includes('Revolut') && flat.includes('XAU')) return parseRevolut(lines)

  // Generic heuristic — any broker PDF
  const generic = await parseGeneric(lines)
  return generic.valid.length ? generic : null
}
