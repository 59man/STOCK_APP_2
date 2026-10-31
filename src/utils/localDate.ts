const pad = (n: number) => String(n).padStart(2, '0')

/**
 * Today in the browser's own zone, as YYYY-MM-DD. Not `toISOString().slice(0, 10)`, which is the UTC
 * date: just after midnight in Prague that is still yesterday, and Android says otherwise.
 */
export function localToday(now: Date = new Date()): string {
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`
}

/**
 * `iso` minus whole months, clamped to the end of a shorter month like java.time's minusMonths, so
 * both apps start a chart on the same day. `Date.setMonth` overflows instead: 31 Oct minus a month
 * lands on 1 Oct.
 */
export function monthsBack(iso: string, months: number): string {
  const [y, m, d] = iso.split('-').map(Number)
  const total = y * 12 + (m - 1) - months
  const year = Math.floor(total / 12)
  const month = total - year * 12
  const lastDay = new Date(Date.UTC(year, month + 1, 0)).getUTCDate()
  return `${year}-${pad(month + 1)}-${pad(Math.min(d, lastDay))}`
}
