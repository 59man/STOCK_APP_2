export interface DividendEvent {
  date: string   // ISO YYYY-MM-DD (ex-dividend date)
  amount: number // gross amount per share
}

// Czech withholding tax on dividends (applies to CZ stocks and AT stocks via treaty)
export const DIVIDEND_TAX_RATE = 0.15

// Some tickers on the Prague Exchange were renamed; Yahoo Finance keeps dividend history
// under the old ticker symbol. Map display ticker → Yahoo dividend ticker.
const DIVIDEND_TICKER_ALIASES: Record<string, string> = {
  'COLT.PR': 'CZG.PR',  // Česká zbrojovka Group renamed to Colt CZ Group SE
}

export async function fetchDividendEvents(ticker: string): Promise<DividendEvent[]> {
  const lookupTicker = DIVIDEND_TICKER_ALIASES[ticker.toUpperCase()] ?? ticker
  const path = `/api/yahoo/v8/finance/chart/${encodeURIComponent(lookupTicker)}?range=max&interval=1d&events=div`
  const res = await fetch(path)
  if (!res.ok) return []
  const json = await res.json()
  const raw = json?.chart?.result?.[0]?.events?.dividends
  if (!raw) return []
  return (Object.values(raw) as Array<{ date: number; amount: number }>)
    .map(({ date, amount }) => ({
      date: new Date(date * 1000).toISOString().slice(0, 10),
      amount,
    }))
    .sort((a, b) => a.date.localeCompare(b.date))
}

// Sum of net dividends received for a group of lots
export function calcNetDividends(
  lots: Array<{ buyDate: string; quantity: number }>,
  dividends: DividendEvent[],
): number {
  return dividends.reduce((total, div) => {
    const shares = lots
      .filter((l) => l.buyDate <= div.date)
      .reduce((s, l) => s + l.quantity, 0)
    return total + shares * div.amount * (1 - DIVIDEND_TAX_RATE)
  }, 0)
}

// Cumulative net dividends received for a set of positions up to a given ISO date
export function cumNetDividendsAt(
  positions: Array<{ ticker: string; buyDate: string; quantity: number }>,
  dividendsByTicker: Map<string, DividendEvent[]>,
  upToDate: string,
): number {
  let total = 0
  for (const pos of positions) {
    const divs = dividendsByTicker.get(pos.ticker.toUpperCase()) ?? []
    for (const div of divs) {
      if (div.date > upToDate) break
      if (pos.buyDate <= div.date) {
        total += pos.quantity * div.amount * (1 - DIVIDEND_TAX_RATE)
      }
    }
  }
  return total
}
