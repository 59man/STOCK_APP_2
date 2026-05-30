import { useState, Fragment } from 'react'
import { PortfolioRow } from '../types'
import { DividendEvent, getDividendTaxRate } from '../utils/dividends'
import { PriceChart } from './PriceChart'
import { SellPositionModal } from './SellPositionModal'

const COL_COUNT = 16

interface SellTarget {
  ticker: string
  lots: Array<{ id: string; quantity: number; buyDate: string; buyPrice: number; currency: string }>
}

interface Props {
  rows: PortfolioRow[]
  onRemove: (ids: string[]) => void
  onSellPositions: (ids: string[], sellPrice: number, sellDate: string) => void
  onRefresh: () => void
  portfolioIrr: number | null
  onSetManualPrice: (ticker: string, price: number) => void
  onClearManualPrice: (ticker: string) => void
  showClosed: boolean
  onToggleClosed: () => void
  displayCurrency: string
  convert: (amount: number, from: string, to: string) => number
  dividendsByTicker: Map<string, DividendEvent[]>
  taxOverrides: Record<string, number>
  onSetDivTax: (ticker: string, date: string, rate: number) => void
  onClearDivTax: (ticker: string, date: string) => void
}

function fmt(n: number, currency = 'USD') {
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency,
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(n)
}

function fmtPrice(n: number, currency = 'USD') {
  const digits = n < 10 ? 4 : 2
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency,
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  }).format(n)
}

function fmtQty(n: number) {
  return parseFloat(n.toFixed(4)).toLocaleString()
}

function pct(n: number) {
  return (n >= 0 ? '+' : '') + n.toFixed(2) + '%'
}

// Inline component for editing dividend tax rate on a single event
function DivTaxCell({
  ticker, date, appliedRate, isOverridden, onSet, onClear,
}: {
  ticker: string; date: string; appliedRate: number; isOverridden: boolean
  onSet: (rate: number) => void; onClear: () => void
}) {
  const [editing, setEditing] = useState(false)
  const [val, setVal] = useState('')

  const commit = () => {
    const pctVal = parseFloat(val)
    if (!isNaN(pctVal) && pctVal >= 0 && pctVal <= 100) {
      onSet(pctVal / 100)
      setEditing(false)
    }
  }

  if (editing) {
    return (
      <span className="div-tax-edit">
        <input
          className="div-tax-input"
          type="number" min="0" max="100" step="0.1"
          autoFocus
          value={val}
          placeholder={(appliedRate * 100).toFixed(1)}
          onChange={(e) => setVal(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter') commit(); if (e.key === 'Escape') setEditing(false) }}
        />
        <span className="muted" style={{ fontSize: 11 }}>%</span>
        <button className="price-edit-ok" onClick={commit}>✓</button>
        <button className="price-edit-cancel" onClick={() => setEditing(false)}>✕</button>
      </span>
    )
  }

  return (
    <span className="div-tax-cell">
      <span
        className={isOverridden ? 'div-tax-custom' : 'div-tax-default'}
        title={isOverridden ? `Custom rate — click to edit (default: ${(getDividendTaxRate(ticker) * 100).toFixed(1)}%)` : 'Default rate — click to override'}
        onClick={() => { setVal((appliedRate * 100).toFixed(1)); setEditing(true) }}
      >
        {(appliedRate * 100).toFixed(1)}%
      </span>
      {isOverridden && (
        <button className="div-tax-clear" title="Reset to default" onClick={onClear}>×</button>
      )}
    </span>
  )
}

export function PortfolioTable({
  rows, onRemove, onSellPositions, onRefresh, portfolioIrr,
  onSetManualPrice, onClearManualPrice, showClosed, onToggleClosed,
  displayCurrency, convert, dividendsByTicker, taxOverrides, onSetDivTax, onClearDivTax,
}: Props) {
  const [editingTicker, setEditingTicker] = useState<string | null>(null)
  const [editValue, setEditValue] = useState('')
  const [editError, setEditError] = useState<string | null>(null)
  const [expanded, setExpanded] = useState<Set<string>>(new Set())
  const [pendingDelete, setPendingDelete] = useState<{ ids: string[]; label: string } | null>(null)
  const [pendingSell, setPendingSell] = useState<SellTarget | null>(null)

  const toggle = (ticker: string) =>
    setExpanded((prev) => {
      const next = new Set(prev)
      next.has(ticker) ? next.delete(ticker) : next.add(ticker)
      return next
    })

  const startEdit = (ticker: string, prefill: string) => { setEditingTicker(ticker); setEditValue(prefill); setEditError(null) }
  const cancelEdit = () => { setEditingTicker(null); setEditValue(''); setEditError(null) }
  const commitEdit = (ticker: string, totalQty: number) => {
    const raw = editValue.replace(/\s/g, '').replace(',', '.')
    const totalValue = parseFloat(raw)
    if (!raw || !isFinite(totalValue) || totalValue <= 0) {
      setEditError('Enter a valid positive number')
      return
    }
    onSetManualPrice(ticker, totalValue / totalQty)
    cancelEdit()
  }

  const handleExport = () => {
    const allPositions = rows.flatMap((r) => r.positions)
    const blob = new Blob([JSON.stringify(allPositions, null, 2)], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `portfolio_${new Date().toISOString().slice(0, 10)}.json`
    a.click()
    URL.revokeObjectURL(url)
  }

  if (rows.length === 0) {
    return (
      <div className="empty-state">
        <p>No positions yet. Add your first stock or ETF above.</p>
      </div>
    )
  }

  const closedCount = rows.filter((r) => r.isClosed).length
  const visibleRows = showClosed ? rows : rows.filter((r) => !r.isClosed)

  // Summary — each row's value is converted from its native currency to displayCurrency
  const cv = (amount: number, currency: string) => convert(amount, currency, displayCurrency)
  const totalCost = rows.reduce((s, r) => s + cv(r.costBasis, r.currency), 0)
  const totalValue = rows.reduce((s, r) => s + cv(r.currentValue, r.currency), 0)
  const totalDivs = rows.reduce((s, r) => s + cv(r.dividendIncome, r.currency), 0)
  const totalPricePnl = rows.reduce((s, r) => s + cv(r.pnl, r.currency), 0)
  const totalReturn = totalPricePnl + totalDivs
  const totalReturnPct = totalCost > 0 ? (totalReturn / totalCost) * 100 : 0
  const totalDailyChange = rows.reduce((s, r) => s + cv(r.dailyChange, r.currency), 0)
  const prevTotalValue = totalValue - totalDailyChange
  const dailyChangePct = prevTotalValue > 0 ? (totalDailyChange / prevTotalValue) * 100 : 0

  return (
    <div className="table-wrapper">

      {/* ── Portfolio summary grid (mobile-friendly cards) ── */}
      <div className="summary-section">
        <div className="summary-grid">
          <div className="summary-card">
            <span className="summary-label">Total Value</span>
            <span className="summary-value">{fmt(totalValue, displayCurrency)}</span>
          </div>
          <div className="summary-card">
            <span className="summary-label">Cost Basis</span>
            <span className="summary-value muted">{fmt(totalCost, displayCurrency)}</span>
          </div>
          <div className="summary-card">
            <span className="summary-label">Today's Change</span>
            <span className={`summary-value ${totalDailyChange >= 0 ? 'gain' : 'loss'}`}>
              {totalDailyChange >= 0 ? '+' : ''}{fmt(totalDailyChange, displayCurrency)}
              <span className="summary-sub">{totalDailyChange >= 0 ? '+' : ''}{dailyChangePct.toFixed(2)}%</span>
            </span>
          </div>
          <div className="summary-card">
            <span className="summary-label">Price P&amp;L</span>
            <span className={`summary-value ${totalPricePnl >= 0 ? 'gain' : 'loss'}`}>
              {totalPricePnl >= 0 ? '+' : ''}{fmt(totalPricePnl, displayCurrency)}
            </span>
          </div>
          <div className="summary-card">
            <span className="summary-label">Net Dividends</span>
            <span className={`summary-value ${totalDivs > 0 ? 'gain' : 'muted'}`}>
              {totalDivs > 0 ? '+' + fmt(totalDivs, displayCurrency) : '—'}
            </span>
          </div>
          <div className="summary-card">
            <span className="summary-label">Total Return</span>
            <span className={`summary-value ${totalReturn >= 0 ? 'gain' : 'loss'}`}>
              {totalReturn >= 0 ? '+' : ''}{fmt(totalReturn, displayCurrency)}
              <span className="summary-sub">{pct(totalReturnPct)}</span>
            </span>
          </div>
          <div className="summary-card">
            <span className="summary-label">IRR p.a.</span>
            <span className={`summary-value ${portfolioIrr != null ? (portfolioIrr >= 0 ? 'gain' : 'loss') : 'muted'}`}>
              {portfolioIrr != null ? pct(portfolioIrr * 100) : '…'}
            </span>
          </div>
        </div>
      </div>

      <div className="table-toolbar">
        <div style={{ display: 'flex', gap: 8, marginLeft: 'auto' }}>
          {closedCount > 0 && (
            <button className="btn-secondary" onClick={onToggleClosed}>
              {showClosed ? 'Hide closed' : `Show closed (${closedCount})`}
            </button>
          )}
          <button className="btn-secondary" onClick={onRefresh}>↻ Refresh</button>
          <button className="btn-secondary" onClick={handleExport} title="Download all positions as JSON">↓ Export</button>
        </div>
      </div>

      <div className="table-scroll">
        <table>
          <thead>
            <tr>
              <th></th>
              <th>Ticker</th>
              <th>Type</th>
              <th>Qty</th>
              <th>Avg Buy</th>
              <th>First Buy</th>
              <th>Lots</th>
              <th>Broker</th>
              <th>Cur. Price</th>
              <th>Cost Basis</th>
              <th>Cur. Value</th>
              <th>Price P&amp;L</th>
              <th>Divid. (net)</th>
              <th>Total Return</th>
              <th>Return %</th>
              <th>IRR p.a.</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {visibleRows.map((r) => {
              const totalReturnPctRow = r.costBasis > 0 ? (r.totalReturn / r.costBasis) * 100 : 0
              const isEditing = editingTicker === r.ticker
              const isExpanded = expanded.has(r.ticker)

              // Broker display: show broker from first lot; "Mixed" if lots differ
              const brokers = [...new Set(r.positions.map((p) => p.broker).filter(Boolean))]
              const brokerDisplay = brokers.length === 0 ? null
                : brokers.length === 1 ? brokers[0]
                : 'Mixed'

              const editInline = (
                <span className="price-edit-inline">
                  <input
                    className={`price-edit-input${editError ? ' price-edit-error' : ''}`}
                    type="text"
                    value={editValue}
                    autoFocus
                    placeholder="cur. value"
                    title="Enter current total position value from your bank report"
                    onChange={(e) => { setEditValue(e.target.value); setEditError(null) }}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter') commitEdit(r.ticker, r.totalQuantity)
                      if (e.key === 'Escape') cancelEdit()
                    }}
                  />
                  <button className="price-edit-ok" onClick={() => commitEdit(r.ticker, r.totalQuantity)}>✓</button>
                  <button className="price-edit-cancel" onClick={cancelEdit}>✕</button>
                  {editError && <span className="price-edit-err-msg">{editError}</span>}
                </span>
              )

              return (
                <Fragment key={r.ticker}>
                  {/* ── Aggregated row ── */}
                  <tr className={[isExpanded ? 'row-expanded' : '', r.isClosed ? 'row-closed' : ''].filter(Boolean).join(' ')}>
                    <td>
                      <button
                        className={`expand-btn ${isExpanded ? 'expanded' : ''}`}
                        onClick={() => toggle(r.ticker)}
                        title={isExpanded ? 'Collapse' : 'Expand transactions & chart'}
                      >▶</button>
                    </td>
                    <td>
                      <span className="ticker">{r.ticker}</span>
                      {r.isClosed && <span className="badge-sold">SOLD</span>}
                      {r.name && r.name !== r.ticker && <span className="name">{r.name}</span>}
                    </td>
                    <td><span className={`badge badge-${r.type}`}>{r.type.toUpperCase()}</span></td>
                    <td>{fmtQty(r.totalQuantity)}</td>
                    <td>{fmtPrice(cv(r.avgBuyPrice, r.currency), displayCurrency)}</td>
                    <td>{r.firstBuyDate}</td>
                    <td>
                      {r.lots > 1
                        ? <span className="lots-badge">{r.lots} lots</span>
                        : <span className="muted">—</span>}
                    </td>
                    <td>
                      {brokerDisplay
                        ? <span className="broker-badge">{brokerDisplay}</span>
                        : <span className="muted">—</span>}
                    </td>

                    {/* Cur. Price */}
                    <td>
                      {r.loading ? <span className="loading-dot">…</span>
                        : r.error && !r.priceIsManual ? <span className="muted">—</span>
                        : (
                          <span>
                            {fmtPrice(cv(r.currentPrice, r.currency), displayCurrency)}
                            {r.priceIsManual && (
                              <span className="badge-manual" title={`Manually set on ${r.manualPriceDate ?? 'unknown'} — edit in Cur. Value`}> M</span>
                            )}
                          </span>
                        )}
                    </td>

                    <td>{fmt(cv(r.costBasis, r.currency), displayCurrency)}</td>

                    {/* Cur. Value — edit target for manual prices */}
                    <td>
                      {r.loading ? '…'
                        : r.priceIsManual ? (
                          <span className="price-manual-wrap">
                            {fmt(cv(r.currentValue, r.currency), displayCurrency)}
                            {isEditing ? editInline : (
                              <>
                                <span className="badge-manual" title={`Set ${r.manualPriceDate ?? ''} — click to update`}
                                  onClick={() => startEdit(r.ticker, r.currentValue.toFixed(2))}>M</span>
                                <button className="price-clear-btn" title="Clear manual price" onClick={() => onClearManualPrice(r.ticker)}>×</button>
                              </>
                            )}
                          </span>
                        ) : r.error ? (
                          <span className="price-error-wrap">
                            {isEditing ? editInline
                              : <button className="price-set-btn" onClick={() => startEdit(r.ticker, '')}>Set</button>}
                          </span>
                        ) : fmt(cv(r.currentValue, r.currency), displayCurrency)}
                    </td>

                    <td className={r.pnl >= 0 ? 'gain' : 'loss'}>{r.loading ? '…' : fmt(cv(r.pnl, r.currency), displayCurrency)}</td>
                    <td className={r.dividendIncome > 0 ? 'gain' : 'muted'}>
                      {r.dividendIncome > 0 ? fmt(cv(r.dividendIncome, r.currency), displayCurrency) : <span className="muted">—</span>}
                    </td>
                    <td className={r.totalReturn >= 0 ? 'gain' : 'loss'}>{r.loading ? '…' : fmt(cv(r.totalReturn, r.currency), displayCurrency)}</td>
                    <td className={totalReturnPctRow >= 0 ? 'gain' : 'loss'}>{r.loading ? '…' : pct(totalReturnPctRow)}</td>
                    <td className={r.irr != null ? (r.irr >= 0 ? 'gain' : 'loss') : ''}>
                      {r.loading ? '…' : r.irr != null ? pct(r.irr * 100) : <span className="muted">N/A</span>}
                    </td>
                    <td>
                      <span style={{ display: 'flex', gap: 4, justifyContent: 'flex-end' }}>
                        {!r.isClosed && (
                          <button
                            className="sell-btn"
                            title="Sell / close position"
                            onClick={() => {
                              const openLots = r.positions.filter((p) => !p.sellPrice || !p.sellDate)
                              setPendingSell({ ticker: r.ticker, lots: openLots.map((p) => ({ id: p.id, quantity: p.quantity, buyDate: p.buyDate, buyPrice: p.buyPrice, currency: p.currency })) })
                            }}
                          >Sell</button>
                        )}
                        <button
                          className="remove-btn"
                          title={r.lots > 1 ? `Remove all ${r.lots} lots` : 'Remove'}
                          onClick={() => setPendingDelete({ ids: r.ids, label: r.lots > 1 ? `all ${r.lots} lots of ${r.ticker}` : r.ticker })}
                        >✕</button>
                      </span>
                    </td>
                  </tr>

                  {/* ── Expanded detail row ── */}
                  {isExpanded && (
                    <tr className="detail-row">
                      <td colSpan={COL_COUNT + 1}>
                        <div className="detail-container">

                          {/* Individual lots mini-table */}
                          <div className="lot-table-wrap">
                            {(() => {
                              const hasClosedLots = r.positions.some((p) => p.sellPrice != null && p.sellDate)
                              const hasBrokers = r.positions.some((p) => p.broker)
                              return (
                                <table className="lot-table">
                                  <thead>
                                    <tr>
                                      <th>#</th>
                                      <th>Buy Date</th>
                                      <th>Qty</th>
                                      <th>Buy Price</th>
                                      <th>Cost</th>
                                      {hasClosedLots && <th>Sell Date</th>}
                                      {hasClosedLots && <th>Sell Price</th>}
                                      <th>Cur. Value</th>
                                      <th>P&amp;L</th>
                                      <th>P&amp;L %</th>
                                      {hasBrokers && <th>Broker</th>}
                                      <th></th>
                                    </tr>
                                  </thead>
                                  <tbody>
                                    {r.positions.map((pos, i) => {
                                      const isSold = pos.sellPrice != null && pos.sellDate
                                      const effectivePrice = isSold ? pos.sellPrice! : r.currentPrice
                                      const posValue = isSold ? 0 : cv(effectivePrice * pos.quantity, pos.currency)
                                      const posCost  = cv(pos.buyPrice * pos.quantity, pos.currency)
                                      const posPnl   = cv(isSold
                                        ? (pos.sellPrice! - pos.buyPrice) * pos.quantity
                                        : (effectivePrice - pos.buyPrice) * pos.quantity, pos.currency)
                                      const posPnlPct = ((effectivePrice - pos.buyPrice) / pos.buyPrice) * 100
                                      const priceUnknown = !isSold && (r.loading || (r.error && !r.priceIsManual))
                                      return (
                                        <tr key={pos.id} className={isSold ? 'lot-closed' : ''}>
                                          <td className="muted">{i + 1}</td>
                                          <td>{pos.buyDate}</td>
                                          <td>{fmtQty(pos.quantity)}</td>
                                          <td>{fmtPrice(cv(pos.buyPrice, pos.currency), displayCurrency)}</td>
                                          <td>{fmt(posCost, displayCurrency)}</td>
                                          {hasClosedLots && <td>{isSold ? pos.sellDate : <span className="muted">—</span>}</td>}
                                          {hasClosedLots && <td>{isSold ? fmtPrice(cv(pos.sellPrice!, pos.currency), displayCurrency) : <span className="muted">—</span>}</td>}
                                          <td>
                                            {isSold
                                              ? <span className="badge-sold" style={{ fontSize: 10 }}>SOLD</span>
                                              : priceUnknown ? <span className="muted">—</span> : fmt(posValue, displayCurrency)}
                                          </td>
                                          <td className={posPnl >= 0 ? 'gain' : 'loss'}>
                                            {priceUnknown ? <span className="muted">—</span> : fmt(posPnl, displayCurrency)}
                                          </td>
                                          <td className={posPnlPct >= 0 ? 'gain' : 'loss'}>
                                            {priceUnknown ? <span className="muted">—</span> : pct(posPnlPct)}
                                          </td>
                                          {hasBrokers && (
                                            <td>{pos.broker ? <span className="broker-badge">{pos.broker}</span> : <span className="muted">—</span>}</td>
                                          )}
                                          <td>
                                            <span style={{ display: 'flex', gap: 4, justifyContent: 'flex-end' }}>
                                              {!isSold && (
                                                <button
                                                  className="sell-btn sell-btn-sm"
                                                  title="Sell this lot"
                                                  onClick={() => setPendingSell({ ticker: r.ticker, lots: [{ id: pos.id, quantity: pos.quantity, buyDate: pos.buyDate, buyPrice: pos.buyPrice, currency: pos.currency }] })}
                                                >Sell</button>
                                              )}
                                              <button
                                                className="remove-btn"
                                                title="Remove this lot"
                                                onClick={() => setPendingDelete({ ids: [pos.id], label: `lot ${i + 1} of ${r.ticker}` })}
                                              >✕</button>
                                            </span>
                                          </td>
                                        </tr>
                                      )
                                    })}
                                  </tbody>
                                </table>
                              )
                            })()}
                          </div>

                          {/* Dividend events panel with editable tax rates */}
                          {(() => {
                            const tickerDivs = dividendsByTicker.get(r.ticker.toUpperCase()) ?? []
                            const relevantDivs = tickerDivs.filter((div) =>
                              r.positions.some((lot) => lot.buyDate <= div.date && (!lot.sellDate || lot.sellDate > div.date))
                            )
                            if (relevantDivs.length === 0) return null

                            return (
                              <div className="div-panel">
                                <div className="div-panel-title">Dividends received</div>
                                <table className="lot-table">
                                  <thead>
                                    <tr>
                                      <th>Ex-date</th>
                                      <th>Per share</th>
                                      <th>Shares</th>
                                      <th>Gross</th>
                                      <th>Tax %</th>
                                      <th>Net</th>
                                    </tr>
                                  </thead>
                                  <tbody>
                                    {relevantDivs.map((div) => {
                                      const shares = r.positions
                                        .filter((lot) => lot.buyDate <= div.date && (!lot.sellDate || lot.sellDate > div.date))
                                        .reduce((s, lot) => s + lot.quantity, 0)
                                      const overrideKey = `${r.ticker.toUpperCase()}::${div.date}`
                                      const defaultRate = getDividendTaxRate(r.ticker)
                                      const appliedRate = taxOverrides[overrideKey] ?? defaultRate
                                      const isOverridden = overrideKey in taxOverrides
                                      const gross = shares * div.amount
                                      const net = gross * (1 - appliedRate)
                                      return (
                                        <tr key={div.date}>
                                          <td>{div.date}</td>
                                          <td>{div.amount.toFixed(4)}</td>
                                          <td>{fmtQty(shares)}</td>
                                          <td>{fmt(gross, r.currency)}</td>
                                          <td>
                                            <DivTaxCell
                                              ticker={r.ticker}
                                              date={div.date}
                                              appliedRate={appliedRate}
                                              isOverridden={isOverridden}
                                              onSet={(rate) => onSetDivTax(r.ticker, div.date, rate)}
                                              onClear={() => onClearDivTax(r.ticker, div.date)}
                                            />
                                          </td>
                                          <td className="gain">{fmt(net, r.currency)}</td>
                                        </tr>
                                      )
                                    })}
                                  </tbody>
                                </table>
                              </div>
                            )
                          })()}

                          {/* Price chart */}
                          <PriceChart
                            ticker={r.ticker}
                            tickerCurrency={r.currency}
                            displayCurrency={displayCurrency}
                            convert={convert}
                          />
                        </div>
                      </td>
                    </tr>
                  )}
                </Fragment>
              )
            })}
          </tbody>
        </table>
      </div>

      {pendingSell && (
        <SellPositionModal
          ticker={pendingSell.ticker}
          lots={pendingSell.lots}
          onSell={onSellPositions}
          onClose={() => setPendingSell(null)}
        />
      )}

      {pendingDelete && (
        <div className="modal-overlay" onClick={() => setPendingDelete(null)}>
          <div className="modal" style={{ maxWidth: 360 }} onClick={(e) => e.stopPropagation()}>
            <div className="modal-header">
              <h2>Remove position?</h2>
              <button className="close-btn" onClick={() => setPendingDelete(null)}>✕</button>
            </div>
            <p style={{ padding: '0 4px 20px', color: '#aaa', fontSize: 14 }}>
              Remove <strong style={{ color: '#e2e8f0' }}>{pendingDelete.label}</strong>? This cannot be undone.
            </p>
            <div className="modal-actions">
              <button className="btn-secondary" onClick={() => setPendingDelete(null)}>Cancel</button>
              <button className="btn-danger" onClick={() => { onRemove(pendingDelete.ids); setPendingDelete(null) }}>Remove</button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
