import { useState, useEffect, useMemo } from 'react'
import { usePortfolio } from '../hooks/usePortfolio'
import { useQuotes } from '../hooks/useQuotes'
import { useDividends } from '../hooks/useDividends'
import { useManualPrices } from '../hooks/useManualPrices'
import { useManualDividendTaxes } from '../hooks/useManualDividendTaxes'
import { PortfolioTable } from './PortfolioTable'
import { PortfolioPnLChart } from './PortfolioPnLChart'
import { PortfolioPieCharts } from './PortfolioPieCharts'
import { AddPositionModal } from './AddPositionModal'
import { PortfolioRow } from '../types'
import { xirr } from '../utils/xirr'
import { calcNetDividends, getDividendTaxRate } from '../utils/dividends'
import { NO_FEED_TICKERS } from '../data/noFeedTickers'

interface Props {
  portfolioId: string
  displayCurrency: string
  convert: (amount: number, from: string, to: string) => number
  showAddModal: boolean
  onCloseAddModal: () => void
}

export function PortfolioContent({ portfolioId, displayCurrency, convert, showAddModal, onCloseAddModal }: Props) {
  const { positions, addPosition, removePositions, updatePosition } = usePortfolio(portfolioId)
  const { quotes, loading: loadingSet, errors, fetchTickers: fetchQuotes } = useQuotes()
  const { dividends, fetchTickers: fetchDividends } = useDividends()
  const { prices: manualPrices, setPrice: setManualPrice, removePrice: clearManualPrice } = useManualPrices(portfolioId)
  const { taxOverrides, setDivTax, clearDivTax } = useManualDividendTaxes(portfolioId)
  const [showClosed, setShowClosed] = useState(false)

  const tickers = useMemo(
    () => [...new Set(positions.map((p) => p.ticker))],
    [positions]
  )
  // No-feed tickers are manual-priced only — fetching them just produces 404 noise
  const feedTickers = useMemo(
    () => tickers.filter((t) => !NO_FEED_TICKERS.has(t.toUpperCase())),
    [tickers]
  )

  useEffect(() => {
    if (feedTickers.length > 0) {
      fetchQuotes(feedTickers)
      fetchDividends(feedTickers)
    }
  }, [feedTickers, fetchQuotes, fetchDividends])

  const refresh = () => {
    fetchQuotes(feedTickers)
    fetchDividends(feedTickers)
  }

  const rows: PortfolioRow[] = useMemo(() => {
    const groups = new Map<string, typeof positions>()
    positions.forEach((p) => {
      groups.set(p.ticker, [...(groups.get(p.ticker) ?? []), p])
    })

    return Array.from(groups.values()).map((lots) => {
      const ticker = lots[0].ticker

      const openLots = lots.filter((l) => !l.sellPrice || !l.sellDate)
      const closedLots = lots.filter((l) => l.sellPrice != null && l.sellDate)
      const isClosed = openLots.length === 0

      const rowCurrency = lots[0].currency
      // ponytail: converts lot amount to row currency so mixed-currency lots aggregate correctly
      const toRow = (amount: number, lotCurrency: string) => convert(amount, lotCurrency, rowCurrency)

      const totalQty = lots.reduce((s, p) => s + p.quantity, 0)
      const openQty = openLots.reduce((s, p) => s + p.quantity, 0)
      const totalCost = lots.reduce((s, p) => s + toRow(p.buyPrice * p.quantity, p.currency), 0)
      const openCost = openLots.reduce((s, p) => s + toRow(p.buyPrice * p.quantity, p.currency), 0)
      const avgBuyPrice = totalCost / totalQty
      const firstBuyDate = [...lots].sort((a, b) => a.buyDate.localeCompare(b.buyDate))[0].buyDate

      const quote = isClosed ? undefined : quotes.get(ticker.toUpperCase())
      const isLoading = !isClosed && loadingSet.has(ticker)
      const error = isClosed ? null : (errors.get(ticker.toUpperCase()) ?? null)
      const manual = isClosed ? undefined : manualPrices[ticker.toUpperCase()]
      const priceIsManual = !isClosed && !quote && !!manual

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

      const tickerDivs = dividends.get(ticker.toUpperCase()) ?? []
      // Dividend amounts are per-share in the ticker's native currency (Yahoo meta.currency)
      const divCurrency = tickerDivs[0]?.currency ?? rowCurrency
      const dividendIncome = toRow(calcNetDividends(lots, tickerDivs, ticker, taxOverrides), divCurrency)

      const realizedPnl = closedLots.reduce((s, l) => s + toRow((l.sellPrice! - l.buyPrice) * l.quantity, l.currency), 0)
      const unrealizedPnl = isClosed ? 0 : (currentPrice - openAvgBuy) * openQty
      const pricePnl = realizedPnl + unrealizedPnl
      const totalReturn = pricePnl + dividendIncome

      const today = new Date()
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
            ...(isClosed ? [] : [{ date: today, amount: currentValue }]),
          ])
        : null

      const dailyChange = isClosed || !quote ? 0 : toRow(quote.change, quote.currency) * openQty

      return {
        ids: lots.map((p) => p.id),
        ticker,
        name: lots[0].name,
        type: lots[0].type,
        currency: lots[0].currency,
        lots: lots.length,
        positions: [...lots].sort((a, b) => a.buyDate.localeCompare(b.buyDate)),
        totalQuantity: totalQty,
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
      }
    })
  }, [positions, quotes, loadingSet, errors, dividends, manualPrices, taxOverrides, convert])

  const portfolioIrr = useMemo(() => {
    if (positions.length === 0) return null
    const anyLoading = rows.some((r) => r.loading)
    const anyMissingPrice = rows.some((r) => !r.error && r.irr === null && !r.loading)
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
      { date: new Date(), amount: totalCurrentValue },
    ])
  }, [positions, rows, dividends, taxOverrides, convert, displayCurrency])

  return (
    <>
      <PortfolioTable
        rows={rows}
        onRemove={removePositions}
        onSellPositions={(ids, sellPrice, sellDate) =>
          ids.forEach((id) => updatePosition(id, { sellPrice, sellDate }))
        }
        onUpdatePosition={updatePosition}
        onRefresh={refresh}
        portfolioIrr={portfolioIrr}
        onSetManualPrice={setManualPrice}
        onClearManualPrice={clearManualPrice}
        showClosed={showClosed}
        onToggleClosed={() => setShowClosed((v) => !v)}
        displayCurrency={displayCurrency}
        convert={convert}
        dividendsByTicker={dividends}
        taxOverrides={taxOverrides}
        manualPrices={manualPrices}
        onSetDivTax={setDivTax}
        onClearDivTax={clearDivTax}
      />

      {positions.length > 0 && (
        <div className="chart-section">
          <PortfolioPnLChart
            positions={positions}
            dividends={dividends}
            manualPrices={manualPrices}
            quotes={quotes}
            displayCurrency={displayCurrency}
            convert={convert}
            taxOverrides={taxOverrides}
          />
          <PortfolioPieCharts
            rows={rows}
            displayCurrency={displayCurrency}
            convert={convert}
          />
        </div>
      )}

      {showAddModal && (
        <AddPositionModal onAdd={addPosition} onClose={onCloseAddModal} />
      )}
    </>
  )
}
