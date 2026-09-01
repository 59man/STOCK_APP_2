#!/usr/bin/env node
// Opt-in smoke check for the two undocumented fund-price feeds (not part of
// `npm test`/CI — these hit live third-party endpoints and their data changes
// daily). Run with: `node server/scripts/check-fund-feeds.mjs`
// Prints the resolved price/date for a human to eyeball against each fund's
// own site — a fast way to notice if onemarkets/Fio ever change their
// endpoint shape. Parsing logic here mirrors src/utils/fundQuoteParsers.ts.
// Keep the FUNDS list below in sync with src/data/fundProviderTickers.ts.

const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36'

function parseOnemarketsCsv(csv) {
  const entries = csv
    .split(/\s+/)
    .map((chunk) => chunk.split(';'))
    .filter(([date, price]) => date && price && !isNaN(parseFloat(price)))
    .map(([date, price]) => {
      const [dd, mm, yyyy] = date.split('.')
      return { date: `${yyyy}-${mm}-${dd}`, price: parseFloat(price) }
    })
  if (entries.length === 0) throw new Error('no data points')
  const last = entries[entries.length - 1]
  const prev = entries.length > 1 ? entries[entries.length - 2] : last
  return { price: last.price, prevClose: prev.price, date: last.date }
}

function parseFioFundJson(json) {
  if (json.length === 0) throw new Error('no data points')
  const last = json[json.length - 1]
  const prev = json.length > 1 ? json[json.length - 2] : last
  return { price: last.value, prevClose: prev.value, date: last.x }
}

async function checkOnemarkets(isin) {
  const end = new Date()
  const start = new Date(end.getTime() - 14 * 24 * 60 * 60 * 1000)
  const ymd = (d) => d.toISOString().slice(0, 10).replace(/-/g, '')
  const qs = new URLSearchParams({
    isin, underlyings: '', underlyingsIds: '', start: ymd(start), end: ymd(end),
    extras: '', exchange: '', tradingStartTime: '00:00', tradingEndTime: '23:59', underlyingCurrency: '',
  })
  const res = await fetch(`https://www.onemarkets.cz/bin/onemarkets-relaunch/multi-chartdata.csv?${qs}`, {
    headers: { 'User-Agent': UA },
  })
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  return parseOnemarketsCsv(await res.text())
}

async function checkFioFund(slug) {
  const url = `https://www.fiofondy.cz/cs/podilove-fondy/${slug}`
  const pageRes = await fetch(url, { headers: { 'User-Agent': UA } })
  const cookies = typeof pageRes.headers.getSetCookie === 'function' ? pageRes.headers.getSetCookie() : []
  const cookie = cookies.map((c) => c.split(';')[0]).join('; ')
  if (!cookie) throw new Error('no session cookie returned')
  const dataRes = await fetch(`${url}?do=getFundChartData`, {
    headers: { 'User-Agent': UA, 'X-Requested-With': 'XMLHttpRequest', 'Referer': url, 'Cookie': cookie },
  })
  if (!dataRes.ok) throw new Error(`HTTP ${dataRes.status}`)
  const json = await dataRes.json()
  if (!Array.isArray(json)) throw new Error(`unexpected response shape: ${JSON.stringify(json).slice(0, 200)}`)
  return parseFioFundJson(json)
}

const FUNDS = [
  ['LU2606422355 (OM BlackRock Global Equity Dyn.)', () => checkOnemarkets('LU2606422355')],
  ['LU2606421548 (OM Fidelity World Equity Income)', () => checkOnemarkets('LU2606421548')],
  ['LU2595011649 (OM Pictet Global Opport. Alloc.)', () => checkOnemarkets('LU2595011649')],
  ['FIOG.PR (Fio Global Fond CZK)', () => checkFioFund('globalni-akciovy-fond')],
]

for (const [label, check] of FUNDS) {
  try {
    const { price, prevClose, date } = await check()
    console.log(`${label}: ${price} CZK (prev ${prevClose}) as of ${date}`)
  } catch (err) {
    console.error(`${label}: FAILED — ${err.message}`)
  }
}
