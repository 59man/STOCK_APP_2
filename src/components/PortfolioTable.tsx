import { useState } from 'react'
import { PortfolioRow } from '../types'
import { PriceChart } from './PriceChart'

const COL_COUNT = 15

interface Props {
  rows: PortfolioRow[]
  onRemove: (ids: string[]) => void
  onRefresh: () => void
  portfolioIrr: number | null
  onSetManualPrice: (ticker: string, price: number) => void
  onClearManualPrice: (ticker: string) => void
  showClosed: boolean
  onToggleClosed: () => void
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
  // up to 4 decimal places, strip trailing zeros
  return parseFloat(n.toFixed(4)).toLocaleString()
}

function pct(n: number) {
  return (n >= 0 ? '+' : '') + n.toFixed(2) + '%'
}

export function PortfolioTable({ rows, onRemove, onRefresh, portfolioIrr, onSetManualPrice, onClearManualPrice, showClosed, onToggleClosed }: Props) {
  const [editingTicker, setEditingTicker] = useState<string | null>(null)
  const [editValue, setEditValue] = useState('')
  const [expanded, setExpanded] = useState<Set<string>>(new Set())

  const toggle = (ticker: string) =>
    setExpanded((prev) => {
      const next = new Set(prev)
      next.has(ticker) ? next.delete(ticker) : next.add(ticker)
      return next
    })

  const startEdit = (ticker: string, prefill: string) => { setEditingTicker(ticker); setEditValue(prefill) }
  const cancelEdit = () => { setEditingTicker(null); setEditValue('') }
  const commitEdit = (ticker: string, totalQty: number) => {
    const totalValue = parseFloat(editValue.replace(/\s/g, '').replace(',', '.'))
    if (isFinite(totalValue) && totalValue > 0 && totalQty > 0) onSetManualPrice(ticker, totalValue / totalQty)
    cancelEdit()
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

  // Summary covers all rows (open + closed) so totals reflect true P&L
  const totalCost = rows.reduce((s, r) => s + r.costBasis, 0)
  const totalValue = rows.reduce((s, r) => s + r.currentValue, 0)
  const totalDivs = rows.reduce((s, r) => s + r.dividendIncome, 0)
  const totalPricePnl = rows.reduce((s, r) => s + r.pnl, 0)
  const totalReturn = totalPricePnl + totalDivs
  const totalReturnPct = totalCost > 0 ? (totalReturn / totalCost) * 100 : 0

  return (
    <div className="table-wrapper">
      <div className="table-toolbar">
        <div className="summary">
          <span>Total Value: <strong>{fmt(totalValue, 'CZK')}</strong></span>
          <span className={totalPricePnl >= 0 ? 'gain' : 'loss'}>
            Price P&amp;L: <strong>{fmt(totalPricePnl, 'CZK')}</strong>
          </span>
          {totalDivs > 0 && (
            <span className="gain">Dividends: <strong>{fmt(totalDivs, 'CZK')}</strong></span>
          )}
          <span className={totalReturn >= 0 ? 'gain' : 'loss'}>
            Total Return: <strong>{fmt(totalReturn, 'CZK')}</strong> ({pct(totalReturnPct)})
          </span>
          <span className={portfolioIrr != null ? (portfolioIrr >= 0 ? 'gain' : 'loss') : ''}>
            IRR p.a.: <strong>{portfolioIrr != null ? pct(portfolioIrr * 100) : '…'}</strong>
          </span>
        </div>
        <div style={{ display: 'flex', gap: 8 }}>
          {closedCount > 0 && (
            <button className="btn-secondary" onClick={onToggleClosed}>
              {showClosed ? 'Hide closed' : `Show closed (${closedCount})`}
            </button>
          )}
          <button className="btn-secondary" onClick={onRefresh}>↻ Refresh</button>
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

              const editInline = (
                <span className="price-edit-inline">
                  <input
                    className="price-edit-input"
                    type="text"
                    value={editValue}
                    autoFocus
                    placeholder="cur. value"
                    title="Enter current total position value from your bank report"
                    onChange={(e) => setEditValue(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter') commitEdit(r.ticker, r.totalQuantity)
                      if (e.key === 'Escape') cancelEdit()
                    }}
                  />
                  <button className="price-edit-ok" onClick={() => commitEdit(r.ticker, r.totalQuantity)}>✓</button>
                  <button className="price-edit-cancel" onClick={cancelEdit}>✕</button>
                </span>
              )

              return (
                <>
                  {/* ── Aggregated row ── */}
                  <tr key={r.ticker} className={[isExpanded ? 'row-expanded' : '', r.isClosed ? 'row-closed' : ''].filter(Boolean).join(' ')}>
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
                    <td>{fmtPrice(r.avgBuyPrice, r.currency)}</td>
                    <td>{r.firstBuyDate}</td>
                    <td>
                      {r.lots > 1
                        ? <span className="lots-badge">{r.lots} lots</span>
                        : <span className="muted">—</span>}
                    </td>

                    {/* Cur. Price */}
                    <td>
                      {r.loading ? <span className="loading-dot">…</span>
                        : r.error && !r.priceIsManual ? <span className="muted">—</span>
                        : (
                          <span>
                            {fmtPrice(r.currentPrice, r.currency)}
                            {r.priceIsManual && (
                              <span className="badge-manual" title={`Manually set on ${r.manualPriceDate ?? 'unknown'} — edit in Cur. Value`}> M</span>
                            )}
                          </span>
                        )}
                    </td>

                    <td>{fmt(r.costBasis, r.currency)}</td>

                    {/* Cur. Value — edit target for manual prices */}
                    <td>
                      {r.loading ? '…'
                        : r.priceIsManual ? (
                          <span className="price-manual-wrap">
                            {fmt(r.currentValue, r.currency)}
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
                        ) : fmt(r.currentValue, r.currency)}
                    </td>

                    <td className={r.pnl >= 0 ? 'gain' : 'loss'}>{r.loading ? '…' : fmt(r.pnl, r.currency)}</td>
                    <td className={r.dividendIncome > 0 ? 'gain' : 'muted'}>
                      {r.dividendIncome > 0 ? fmt(r.dividendIncome, r.currency) : <span className="muted">—</span>}
                    </td>
                    <td className={r.totalReturn >= 0 ? 'gain' : 'loss'}>{r.loading ? '…' : fmt(r.totalReturn, r.currency)}</td>
                    <td className={totalReturnPctRow >= 0 ? 'gain' : 'loss'}>{r.loading ? '…' : pct(totalReturnPctRow)}</td>
                    <td className={r.irr != null ? (r.irr >= 0 ? 'gain' : 'loss') : ''}>
                      {r.loading ? '…' : r.irr != null ? pct(r.irr * 100) : <span className="muted">N/A</span>}
                    </td>
                    <td>
                      <button className="remove-btn" title={r.lots > 1 ? `Remove all ${r.lots} lots` : 'Remove'} onClick={() => onRemove(r.ids)}>✕</button>
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
                                      <th></th>
                                    </tr>
                                  </thead>
                                  <tbody>
                                    {r.positions.map((pos, i) => {
                                      const isSold = pos.sellPrice != null && pos.sellDate
                                      const effectivePrice = isSold ? pos.sellPrice! : r.currentPrice
                                      const posValue = isSold ? 0 : effectivePrice * pos.quantity
                                      const posCost  = pos.buyPrice * pos.quantity
                                      const posPnl   = isSold
                                        ? (pos.sellPrice! - pos.buyPrice) * pos.quantity
                                        : (effectivePrice - pos.buyPrice) * pos.quantity
                                      const posPnlPct = ((effectivePrice - pos.buyPrice) / pos.buyPrice) * 100
                                      const priceUnknown = !isSold && (r.loading || (r.error && !r.priceIsManual))
                                      return (
                                        <tr key={pos.id} className={isSold ? 'lot-closed' : ''}>
                                          <td className="muted">{i + 1}</td>
                                          <td>{pos.buyDate}</td>
                                          <td>{fmtQty(pos.quantity)}</td>
                                          <td>{fmtPrice(pos.buyPrice, pos.currency)}</td>
                                          <td>{fmt(posCost, pos.currency)}</td>
                                          {hasClosedLots && <td>{isSold ? pos.sellDate : <span className="muted">—</span>}</td>}
                                          {hasClosedLots && <td>{isSold ? fmtPrice(pos.sellPrice!, pos.currency) : <span className="muted">—</span>}</td>}
                                          <td>
                                            {isSold
                                              ? <span className="badge-sold" style={{ fontSize: 10 }}>SOLD</span>
                                              : priceUnknown ? <span className="muted">—</span> : fmt(posValue, pos.currency)}
                                          </td>
                                          <td className={posPnl >= 0 ? 'gain' : 'loss'}>
                                            {priceUnknown ? <span className="muted">—</span> : fmt(posPnl, pos.currency)}
                                          </td>
                                          <td className={posPnlPct >= 0 ? 'gain' : 'loss'}>
                                            {priceUnknown ? <span className="muted">—</span> : pct(posPnlPct)}
                                          </td>
                                          <td>
                                            <button className="remove-btn" title="Remove this lot" onClick={() => onRemove([pos.id])}>✕</button>
                                          </td>
                                        </tr>
                                      )
                                    })}
                                  </tbody>
                                </table>
                              )
                            })()}
                          </div>

                          {/* Price chart */}
                          <PriceChart ticker={r.ticker} />
                        </div>
                      </td>
                    </tr>
                  )}
                </>
              )
            })}
          </tbody>
        </table>
      </div>
    </div>
  )
}
