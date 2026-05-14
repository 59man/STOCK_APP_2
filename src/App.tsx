import { useState, useEffect, useMemo } from 'react'
import { usePortfolio } from './hooks/usePortfolio'
import { useQuotes } from './hooks/useQuotes'
import { useDividends } from './hooks/useDividends'
import { useManualPrices } from './hooks/useManualPrices'
import { AddPositionModal } from './components/AddPositionModal'
import { PortfolioTable } from './components/PortfolioTable'
import { PortfolioPnLChart } from './components/PortfolioPnLChart'
import { PortfolioRow } from './types'
import { xirr } from './utils/xirr'
import { calcNetDividends } from './utils/dividends'
import './App.css'

export default function App() {
  const { positions, addPosition, removePositions } = usePortfolio()
  const { quotes, loading: loadingSet, errors, fetchTickers: fetchQuotes } = useQuotes()
  const { dividends, fetchTickers: fetchDividends } = useDividends()
  const { prices: manualPrices, setPrice: setManualPrice, removePrice: clearManualPrice } = useManualPrices()
  const [showModal, setShowModal] = useState(false)
  const [showClosed, setShowClosed] = useState(false)

  const tickers = useMemo(
    () => [...new Set(positions.map((p) => p.ticker))],
    [positions]
  )

  useEffect(() => {
    if (tickers.length > 0) {
      fetchQuotes(tickers)
      fetchDividends(tickers)
    }
  }, [tickers, fetchQuotes, fetchDividends])

  const refresh = () => {
    fetchQuotes(tickers)
    fetchDividends(tickers)
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

      const totalQty = lots.reduce((s, p) => s + p.quantity, 0)
      const openQty = openLots.reduce((s, p) => s + p.quantity, 0)
      const totalCost = lots.reduce((s, p) => s + p.buyPrice * p.quantity, 0)
      const openCost = openLots.reduce((s, p) => s + p.buyPrice * p.quantity, 0)
      const avgBuyPrice = totalCost / totalQty
      const firstBuyDate = [...lots].sort((a, b) => a.buyDate.localeCompare(b.buyDate))[0].buyDate

      const quote = isClosed ? undefined : quotes.get(ticker)
      const isLoading = !isClosed && loadingSet.has(ticker)
      const error = isClosed ? null : (errors.get(ticker) ?? null)
      const manual = isClosed ? undefined : manualPrices[ticker.toUpperCase()]
      const priceIsManual = !isClosed && !quote && !!manual

      // For fully closed rows show the avg sell price; for open/mixed show live price
      const avgSellPrice = closedLots.length > 0
        ? closedLots.reduce((s, l) => s + l.sellPrice! * l.quantity, 0) / closedLots.reduce((s, l) => s + l.quantity, 0)
        : 0
      const openAvgBuy = openQty > 0 ? openCost / openQty : 0
      const currentPrice = isClosed
        ? avgSellPrice
        : (quote?.price ?? manual?.price ?? avgBuyPrice)
      const currentValue = isClosed ? 0 : currentPrice * openQty

      const tickerDivs = dividends.get(ticker.toUpperCase()) ?? []
      const dividendIncome = calcNetDividends(lots, tickerDivs)

      // Realized P&L from closed lots + unrealized from open lots
      const realizedPnl = closedLots.reduce((s, l) => s + (l.sellPrice! - l.buyPrice) * l.quantity, 0)
      const unrealizedPnl = isClosed ? 0 : (currentPrice - openAvgBuy) * openQty
      const pricePnl = realizedPnl + unrealizedPnl
      const totalReturn = pricePnl + dividendIncome

      const today = new Date()
      const hasUsablePrice = isClosed || (!isLoading && (!!quote || !!manual))
      const irrValue =
        hasUsablePrice
          ? xirr([
              ...lots.map((p) => ({ date: new Date(p.buyDate), amount: -(p.buyPrice * p.quantity) })),
              ...closedLots.map((l) => ({ date: new Date(l.sellDate!), amount: l.sellPrice! * l.quantity })),
              ...tickerDivs.flatMap((div) => {
                const shares = lots
                  .filter((l) => l.buyDate <= div.date && (!l.sellDate || l.sellDate > div.date))
                  .reduce((s, l) => s + l.quantity, 0)
                if (shares === 0) return []
                return [{ date: new Date(div.date), amount: shares * div.amount * 0.85 }]
              }),
              ...(isClosed ? [] : [{ date: today, amount: currentValue }]),
            ])
          : null

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
      }
    })
  }, [positions, quotes, loadingSet, errors, dividends, manualPrices])

  // Portfolio-level XIRR including dividends
  const portfolioIrr = useMemo(() => {
    if (positions.length === 0) return null
    const anyLoading = rows.some((r) => r.loading)
    const anyMissingPrice = rows.some((r) => !r.error && r.irr === null && !r.loading)
    if (anyLoading || anyMissingPrice) return null

    const totalCurrentValue = rows.reduce((s, r) => s + r.currentValue, 0)

    const divCashFlows: { date: Date; amount: number }[] = []
    positions.forEach((pos) => {
      const divs = dividends.get(pos.ticker.toUpperCase()) ?? []
      divs.forEach((div) => {
        if (pos.buyDate <= div.date && (!pos.sellDate || pos.sellDate > div.date)) {
          divCashFlows.push({ date: new Date(div.date), amount: pos.quantity * div.amount * 0.85 })
        }
      })
    })

    const sellCashFlows = positions
      .filter((p) => p.sellPrice != null && p.sellDate)
      .map((p) => ({ date: new Date(p.sellDate!), amount: p.sellPrice! * p.quantity }))

    return xirr([
      ...positions.map((p) => ({ date: new Date(p.buyDate), amount: -(p.buyPrice * p.quantity) })),
      ...sellCashFlows,
      ...divCashFlows,
      { date: new Date(), amount: totalCurrentValue },
    ])
  }, [positions, rows, dividends])

  return (
    <div className="app">
      <header className="header">
        <div className="header-inner">
          <h1>📈 Stock Tracker</h1>
          <button className="btn-primary" onClick={() => setShowModal(true)}>
            + Add Position
          </button>
        </div>
      </header>

      <main className="main">
        <PortfolioTable
          rows={rows}
          onRemove={removePositions}
          onRefresh={refresh}
          portfolioIrr={portfolioIrr}
          onSetManualPrice={setManualPrice}
          onClearManualPrice={clearManualPrice}
          showClosed={showClosed}
          onToggleClosed={() => setShowClosed((v) => !v)}
        />

        {positions.length > 0 && (
          <div className="chart-section">
            <PortfolioPnLChart positions={positions} dividends={dividends} manualPrices={manualPrices} />
          </div>
        )}

      </main>

      {showModal && (
        <AddPositionModal onAdd={addPosition} onClose={() => setShowModal(false)} />
      )}
    </div>
  )
}
