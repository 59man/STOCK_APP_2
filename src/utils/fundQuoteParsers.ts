export interface FundQuotePoint {
  price: number
  prevClose: number
  date: string // YYYY-MM-DD
}

/**
 * Parses the onemarkets.cz `multi-chartdata.csv` response:
 * `DD.MM.YYYY;price;; DD.MM.YYYY;price;; ...` (whitespace-separated entries).
 */
export function parseOnemarketsCsv(csv: string): FundQuotePoint {
  const entries = csv
    .split(/\s+/)
    .map((chunk) => chunk.split(';'))
    .filter(([date, price]) => date && price && !isNaN(parseFloat(price)))
    .map(([date, price]) => {
      const [dd, mm, yyyy] = date.split('.')
      return { date: `${yyyy}-${mm}-${dd}`, price: parseFloat(price) }
    })

  if (entries.length === 0) throw new Error('parseOnemarketsCsv: no data points found')

  const last = entries[entries.length - 1]
  const prev = entries.length > 1 ? entries[entries.length - 2] : last
  return { price: last.price, prevClose: prev.price, date: last.date }
}

/**
 * Parses the fiofondy.cz `?do=getFundChartData` JSON response:
 * `[{ x: 'YYYY-MM-DD', value: number }, ...]`.
 */
export function parseFioFundJson(json: { x: string; value: number }[]): FundQuotePoint {
  if (json.length === 0) throw new Error('parseFioFundJson: no data points found')

  const last = json[json.length - 1]
  const prev = json.length > 1 ? json[json.length - 2] : last
  return { price: last.value, prevClose: prev.value, date: last.x }
}
