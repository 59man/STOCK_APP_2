export interface DividendEvent {
  date: string   // ISO YYYY-MM-DD (ex-dividend date)
  amount: number // gross amount per share
}

// Withholding tax rates by country (ISO 3166-1 alpha-2).
// Rates reflect what is typically withheld at source for Czech (EU) resident investors.
// Where the Czech DTA treaty rate is lower but NOT enforced at source, the full domestic
// rate is listed and a refund must be claimed from the foreign tax authority.
const COUNTRY_WITHHOLDING_RATES: Record<string, number> = {
  CZ: 0.15,    // Czech Republic — domestic default
  AT: 0.275,   // Austria — 27.5 % KeSt at source; DTA allows 10–15 %, claim refund for excess
  BE: 0.30,    // Belgium — 30 % at source; DTA 15 % requires prior exemption filing
  DE: 0.2637,  // Germany — 25 % + 5.5 % solidarity = 26.375 %; refund to DTA 15 % via German tax office
  DK: 0.27,    // Denmark — 27 % at source for non-residents
  ES: 0.19,    // Spain — 19 % for EU residents
  FI: 0.20,    // Finland — 20 % non-resident rate at source
  FR: 0.128,   // France — 12.8 % flat PFU rate for EU residents (social charges waived for non-French EU)
  HU: 0.00,    // Hungary — no dividend withholding tax
  IE: 0.00,    // Ireland — 0 % on UCITS distributions to non-Irish EU investors (statutory exemption)
  IT: 0.26,    // Italy — 26 % at source; refund to DTA 15 % possible
  LU: 0.00,    // Luxembourg — 0 % for non-resident EU investors on UCITS / fund distributions
  NL: 0.15,    // Netherlands — 15 % dividend tax (matches CZ-NL DTA, no refund needed)
  NO: 0.15,    // Norway (EEA) — CZ-NO DTA rate enforced at source
  PL: 0.19,    // Poland — 19 % at source; DTA allows lower rate with prior exemption
  PT: 0.25,    // Portugal — 25 % non-resident rate
  SE: 0.30,    // Sweden — 30 % at source; refund to DTA 15 % possible
  SI: 0.15,    // Slovenia — 15 % (CZ-SI DTA)
  SK: 0.15,    // Slovakia — 15 % (CZ-SK DTA)
  CH: 0.35,    // Switzerland (non-EU) — 35 % at source; refund to 15 % under CZ-CH DTA
  GB: 0.00,    // United Kingdom — no dividend WHT
  US: 0.15,    // USA — 15 % CZ-US DTA, enforced at source with IRS Form W-8BEN
}

const DEFAULT_DIVIDEND_TAX_RATE = COUNTRY_WITHHOLDING_RATES.CZ

// Maps display ticker → ISO 3166-1 alpha-2 home country of the issuing company/fund.
// Czech-listed (.PR) tickers are Czech by default and don't need an entry here.
// Add one line here when adding a new foreign position that pays dividends.
const TICKER_COUNTRY: Record<string, string> = {
  'VIG.PR':  'AT',  // Vienna Insurance Group — Austria
  'EXUS.DE': 'IE',  // iShares MSCI World ex USA UCITS ETF — Ireland-domiciled
  '4GLD.DE': 'DE',  // Xetra-Gold ETC — Germany (no dividends in practice)
  'UCG.MI':  'IT',  // UniCredit — Italy 26 % at source
  'DTE.DE':  'DE',  // Deutsche Telekom — Germany
}

export function getDividendTaxRate(ticker: string): number {
  const country = TICKER_COUNTRY[ticker.toUpperCase()]
  if (country !== undefined) return COUNTRY_WITHHOLDING_RATES[country] ?? DEFAULT_DIVIDEND_TAX_RATE
  return DEFAULT_DIVIDEND_TAX_RATE
}

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

// Sum of net dividends received for a group of lots.
// taxOverrides: optional map of `${TICKER}::${date}` → custom rate (0–1).
export function calcNetDividends(
  lots: Array<{ buyDate: string; quantity: number; sellDate?: string }>,
  dividends: DividendEvent[],
  ticker: string,
  taxOverrides?: Record<string, number>,
): number {
  const defaultRate = getDividendTaxRate(ticker)
  return dividends.reduce((total, div) => {
    const rate = taxOverrides?.[`${ticker.toUpperCase()}::${div.date}`] ?? defaultRate
    const shares = lots
      .filter((l) => l.buyDate <= div.date && (!l.sellDate || l.sellDate > div.date))
      .reduce((s, l) => s + l.quantity, 0)
    return total + shares * div.amount * (1 - rate)
  }, 0)
}

