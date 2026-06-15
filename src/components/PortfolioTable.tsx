import { useState, Fragment, useRef, useEffect } from 'react'
import { PortfolioRow, Position } from '../types'
import { DividendEvent, getDividendTaxRate } from '../utils/dividends'
import { ManualPriceEntry } from '../hooks/useManualPrices'
import { PriceChart } from './PriceChart'
import { SellPositionModal } from './SellPositionModal'

// ── Ticker fetch test & ISIN lookup ───────────────────────────────────────────
interface FetchTestResult { state: 'loading' | 'ok' | 'error'; msg: string }

async function testTickerFetch(ticker: string): Promise<{ ok: boolean; msg: string }> {
  const ac = new AbortController()
  const timer = setTimeout(() => ac.abort(), 8000)
  try {
    const res = await fetch(
      `/api/yahoo/v8/finance/chart/${encodeURIComponent(ticker.trim())}?interval=1d&range=1d`,
      { signal: ac.signal }
    )
    clearTimeout(timer)
    if (res.status === 429) return { ok: false, msg: 'Rate limited (429) — try later' }
    if (!res.ok) return { ok: false, msg: `HTTP ${res.status}` }
    const json = await res.json()
    const meta = json?.chart?.result?.[0]?.meta
    const price = meta?.regularMarketPrice
    if (!price) return { ok: false, msg: 'No price in response' }
    return { ok: true, msg: `${price} ${meta?.currency ?? ''}` }
  } catch (e) {
    clearTimeout(timer)
    return { ok: false, msg: e instanceof Error ? (e.name === 'AbortError' ? 'Timeout' : e.message) : 'Failed' }
  }
}

async function lookupTickerByIsin(query: string): Promise<{ ticker: string; name: string } | null> {
  try {
    const res = await fetch(
      `/api/yahoo/v1/finance/search?q=${encodeURIComponent(query)}&quotesCount=1&newsCount=0`
    )
    if (!res.ok) return null
    const data = await res.json()
    const hit = data?.quotes?.[0]
    if (!hit?.symbol) return null
    return { ticker: hit.symbol as string, name: (hit.longname || hit.shortname || '') as string }
  } catch {
    return null
  }
}

// ── Column definitions ────────────────────────────────────────────────────────
type ColKey = 'type' | 'qty' | 'avgBuy' | 'firstBuy' | 'lots' | 'broker' | 'curPrice' | 'today' | 'costBasis' | 'curValue' | 'pricePnl' | 'dividends' | 'totalReturn' | 'returnPct' | 'irr'

interface ColumnDef {
  key: ColKey
  label: string
  defaultVisible: boolean
  hideBelow?: number  // hide by default when viewport width ≤ this value
}

const COLUMN_DEFS: ColumnDef[] = [
  { key: 'type',        label: 'Type',         defaultVisible: true, hideBelow: 640  },
  { key: 'qty',         label: 'Qty',          defaultVisible: true, hideBelow: 400  },
  { key: 'avgBuy',      label: 'Avg Buy',      defaultVisible: true, hideBelow: 960  },
  { key: 'firstBuy',    label: 'First Buy',    defaultVisible: true, hideBelow: 960  },
  { key: 'lots',        label: 'Lots',         defaultVisible: true, hideBelow: 960  },
  { key: 'broker',      label: 'Broker',       defaultVisible: true, hideBelow: 960  },
  { key: 'curPrice',    label: 'Cur. Price',   defaultVisible: true, hideBelow: 640  },
  { key: 'today',       label: 'Today',        defaultVisible: true, hideBelow: 960  },
  { key: 'costBasis',   label: 'Cost Basis',   defaultVisible: true, hideBelow: 960  },
  { key: 'curValue',    label: 'Cur. Value',   defaultVisible: true  },
  { key: 'pricePnl',    label: 'Price P&L',    defaultVisible: true  },
  { key: 'dividends',   label: 'Divid. (net)', defaultVisible: true, hideBelow: 960  },
  { key: 'totalReturn', label: 'Total Return', defaultVisible: true, hideBelow: 640  },
  { key: 'returnPct',   label: 'Return %',     defaultVisible: true  },
  { key: 'irr',         label: 'IRR p.a.',     defaultVisible: true, hideBelow: 960  },
]

// CSS class for each column
const COL_CLASS: Record<ColKey, string> = {
  type:        'col-type',
  qty:         'col-qty',
  avgBuy:      'col-avg-buy',
  firstBuy:    'col-first-buy',
  lots:        'col-lots',
  broker:      'col-broker',
  curPrice:    'col-cur-price',
  today:       'col-today',
  costBasis:   'col-cost-basis',
  curValue:    'col-cur-value',
  pricePnl:    'col-price-pnl',
  dividends:   'col-dividends',
  totalReturn: 'col-total-return',
  returnPct:   'col-return-pct',
  irr:         'col-irr',
}

const COL_STORAGE_KEY = 'stock_tracker_column_config'

interface ColConfig { key: ColKey; visible: boolean }

function loadColConfig(): ColConfig[] {
  const width = typeof window !== 'undefined' ? window.innerWidth : 9999
  try {
    const raw = localStorage.getItem(COL_STORAGE_KEY)
    if (raw) {
      const stored = JSON.parse(raw) as { key: string; visible: boolean }[]
      const knownKeys = new Set(COLUMN_DEFS.map(d => d.key as string))
      const storedKeys = new Set(stored.map(c => c.key))
      return [
        ...stored.filter(c => knownKeys.has(c.key)) as ColConfig[],
        ...COLUMN_DEFS.filter(d => !storedKeys.has(d.key)).map(d => ({
          key: d.key,
          visible: !d.hideBelow || width > d.hideBelow,
        })),
      ]
    }
  } catch {}
  return COLUMN_DEFS.map(d => ({
    key: d.key,
    visible: !d.hideBelow || width > d.hideBelow,
  }))
}

function saveColConfig(cfg: ColConfig[]) {
  try { localStorage.setItem(COL_STORAGE_KEY, JSON.stringify(cfg)) } catch {}
}

// ── Supporting types ──────────────────────────────────────────────────────────
interface SellTarget {
  ticker: string
  lots: Array<{ id: string; quantity: number; buyDate: string; buyPrice: number; currency: string }>
}

interface Props {
  rows: PortfolioRow[]
  onRemove: (ids: string[]) => void
  onSellPositions: (ids: string[], sellPrice: number, sellDate: string) => void
  onUpdatePosition: (id: string, updates: Partial<Omit<Position, 'id'>>) => void
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
  manualPrices: Record<string, ManualPriceEntry>
  onSetDivTax: (ticker: string, date: string, rate: number) => void
  onClearDivTax: (ticker: string, date: string) => void
}

// ── Formatting helpers ────────────────────────────────────────────────────────
function fmt(n: number, currency = 'USD') {
  return new Intl.NumberFormat('en-US', {
    style: 'currency', currency, minimumFractionDigits: 2, maximumFractionDigits: 2,
  }).format(n)
}

function fmtPrice(n: number, currency = 'USD') {
  const digits = n < 10 ? 4 : 2
  return new Intl.NumberFormat('en-US', {
    style: 'currency', currency, minimumFractionDigits: digits, maximumFractionDigits: digits,
  }).format(n)
}

function fmtQty(n: number) { return parseFloat(n.toFixed(4)).toLocaleString() }
function pct(n: number) { return (n >= 0 ? '+' : '') + n.toFixed(2) + '%' }

// ── DivTaxCell ────────────────────────────────────────────────────────────────
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
    if (!isNaN(pctVal) && pctVal >= 0 && pctVal <= 100) { onSet(pctVal / 100); setEditing(false) }
  }

  if (editing) {
    return (
      <span className="div-tax-edit">
        <input
          className="div-tax-input" type="number" min="0" max="100" step="0.1"
          autoFocus value={val} placeholder={(appliedRate * 100).toFixed(1)}
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

// ── Main component ────────────────────────────────────────────────────────────
export function PortfolioTable({
  rows, onRemove, onSellPositions, onUpdatePosition, onRefresh, portfolioIrr,
  onSetManualPrice, onClearManualPrice, showClosed, onToggleClosed,
  displayCurrency, convert, dividendsByTicker, taxOverrides, manualPrices, onSetDivTax, onClearDivTax,
}: Props) {
  const [editingTicker, setEditingTicker] = useState<string | null>(null)
  const [editValue, setEditValue] = useState('')
  const [editError, setEditError] = useState<string | null>(null)
  const [expanded, setExpanded] = useState<Set<string>>(new Set())
  const [pendingDelete, setPendingDelete] = useState<{ ids: string[]; label: string } | null>(null)
  const [pendingSell, setPendingSell] = useState<SellTarget | null>(null)

  // Column config
  const [colConfig, setColConfig] = useState<ColConfig[]>(loadColConfig)
  const [showColPanel, setShowColPanel] = useState(false)
  const colPanelRef = useRef<HTMLDivElement>(null)

  // Edit mode
  const [editMode, setEditMode] = useState(false)
  const [editingLotId, setEditingLotId] = useState<string | null>(null)
  const [lotDraft, setLotDraft] = useState<Partial<Omit<Position, 'id'>>>({})
  const [nameEdits, setNameEdits] = useState<Record<string, string>>({})
  const [tickerEdits, setTickerEdits] = useState<Record<string, string>>({})
  const [isinEdits, setIsinEdits] = useState<Record<string, string>>({})
  const [fetchTests, setFetchTests] = useState<Record<string, FetchTestResult>>({})
  const [lookingUp, setLookingUp] = useState<Record<string, boolean>>({})

  useEffect(() => {
    if (!showColPanel) return
    const handler = (e: MouseEvent) => {
      if (colPanelRef.current && !colPanelRef.current.contains(e.target as Node)) {
        setShowColPanel(false)
      }
    }
    document.addEventListener('mousedown', handler)
    return () => document.removeEventListener('mousedown', handler)
  }, [showColPanel])

  const activeColumns = colConfig.filter(c => c.visible)

  const toggleColumn = (key: ColKey) => {
    const next = colConfig.map(c => c.key === key ? { ...c, visible: !c.visible } : c)
    setColConfig(next); saveColConfig(next)
  }

  const moveColumn = (index: number, dir: -1 | 1) => {
    const target = index + dir
    if (target < 0 || target >= colConfig.length) return
    const next = [...colConfig]
    ;[next[index], next[target]] = [next[target], next[index]]
    setColConfig(next); saveColConfig(next)
  }

  const resetColumns = () => {
    const width = window.innerWidth
    const next = COLUMN_DEFS.map(d => ({ key: d.key, visible: !d.hideBelow || width > d.hideBelow }))
    setColConfig(next); saveColConfig(next)
  }

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
    if (!raw || !isFinite(totalValue) || totalValue <= 0) { setEditError('Enter a valid positive number'); return }
    onSetManualPrice(ticker, totalValue / totalQty)
    cancelEdit()
  }

  // Lot inline editing
  const startLotEdit = (pos: Position) => {
    setEditingLotId(pos.id)
    setLotDraft({
      buyDate: pos.buyDate,
      quantity: pos.quantity,
      buyPrice: pos.buyPrice,
      currency: pos.currency,
      broker: pos.broker,
      sellDate: pos.sellDate,
      sellPrice: pos.sellPrice,
    })
  }

  const commitLotEdit = (id: string) => {
    const qty = parseFloat(String(lotDraft.quantity ?? ''))
    const buyP = parseFloat(String(lotDraft.buyPrice ?? ''))
    if (!isFinite(qty) || qty <= 0) return
    if (!isFinite(buyP) || buyP < 0) return
    const updates: Partial<Omit<Position, 'id'>> = {
      buyDate: lotDraft.buyDate ?? '',
      quantity: qty,
      buyPrice: buyP,
      currency: lotDraft.currency ?? 'CZK',
      broker: lotDraft.broker || undefined,
    }
    if (lotDraft.sellDate) {
      updates.sellDate = lotDraft.sellDate
      const sp = parseFloat(String(lotDraft.sellPrice ?? ''))
      if (isFinite(sp) && sp >= 0) updates.sellPrice = sp
    } else {
      updates.sellDate = undefined
      updates.sellPrice = undefined
    }
    onUpdatePosition(id, updates)
    setEditingLotId(null)
    setLotDraft({})
  }

  const cancelLotEdit = () => { setEditingLotId(null); setLotDraft({}) }

  const commitNameEdit = (ticker: string, positions: Position[]) => {
    const v = nameEdits[ticker]
    if (v !== undefined && v.trim() && v.trim() !== positions[0]?.name) {
      positions.forEach(p => onUpdatePosition(p.id, { name: v.trim() }))
    }
    setNameEdits(prev => { const n = { ...prev }; delete n[ticker]; return n })
  }

  const commitTickerEdit = (oldTicker: string, positions: Position[]) => {
    const newTicker = (tickerEdits[oldTicker] ?? oldTicker).toUpperCase().trim()
    if (newTicker && newTicker !== oldTicker) {
      positions.forEach(p => onUpdatePosition(p.id, { ticker: newTicker }))
    }
    setTickerEdits(prev => { const n = { ...prev }; delete n[oldTicker]; return n })
    setFetchTests(prev => { const n = { ...prev }; delete n[oldTicker]; return n })
  }

  const commitIsinEdit = (ticker: string, positions: Position[]) => {
    const v = (isinEdits[ticker] ?? '').trim().toUpperCase()
    const current = positions[0]?.isin ?? ''
    if (v !== current) {
      positions.forEach(p => onUpdatePosition(p.id, { isin: v || undefined }))
    }
    setIsinEdits(prev => { const n = { ...prev }; delete n[ticker]; return n })
  }

  const runFetchTest = async (rowTicker: string) => {
    const ticker = (tickerEdits[rowTicker] ?? rowTicker).trim()
    if (!ticker) return
    setFetchTests(prev => ({ ...prev, [rowTicker]: { state: 'loading', msg: '' } }))
    const result = await testTickerFetch(ticker)
    setFetchTests(prev => ({ ...prev, [rowTicker]: { state: result.ok ? 'ok' : 'error', msg: result.msg } }))
  }

  const runLookup = async (rowTicker: string, positions: Position[]) => {
    const query = (isinEdits[rowTicker] ?? positions[0]?.isin ?? '').trim()
    if (!query) return
    setLookingUp(prev => ({ ...prev, [rowTicker]: true }))
    const result = await lookupTickerByIsin(query)
    setLookingUp(prev => ({ ...prev, [rowTicker]: false }))
    if (result) {
      setTickerEdits(prev => ({ ...prev, [rowTicker]: result.ticker }))
      if (result.name) setNameEdits(prev => ({ ...prev, [rowTicker]: result.name }))
    }
  }

  const handleExport = () => {
    const allPositions = rows.flatMap((r) => r.positions)
    const exportData: Record<string, unknown> = { version: 1, positions: allPositions }
    if (Object.keys(taxOverrides).length > 0) exportData.dividendTaxOverrides = taxOverrides
    if (Object.keys(manualPrices).length > 0) exportData.manualPrices = manualPrices
    const blob = new Blob([JSON.stringify(exportData, null, 2)], { type: 'application/json' })
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

  const detailColSpan = activeColumns.length + 3

  return (
    <div className="table-wrapper">

      {/* ── Summary grid ── */}
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

      {/* ── Toolbar ── */}
      <div className="table-toolbar">
        <div style={{ display: 'flex', gap: 8, marginLeft: 'auto', alignItems: 'center', flexWrap: 'wrap' }}>
          {closedCount > 0 && (
            <button className="btn-secondary" onClick={onToggleClosed}>
              {showClosed ? 'Hide closed' : `Show closed (${closedCount})`}
            </button>
          )}
          <button
            className={`btn-secondary${editMode ? ' active' : ''}`}
            onClick={() => { setEditMode(v => !v); setEditingLotId(null); setLotDraft({}) }}
            title="Toggle edit mode to modify position data"
          >
            {editMode ? '✓ Done' : '✎ Edit'}
          </button>
          <button className="btn-secondary" onClick={onRefresh}>↻ Refresh</button>
          <button className="btn-secondary" onClick={handleExport} title="Download all positions as JSON">↓ Export</button>
          {/* Column config button */}
          <div className="col-panel-wrap" ref={colPanelRef}>
            <button
              className={`btn-secondary${showColPanel ? ' active' : ''}`}
              onClick={() => setShowColPanel(v => !v)}
              title="Show/hide and reorder columns"
            >⚙ Columns</button>
            {showColPanel && (
              <>
                <div className="col-panel-backdrop" onClick={() => setShowColPanel(false)} />
                <div className="col-panel">
                  {colConfig.map((col, i) => {
                    const def = COLUMN_DEFS.find(d => d.key === col.key)
                    return (
                      <div key={col.key} className="col-panel-item">
                        <label>
                          <input
                            type="checkbox"
                            checked={col.visible}
                            onChange={() => toggleColumn(col.key)}
                          />
                          {def?.label ?? col.key}
                        </label>
                        <div className="col-panel-arrows">
                          <button onClick={() => moveColumn(i, -1)} disabled={i === 0} title="Move up">↑</button>
                          <button onClick={() => moveColumn(i, 1)} disabled={i === colConfig.length - 1} title="Move down">↓</button>
                        </div>
                      </div>
                    )
                  })}
                  <div className="col-panel-divider" />
                  <button className="col-panel-reset" onClick={resetColumns}>Reset to default</button>
                </div>
              </>
            )}
          </div>
        </div>
      </div>

      {/* ── Table ── */}
      <div className="table-scroll">
        <table>
          <thead>
            <tr>
              <th></th>
              <th>Ticker</th>
              {activeColumns.map(col => (
                <th key={col.key} className={COL_CLASS[col.key]}>
                  {COLUMN_DEFS.find(d => d.key === col.key)?.label}
                </th>
              ))}
              <th></th>
            </tr>
          </thead>
          <tbody>
            {visibleRows.map((r) => {
              const totalReturnPctRow = r.costBasis > 0 ? (r.totalReturn / r.costBasis) * 100 : 0
              const isEditing = editingTicker === r.ticker
              const isExpanded = expanded.has(r.ticker)
              const brokers = [...new Set(r.positions.map((p) => p.broker).filter(Boolean))]
              const brokerDisplay = brokers.length === 0 ? null : brokers.length === 1 ? brokers[0] : 'Mixed'
              const noLivePrice = r.isClosed || r.priceIsManual || (!!r.error && !r.priceIsManual)
              const dailyVal = cv(r.dailyChange, r.currency)
              const prevVal = cv(r.currentValue, r.currency) - dailyVal
              const dailyPct = prevVal > 0 ? (dailyVal / prevVal) * 100 : 0

              const editInline = (
                <span className="price-edit-inline">
                  <input
                    className={`price-edit-input${editError ? ' price-edit-error' : ''}`}
                    type="text" value={editValue} autoFocus placeholder="cur. value"
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
                  <tr className={[isExpanded ? 'row-expanded' : '', r.isClosed ? 'row-closed' : ''].filter(Boolean).join(' ')}>
                    {/* Fixed: expand button */}
                    <td>
                      <button
                        className={`expand-btn ${isExpanded ? 'expanded' : ''}`}
                        onClick={() => toggle(r.ticker)}
                        title={isExpanded ? 'Collapse' : 'Expand transactions & chart'}
                      >▶</button>
                    </td>

                    {/* Fixed: Ticker */}
                    <td>
                      {editMode ? (
                        <div className="ticker-edit-block">
                          {/* Row 1: editable ticker + test button */}
                          <div className="ticker-edit-row">
                            <input
                              className="ticker-edit-input"
                              value={tickerEdits[r.ticker] ?? r.ticker}
                              placeholder="TICKER"
                              title="Ticker symbol — press Enter to apply"
                              onChange={e => setTickerEdits(prev => ({ ...prev, [r.ticker]: e.target.value.toUpperCase() }))}
                              onBlur={() => commitTickerEdit(r.ticker, r.positions)}
                              onKeyDown={e => { if (e.key === 'Enter') (e.target as HTMLInputElement).blur() }}
                            />
                            <button
                              className={`fetch-test-btn${fetchTests[r.ticker]?.state === 'ok' ? ' fetch-test-ok' : fetchTests[r.ticker]?.state === 'error' ? ' fetch-test-err' : ''}`}
                              onClick={() => runFetchTest(r.ticker)}
                              disabled={fetchTests[r.ticker]?.state === 'loading'}
                              title="Test if this ticker can fetch a live price from Yahoo Finance"
                            >{fetchTests[r.ticker]?.state === 'loading' ? '…' : '▶ Test'}</button>
                          </div>
                          {/* Test result */}
                          {fetchTests[r.ticker] && fetchTests[r.ticker].state !== 'loading' && (
                            <span className={`fetch-test-result ${fetchTests[r.ticker].state === 'ok' ? 'fetch-test-ok' : 'fetch-test-err'}`}>
                              {fetchTests[r.ticker].state === 'ok' ? '✓' : '✗'} {fetchTests[r.ticker].msg}
                            </span>
                          )}
                          {/* Row 2: ISIN + lookup button */}
                          <div className="ticker-edit-row">
                            <input
                              className="isin-edit-input"
                              value={isinEdits[r.ticker] ?? r.positions[0]?.isin ?? ''}
                              placeholder="ISIN (optional)"
                              title="Store the ISIN for reference; use ⟲ to look up ticker from ISIN"
                              onChange={e => setIsinEdits(prev => ({ ...prev, [r.ticker]: e.target.value }))}
                              onBlur={() => commitIsinEdit(r.ticker, r.positions)}
                              onKeyDown={e => { if (e.key === 'Enter') (e.target as HTMLInputElement).blur() }}
                            />
                            <button
                              className="fetch-test-btn"
                              onClick={() => runLookup(r.ticker, r.positions)}
                              disabled={lookingUp[r.ticker]}
                              title="Look up ticker symbol from the ISIN above using Yahoo Finance"
                            >{lookingUp[r.ticker] ? '…' : '⟲'}</button>
                          </div>
                          {/* Row 3: name edit */}
                          <input
                            className="name-edit-input"
                            value={nameEdits[r.ticker] ?? r.name}
                            placeholder={r.name || r.ticker}
                            onChange={e => setNameEdits(prev => ({ ...prev, [r.ticker]: e.target.value }))}
                            onBlur={() => commitNameEdit(r.ticker, r.positions)}
                            onKeyDown={e => { if (e.key === 'Enter') (e.target as HTMLInputElement).blur() }}
                          />
                        </div>
                      ) : (
                        <>
                          <span className="ticker">{r.ticker}</span>
                          {r.isClosed && <span className="badge-sold">SOLD</span>}
                          {r.name && r.name !== r.ticker && <span className="name">{r.name}</span>}
                          {r.positions[0]?.isin && <span className="isin-display">{r.positions[0].isin}</span>}
                        </>
                      )}
                    </td>

                    {/* Dynamic columns */}
                    {activeColumns.map(col => {
                      const cls = COL_CLASS[col.key]
                      switch (col.key) {
                        case 'type':
                          return (
                            <td key={col.key} className={cls}>
                              {editMode ? (
                                <select
                                  className="type-edit-select"
                                  value={r.type}
                                  onChange={e => {
                                    const t = e.target.value as Position['type']
                                    r.positions.forEach(p => onUpdatePosition(p.id, { type: t }))
                                  }}
                                >
                                  <option value="stock">STOCK</option>
                                  <option value="etf">ETF</option>
                                  <option value="fund">FUND</option>
                                  <option value="commodity">COMMODITY</option>
                                </select>
                              ) : (
                                <span className={`badge badge-${r.type}`}>{r.type.toUpperCase()}</span>
                              )}
                            </td>
                          )

                        case 'qty':
                          return <td key={col.key} className={cls}>{fmtQty(r.totalQuantity)}</td>

                        case 'avgBuy':
                          return <td key={col.key} className={cls}>{fmtPrice(cv(r.avgBuyPrice, r.currency), displayCurrency)}</td>

                        case 'firstBuy':
                          return <td key={col.key} className={cls}>{r.firstBuyDate}</td>

                        case 'lots':
                          return (
                            <td key={col.key} className={cls}>
                              {r.lots > 1 ? <span className="lots-badge">{r.lots} lots</span> : <span className="muted">—</span>}
                            </td>
                          )

                        case 'broker':
                          return (
                            <td key={col.key} className={cls}>
                              {brokerDisplay ? <span className="broker-badge">{brokerDisplay}</span> : <span className="muted">—</span>}
                            </td>
                          )

                        case 'curPrice':
                          return (
                            <td key={col.key} className={cls}>
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
                          )

                        case 'today':
                          return (
                            <td key={col.key} className={`${cls}${!r.loading && !noLivePrice && r.dailyChange !== 0 ? (r.dailyChange >= 0 ? ' gain' : ' loss') : ''}`}>
                              {r.loading ? <span className="loading-dot">…</span>
                                : noLivePrice ? <span className="muted">—</span>
                                : (
                                  <>
                                    {dailyVal >= 0 ? '+' : ''}{fmt(dailyVal, displayCurrency)}
                                    <span className="summary-sub">{pct(dailyPct)}</span>
                                  </>
                                )}
                            </td>
                          )

                        case 'costBasis':
                          return <td key={col.key} className={cls}>{fmt(cv(r.costBasis, r.currency), displayCurrency)}</td>

                        case 'curValue':
                          return (
                            <td key={col.key} className={cls}>
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
                          )

                        case 'pricePnl':
                          return (
                            <td key={col.key} className={`${cls} ${r.pnl >= 0 ? 'gain' : 'loss'}`}>
                              {r.loading ? '…' : fmt(cv(r.pnl, r.currency), displayCurrency)}
                            </td>
                          )

                        case 'dividends':
                          return (
                            <td key={col.key} className={`${cls} ${r.dividendIncome > 0 ? 'gain' : 'muted'}`}>
                              {r.dividendIncome > 0 ? fmt(cv(r.dividendIncome, r.currency), displayCurrency) : <span className="muted">—</span>}
                            </td>
                          )

                        case 'totalReturn':
                          return (
                            <td key={col.key} className={`${cls} ${r.totalReturn >= 0 ? 'gain' : 'loss'}`}>
                              {r.loading ? '…' : fmt(cv(r.totalReturn, r.currency), displayCurrency)}
                            </td>
                          )

                        case 'returnPct':
                          return (
                            <td key={col.key} className={`${cls} ${totalReturnPctRow >= 0 ? 'gain' : 'loss'}`}>
                              {r.loading ? '…' : pct(totalReturnPctRow)}
                            </td>
                          )

                        case 'irr':
                          return (
                            <td key={col.key} className={`${cls}${r.irr != null ? (r.irr >= 0 ? ' gain' : ' loss') : ''}`}>
                              {r.loading ? '…' : r.irr != null ? pct(r.irr * 100) : <span className="muted">N/A</span>}
                            </td>
                          )

                        default: return null
                      }
                    })}

                    {/* Fixed: Actions */}
                    <td>
                      <span style={{ display: 'flex', gap: 4, justifyContent: 'flex-end' }}>
                        {!r.isClosed && (
                          <button
                            className="sell-btn" title="Sell / close position"
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
                      <td colSpan={detailColSpan}>
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
                                      <th>#</th><th>Buy Date</th><th>Qty</th><th>Buy Price</th><th>Cost</th>
                                      {hasClosedLots && <th>Sell Date</th>}
                                      {hasClosedLots && <th>Sell Price</th>}
                                      <th>Cur. Value</th><th>P&amp;L</th><th>P&amp;L %</th>
                                      {hasBrokers && <th>Broker</th>}
                                      <th></th>
                                    </tr>
                                  </thead>
                                  <tbody>
                                    {r.positions.map((pos, i) => {
                                      if (editMode && editingLotId === pos.id) {
                                        // ── Inline edit row ──
                                        return (
                                          <tr key={pos.id} className="lot-draft-row">
                                            <td className="muted">{i + 1}</td>
                                            <td>
                                              <input
                                                className="lot-draft-input" type="date"
                                                value={lotDraft.buyDate ?? ''}
                                                onChange={e => setLotDraft(d => ({ ...d, buyDate: e.target.value }))}
                                              />
                                            </td>
                                            <td>
                                              <input
                                                className="lot-draft-input" type="number" step="any" min="0"
                                                value={String(lotDraft.quantity ?? '')}
                                                onChange={e => setLotDraft(d => ({ ...d, quantity: parseFloat(e.target.value) || 0 }))}
                                              />
                                            </td>
                                            <td>
                                              <span style={{ display: 'flex', gap: 3, alignItems: 'center' }}>
                                                <input
                                                  className="lot-draft-input" type="number" step="any" min="0"
                                                  value={String(lotDraft.buyPrice ?? '')}
                                                  onChange={e => setLotDraft(d => ({ ...d, buyPrice: parseFloat(e.target.value) || 0 }))}
                                                />
                                                <select
                                                  className="currency-mini-select"
                                                  value={lotDraft.currency ?? 'CZK'}
                                                  onChange={e => setLotDraft(d => ({ ...d, currency: e.target.value }))}
                                                >
                                                  {['CZK', 'USD', 'EUR', 'GBP', 'CHF'].map(c => <option key={c}>{c}</option>)}
                                                </select>
                                              </span>
                                            </td>
                                            <td className="muted">—</td>
                                            {hasClosedLots && (
                                              <td>
                                                <input
                                                  className="lot-draft-input" type="date"
                                                  value={lotDraft.sellDate ?? ''}
                                                  onChange={e => setLotDraft(d => ({ ...d, sellDate: e.target.value || undefined }))}
                                                />
                                              </td>
                                            )}
                                            {hasClosedLots && (
                                              <td>
                                                <input
                                                  className="lot-draft-input" type="number" step="any" min="0"
                                                  value={String(lotDraft.sellPrice ?? '')}
                                                  onChange={e => setLotDraft(d => ({ ...d, sellPrice: parseFloat(e.target.value) || undefined }))}
                                                />
                                              </td>
                                            )}
                                            <td className="muted">—</td>
                                            <td className="muted">—</td>
                                            <td className="muted">—</td>
                                            {hasBrokers && (
                                              <td>
                                                <input
                                                  className="lot-draft-input" type="text"
                                                  value={lotDraft.broker ?? ''}
                                                  placeholder="broker"
                                                  list="broker-datalist"
                                                  onChange={e => setLotDraft(d => ({ ...d, broker: e.target.value || undefined }))}
                                                />
                                              </td>
                                            )}
                                            <td>
                                              <span style={{ display: 'flex', gap: 4, justifyContent: 'flex-end' }}>
                                                <button className="price-edit-ok" title="Save" onClick={() => commitLotEdit(pos.id)}>✓</button>
                                                <button className="price-edit-cancel" title="Cancel" onClick={cancelLotEdit}>✕</button>
                                              </span>
                                            </td>
                                          </tr>
                                        )
                                      }

                                      // ── Normal display row ──
                                      const isSold = pos.sellPrice != null && pos.sellDate
                                      const effectivePrice = isSold ? pos.sellPrice! : r.currentPrice
                                      // ponytail: r.currentPrice is in r.currency; normalize buyPrice when lot currency differs
                                      const buyInRowCcy = convert(pos.buyPrice, pos.currency, r.currency)
                                      const posValue = isSold ? 0 : cv(effectivePrice * pos.quantity, r.currency)
                                      const posCost  = cv(pos.buyPrice * pos.quantity, pos.currency)
                                      const posPnl   = cv(isSold
                                        ? (pos.sellPrice! - pos.buyPrice) * pos.quantity
                                        : (effectivePrice - buyInRowCcy) * pos.quantity,
                                        isSold ? pos.currency : r.currency)
                                      const posPnlPct = isSold
                                        ? ((pos.sellPrice! - pos.buyPrice) / pos.buyPrice) * 100
                                        : ((effectivePrice - buyInRowCcy) / buyInRowCcy) * 100
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
                                          <td>{isSold ? <span className="badge-sold" style={{ fontSize: 10 }}>SOLD</span> : priceUnknown ? <span className="muted">—</span> : fmt(posValue, displayCurrency)}</td>
                                          <td className={posPnl >= 0 ? 'gain' : 'loss'}>{priceUnknown ? <span className="muted">—</span> : fmt(posPnl, displayCurrency)}</td>
                                          <td className={posPnlPct >= 0 ? 'gain' : 'loss'}>{priceUnknown ? <span className="muted">—</span> : pct(posPnlPct)}</td>
                                          {hasBrokers && <td>{pos.broker ? <span className="broker-badge">{pos.broker}</span> : <span className="muted">—</span>}</td>}
                                          <td>
                                            <span style={{ display: 'flex', gap: 4, justifyContent: 'flex-end' }}>
                                              {editMode && !editingLotId && (
                                                <button className="edit-lot-btn" title="Edit this lot"
                                                  onClick={() => startLotEdit(pos)}
                                                >✎</button>
                                              )}
                                              {!isSold && !editMode && (
                                                <button className="sell-btn sell-btn-sm" title="Sell this lot"
                                                  onClick={() => setPendingSell({ ticker: r.ticker, lots: [{ id: pos.id, quantity: pos.quantity, buyDate: pos.buyDate, buyPrice: pos.buyPrice, currency: pos.currency }] })}
                                                >Sell</button>
                                              )}
                                              <button className="remove-btn" title="Remove this lot"
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

                          {/* Datalist for broker autocomplete in edit mode */}
                          <datalist id="broker-datalist">
                            <option value="XTB" />
                            <option value="Revolut" />
                            <option value="IBKR" />
                            <option value="Fio banka" />
                            <option value="Degiro" />
                            <option value="Trading 212" />
                          </datalist>

                          {/* Dividend events panel */}
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
                                    <tr><th>Ex-date</th><th>Per share</th><th>Shares</th><th>Gross</th><th>Tax %</th><th>Net</th></tr>
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
                                              ticker={r.ticker} date={div.date}
                                              appliedRate={appliedRate} isOverridden={isOverridden}
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
