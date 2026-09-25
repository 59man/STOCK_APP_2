import type { PortfolioRow, Position, Quote } from '../types'
import type { DividendEvent } from './dividends'
import { xirr } from './xirr'
import { calcNetDividends, getDividendTaxRate } from './dividends'
import { FX_CONVERTED_TICKERS } from '../data/fxConvertedTickers'
import { dailyChange as dailyChangeOf } from './dailyChange'

export type Convert = (amount: number, from: string, to: string) => number

/** A lot is sold once it has both a sell date and a sell price — a price of 0 (a worthless
 *  delisting) still counts as sold. Mirrors `isClosedLot` in core/calc/Lots.kt. */
export const isClosedLot = (l: Position): boolean => l.sellPrice != null && !!l.sellDate
export const isOpenLot = (l: Position): boolean => !isClosedLot(l)

export interface DeriveRowInput {
  lots: Position[]
  quote?: Quote
  loading: boolean
  error: string | null
  manual?: { price: number; updatedAt?: string }
  dividends: DividendEvent[]
  taxOverrides: Record<string, number>
  /** YYYY-MM-DD — injected so the IRR terminal flow is deterministic under test. */
  today: string
  convert: Convert
  /** Midnight anchor for this ticker, in the quote's own currency. */
  anchor?: { price: number | null; lastTradedAt: number | null }
  /** Quote currency -> display currency at local midnight. */
  anchorFx?: number | null
  displayCurrency: string
}

/**
 * One aggregated ticker row. Mirrored parameter-for-parameter by `deriveRow` in
 * android/core/calc/.../RowDerivation.kt; test-fixtures/parity pins the two together.
 * The raw quote/loading/error/manual are nulled here once the row is fully closed.
 */
export function deriveRow(i: DeriveRowInput): PortfolioRow {
  const { lots, dividends: tickerDivs, taxOverrides, convert, displayCurrency } = i
  const ticker = lots[0].ticker

  const openLots = lots.filter(isOpenLot)
  const closedLots = lots.filter(isClosedLot)
  const isClosed = openLots.length === 0

  const rowCurrency = lots[0].currency
  // ponytail: converts lot amount to row currency so mixed-currency lots aggregate correctly
  const toRow = (amount: number, lotCurrency: string) => convert(amount, lotCurrency, rowCurrency)

  const totalQty = lots.reduce((s, p) => s + p.quantity, 0)
  const openQty = openLots.reduce((s, p) => s + p.quantity, 0)
  const totalCost = lots.reduce((s, p) => s + toRow(p.buyPrice * p.quantity, p.currency), 0)
  const openCost = openLots.reduce((s, p) => s + toRow(p.buyPrice * p.quantity, p.currency), 0)
  const avgBuyPrice = totalQty > 0 ? totalCost / totalQty : 0
  const firstBuyDate = [...lots].sort((a, b) => a.buyDate.localeCompare(b.buyDate))[0].buyDate

  const quote = isClosed ? undefined : i.quote
  const isLoading = !isClosed && i.loading
  const error = isClosed ? null : i.error
  const manual = isClosed ? undefined : i.manual
  const priceIsManual = !isClosed && !quote && !!manual

  // The currency the instrument itself trades in — independent of what
  // currency a given lot's buy price happened to be recorded in (e.g. a
  // CZK brokerage statement for a EUR-denominated ETC like 4GLD.DE).
  // FX-converted tickers (fxConvertedTickers.ts) fetch their price in a
  // foreign currency and multiply by an FX pair to get a CZK value, so
  // their true native currency is the fxTicker's base (e.g. EURCZK=X → EUR).
  const fxEntry = FX_CONVERTED_TICKERS[ticker.toUpperCase()]
  const nativeCurrency = fxEntry ? fxEntry.fxTicker.slice(0, 3) : (quote?.currency ?? rowCurrency)

  const avgSellPrice = closedLots.length > 0
    ? closedLots.reduce((s, l) => s + toRow(l.sellPrice! * l.quantity, l.currency), 0) / closedLots.reduce((s, l) => s + l.quantity, 0)
    : 0
  const openAvgBuy = openQty > 0 ? openCost / openQty : 0
  // Quotes arrive in the asset's native currency (JPY for .T, EUR for .AS, …),
  // which can differ from the lot currency the broker statement was priced in.
  const quotePrice = quote ? toRow(quote.price, quote.currency) : undefined
  const currentPrice = isClosed
    ? avgSellPrice
    : (quotePrice ?? manual?.price ?? avgBuyPrice)
  const currentValue = isClosed ? 0 : currentPrice * openQty

  // Dividend amounts are per-share in the ticker's native currency (Yahoo meta.currency)
  const divCurrency = tickerDivs[0]?.currency ?? rowCurrency
  const dividendIncome = toRow(calcNetDividends(lots, tickerDivs, ticker, taxOverrides), divCurrency)

  const realizedPnl = closedLots.reduce((s, l) => s + toRow((l.sellPrice! - l.buyPrice) * l.quantity, l.currency), 0)
  const unrealizedPnl = isClosed ? 0 : (currentPrice - openAvgBuy) * openQty
  const pricePnl = realizedPnl + unrealizedPnl
  const totalReturn = pricePnl + dividendIncome

  const hasUsablePrice = isClosed || (!isLoading && (!!quote || !!manual))
  const irrValue = hasUsablePrice
    ? xirr([
        ...lots.map((p) => ({ date: new Date(p.buyDate), amount: -toRow(p.buyPrice * p.quantity, p.currency) })),
        ...closedLots.map((l) => ({ date: new Date(l.sellDate!), amount: toRow(l.sellPrice! * l.quantity, l.currency) })),
        ...tickerDivs.flatMap((div) => {
          const shares = lots
            .filter((l) => l.buyDate <= div.date && (!l.sellDate || l.sellDate > div.date))
            .reduce((s, l) => s + l.quantity, 0)
          if (shares === 0) return []
          const rate = taxOverrides[`${ticker.toUpperCase()}::${div.date}`] ?? getDividendTaxRate(ticker)
          return [{ date: new Date(div.date), amount: toRow(shares * div.amount * (1 - rate), div.currency ?? rowCurrency) }]
        }),
        ...(isClosed ? [] : [{ date: new Date(i.today), amount: currentValue }]),
      ])
    : null

  const dailyChange = isClosed || !quote ? 0 : toRow(quote.change, quote.currency) * openQty

  // Today's change, measured from midnight in the user's own zone rather than from the
  // exchange's previous close — see docs/superpowers/specs/2026-09-19-timezone-anchored-
  // daily-change-design.md. Worked in the quote's own currency: the anchor price and the
  // FX anchor are both quoted there, and converting either one through the row currency
  // first would mix a spot rate into a figure that is meant to be anchored.
  const quoteCurrency = quote?.currency ?? rowCurrency
  const anchor = isClosed || !quote ? undefined : i.anchor
  const anchorFx = isClosed || !quote ? undefined : i.anchorFx
  const daily = dailyChangeOf({
    quantity: isClosed || !quote ? 0 : openQty,
    currentPrice: quote?.price ?? 0,
    anchorPrice: anchor?.price ?? null,
    currentFx: convert(1, quoteCurrency, displayCurrency),
    anchorFx: anchorFx ?? null,
    // Reconstructed from the figure the quote already carries, so the fallback keeps
    // reporting exactly what the app showed before this feature existed.
    prevClose: quote ? quote.price - quote.change : null,
  })

  return {
    ids: lots.map((p) => p.id),
    ticker,
    name: lots[0].name,
    type: lots[0].type,
    currency: lots[0].currency,
    nativeCurrency,
    lots: lots.length,
    positions: [...lots].sort((a, b) => a.buyDate.localeCompare(b.buyDate)),
    totalQuantity: totalQty,
    openQuantity: openQty,
    avgBuyPrice,
    firstBuyDate,
    currentPrice,
    currentValue,
    costBasis: totalCost,
    pnl: pricePnl,
    pnlPercent: totalCost > 0 ? (pricePnl / totalCost) * 100 : 0,
    dividendIncome,
    totalReturn,
    loading: isLoading,
    error,
    priceIsManual,
    manualPriceDate: manual?.updatedAt,
    irr: irrValue,
    isClosed,
    dailyChange,
    dailyChangeDisplay: daily.change,
    dailyChangePercent: daily.changePercent,
    dailyPriceOnlyDisplay: daily.priceOnlyChange,
    dailyChangeMethod: daily.method,
    lastTradedAt: anchor?.lastTradedAt ?? null,
  }
}

/** Groups lots by ticker in first-seen order — the row order both apps start from. */
export function groupByTicker(positions: Position[]): Position[][] {
  const groups = new Map<string, Position[]>()
  positions.forEach((p) => groups.set(p.ticker, [...(groups.get(p.ticker) ?? []), p]))
  return [...groups.values()]
}

/**
 * Portfolio-wide IRR — one XIRR over every lot, not an average of row IRRs. Mirrors
 * `computePortfolioIrr` in android/core/calc/.../RowDerivation.kt.
 */
export function computePortfolioIrr(
  positions: Position[],
  rows: PortfolioRow[],
  dividends: Map<string, DividendEvent[]>,
  taxOverrides: Record<string, number>,
  displayCurrency: string,
  today: string,
  convert: Convert,
): number | null {
  if (positions.length === 0) return null
  const anyLoading = rows.some((r) => r.loading)
  // Waits for open rows still missing a price. A closed row can legitimately have no IRR
  // (a total loss has no root), and must not blank the whole portfolio's figure.
  const anyMissingPrice = rows.some((r) => !r.isClosed && !r.error && r.irr === null && !r.loading)
  if (anyLoading || anyMissingPrice) return null

  const toDC = (amount: number, currency: string) => convert(amount, currency, displayCurrency)
  const totalCurrentValue = rows.reduce((s, r) => s + toDC(r.currentValue, r.currency), 0)

  const divCashFlows: { date: Date; amount: number }[] = []
  positions.forEach((pos) => {
    const divs = dividends.get(pos.ticker.toUpperCase()) ?? []
    divs.forEach((div) => {
      if (pos.buyDate <= div.date && (!pos.sellDate || pos.sellDate > div.date)) {
        const rate = taxOverrides[`${pos.ticker.toUpperCase()}::${div.date}`] ?? getDividendTaxRate(pos.ticker)
        divCashFlows.push({ date: new Date(div.date), amount: toDC(pos.quantity * div.amount * (1 - rate), div.currency ?? pos.currency) })
      }
    })
  })

  const sellCashFlows = positions
    .filter((p) => p.sellPrice != null && p.sellDate)
    .map((p) => ({ date: new Date(p.sellDate!), amount: toDC(p.sellPrice! * p.quantity, p.currency) }))

  return xirr([
    ...positions.map((p) => ({ date: new Date(p.buyDate), amount: -toDC(p.buyPrice * p.quantity, p.currency) })),
    ...sellCashFlows,
    ...divCashFlows,
    { date: new Date(today), amount: totalCurrentValue },
  ])
}
