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
import { NO_FEED_TICKERS } from '../data/noFeedTickers'
import { FUND_PROVIDER_SET } from '../data/fundProviderTickers'
import { useDailyAnchors } from '../hooks/useDailyAnchors'
import { computePortfolioIrr, deriveRow, groupByTicker } from '../utils/rowDerivation'

interface Props {
  portfolioId: string
  displayCurrency: string
  convert: (amount: number, from: string, to: string) => number
  /** IANA zone the today's-change figure resets at. */
  timeZone: string
  showAddModal: boolean
  onCloseAddModal: () => void
}

export function PortfolioContent({ portfolioId, displayCurrency, convert, timeZone, showAddModal, onCloseAddModal }: Props) {
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
  // Fund-provider tickers (auto-priced via a dedicated proxy, see useQuotes)
  // have no traceable Yahoo dividend history — skip the doomed dividend call.
  const dividendTickers = useMemo(
    () => feedTickers.filter((t) => !FUND_PROVIDER_SET.has(t.toUpperCase())),
    [feedTickers]
  )

  useEffect(() => {
    if (feedTickers.length > 0) fetchQuotes(feedTickers)
    if (dividendTickers.length > 0) fetchDividends(dividendTickers)
  }, [feedTickers, dividendTickers, fetchQuotes, fetchDividends])

  // Currencies needing a midnight FX anchor: whatever the quotes actually came back in, which
  // is not always the lot currency (8306.T quotes JPY against EUR-recorded lots).
  const quoteCurrencies = useMemo(() => {
    const seen = new Set<string>()
    feedTickers.forEach((t) => {
      const c = quotes.get(t.toUpperCase())?.currency
      if (c && c !== displayCurrency) seen.add(c)
    })
    return [...seen].sort()
  }, [feedTickers, quotes, displayCurrency])

  const { anchorFor, fxAnchorFor } = useDailyAnchors(feedTickers, timeZone, quoteCurrencies, displayCurrency)

  // Fund-provider tickers have no Yahoo history to fall back on after a page
  // reload wipes useQuotes' in-memory cache — persist each successful
  // auto-fetch as the manual price too, so a transient feed failure (a
  // refresh landing before the provider responds, a rate limit, etc.) falls
  // back to the last real live price instead of a stale pre-automation
  // manual entry from a bank report months ago.
  useEffect(() => {
    tickers.forEach((t) => {
      const key = t.toUpperCase()
      if (!FUND_PROVIDER_SET.has(key)) return
      const q = quotes.get(key)
      if (!q || !(q.price > 0) || !isFinite(q.price)) return
      if (manualPrices[key]?.price === q.price) return
      setManualPrice(t, q.price)
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [quotes, tickers])

  const refresh = () => {
    fetchQuotes(feedTickers)
    fetchDividends(dividendTickers)
  }

  const rows: PortfolioRow[] = useMemo(() => {
    const today = new Date().toISOString().slice(0, 10)
    return groupByTicker(positions).map((lots) => {
      const ticker = lots[0].ticker
      const key = ticker.toUpperCase()
      const quote = quotes.get(key)
      return deriveRow({
        lots,
        quote,
        loading: loadingSet.has(ticker),
        error: errors.get(key) ?? null,
        manual: manualPrices[key],
        dividends: dividends.get(key) ?? [],
        taxOverrides,
        today,
        convert,
        anchor: quote ? anchorFor(ticker) : undefined,
        anchorFx: quote ? fxAnchorFor(quote.currency) : undefined,
        displayCurrency,
      })
    })
  }, [positions, quotes, loadingSet, errors, dividends, manualPrices, taxOverrides, convert, displayCurrency, anchorFor, fxAnchorFor])

  const portfolioIrr = useMemo(
    () => computePortfolioIrr(positions, rows, dividends, taxOverrides, displayCurrency, new Date().toISOString().slice(0, 10), convert),
    [positions, rows, dividends, taxOverrides, convert, displayCurrency],
  )

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
