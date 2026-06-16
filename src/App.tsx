import { useState, useRef } from 'react'
import { usePortfolios } from './hooks/usePortfolios'
import { useFxRates, DisplayCurrency } from './hooks/useFxRates'
import { PortfolioContent } from './components/PortfolioContent'
import { ImportModal } from './components/ImportModal'
import { ColumnMappingModal } from './components/ColumnMappingModal'
import { Position } from './types'
import { getItem, setItem } from './utils/storage'
import { randomUUID } from './utils/uuid'
import { parseFile, parseWithMapping, ColumnMapping, MappingDefaults, NeedsMapping } from './utils/importParser'
import './App.css'

const CURRENCIES: DisplayCurrency[] = ['CZK', 'USD', 'EUR']

export default function App() {
  const { portfolios, activeId, ready, addPortfolio, removePortfolio, renamePortfolio, switchPortfolio } = usePortfolios()
  const { convert } = useFxRates()
  const [displayCurrency, setDisplayCurrency] = useState<DisplayCurrency>('CZK')
  const [showAddModal, setShowAddModal] = useState(false)
  const [contentKey, setContentKey] = useState(0)
  const [importData, setImportData] = useState<{
    fileName: string
    positions: Position[]
    taxOverrides?: Record<string, number>
    manualPrices?: Record<string, { price: number; updatedAt: string }>
  } | null>(null)
  const [columnMapData, setColumnMapData] = useState<{ fileName: string; rows: unknown[][] } | null>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)

  // Inline rename state for the portfolio tab bar
  const [editingId, setEditingId] = useState<string | null>(null)
  const [editName, setEditName] = useState('')

  const startRename = (id: string, currentName: string) => {
    setEditingId(id)
    setEditName(currentName)
  }

  const commitRename = () => {
    if (editingId && editName.trim()) {
      renamePortfolio(editingId, editName.trim())
    }
    setEditingId(null)
    setEditName('')
  }

  // ── Import ────────────────────────────────────────────
  const handleFileSelected = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file) return
    e.target.value = ''
    try {
      const result = await parseFile(file)
      if (!result) {
        alert('Could not parse file — unsupported format or corrupted file.')
        return
      }
      // Unknown tabular format → show column mapping wizard
      if ((result as NeedsMapping).type === 'needs-mapping') {
        setColumnMapData({ fileName: file.name, rows: (result as NeedsMapping).rows })
        return
      }
      const parsed = result as Exclude<typeof result, NeedsMapping>
      if (parsed.valid.length === 0) {
        alert('No valid positions found in this file.')
        return
      }
      if (parsed.skipped > 0) {
        console.warn(`[import] ${parsed.skipped} row(s) skipped`)
      }
      setImportData({
        fileName: file.name,
        positions: parsed.valid,
        taxOverrides: parsed.dividendTaxOverrides,
        manualPrices: parsed.manualPrices,
      })
    } catch (err) {
      console.error('[import]', err)
      alert('Could not parse file — unsupported format or corrupted file.')
    }
  }

  const handleColumnMappingConfirm = async (mapping: ColumnMapping, defaults: MappingDefaults) => {
    if (!columnMapData) return
    try {
      const result = await parseWithMapping(columnMapData.rows, mapping, defaults)
      setColumnMapData(null)
      if (!result || result.valid.length === 0) {
        alert('No valid positions found with this mapping.')
        return
      }
      setImportData({ fileName: columnMapData.fileName, positions: result.valid })
    } catch (err) {
      console.error('[import mapping]', err)
      alert('Error applying column mapping.')
    }
  }

  const handleImportConfirm = async (mode: 'new' | 'current', newPortfolioName?: string) => {
    if (!importData) return
    const positions = importData.positions.map((p) => ({ ...p, id: randomUUID() }))

    if (mode === 'new' && newPortfolioName) {
      const id = addPortfolio(newPortfolioName)
      await setItem(`stock_tracker_positions_${id}`, JSON.stringify(positions))
      if (importData.taxOverrides && Object.keys(importData.taxOverrides).length > 0) {
        await setItem(`stock_tracker_div_tax_${id}`, JSON.stringify(importData.taxOverrides))
      }
      if (importData.manualPrices && Object.keys(importData.manualPrices).length > 0) {
        await setItem(`stock_tracker_manual_prices_${id}`, JSON.stringify(importData.manualPrices))
      }
      switchPortfolio(id)
    } else {
      // Append to current portfolio — merge overrides rather than replace
      const posKey = `stock_tracker_positions_${activeId}`
      const existing = await getItem(posKey).catch(() => null)
      let current: Position[] = []
      if (existing) {
        try { current = JSON.parse(existing) } catch {
          console.warn('[import] could not parse existing positions — appending to empty list')
        }
      }
      await setItem(posKey, JSON.stringify([...current, ...positions]))

      if (importData.taxOverrides && Object.keys(importData.taxOverrides).length > 0) {
        const taxKey = `stock_tracker_div_tax_${activeId}`
        const existingTax = await getItem(taxKey).catch(() => null)
        let currentTax: Record<string, number> = {}
        if (existingTax) { try { currentTax = JSON.parse(existingTax) } catch {} }
        await setItem(taxKey, JSON.stringify({ ...currentTax, ...importData.taxOverrides }))
      }

      if (importData.manualPrices && Object.keys(importData.manualPrices).length > 0) {
        const priceKey = `stock_tracker_manual_prices_${activeId}`
        const existingPrices = await getItem(priceKey).catch(() => null)
        let currentPrices: Record<string, { price: number; updatedAt: string }> = {}
        if (existingPrices) { try { currentPrices = JSON.parse(existingPrices) } catch {} }
        await setItem(priceKey, JSON.stringify({ ...currentPrices, ...importData.manualPrices }))
      }

      // Force PortfolioContent to remount so it reads the updated storage
      setContentKey((k) => k + 1)
    }

    setImportData(null)
  }

  const handleAddPortfolio = () => {
    const base = 'New Portfolio'
    const names = new Set(portfolios.map((p) => p.name))
    let name = base
    let n = 2
    while (names.has(name)) { name = `${base} ${n++}` }
    const id = addPortfolio(name)
    switchPortfolio(id)
    // Auto-enter rename mode on the new tab
    setTimeout(() => startRename(id, name), 50)
  }

  const handleDeletePortfolio = (id: string) => {
    if (portfolios.length <= 1) return
    const p = portfolios.find((p) => p.id === id)
    if (p && window.confirm(`Delete portfolio "${p.name}"? All positions in it will be lost.`)) {
      removePortfolio(id)
    }
  }

  return (
    <div className="app">
      <header className="header">
        <div className="header-inner">
          <h1>📈 Stock Tracker</h1>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
            <div className="currency-tabs">
              {CURRENCIES.map((c) => (
                <button
                  key={c}
                  className={`currency-tab${displayCurrency === c ? ' active' : ''}`}
                  onClick={() => setDisplayCurrency(c)}
                >{c}</button>
              ))}
            </div>
            <button className="btn-primary" onClick={() => setShowAddModal(true)}>
              + Add Position
            </button>
          </div>
        </div>

        {/* ── Portfolio tab bar ── */}
        <div className="portfolio-bar">
          {portfolios.map((p) => {
            const isActive = p.id === activeId
            const isEditing = editingId === p.id
            return (
              <div
                key={p.id}
                className={`portfolio-tab${isActive ? ' active' : ''}`}
                onClick={() => { if (!isActive) switchPortfolio(p.id) }}
              >
                {isEditing ? (
                  <input
                    className="portfolio-tab-input"
                    value={editName}
                    autoFocus
                    onChange={(e) => setEditName(e.target.value)}
                    onBlur={commitRename}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter') commitRename()
                      if (e.key === 'Escape') { setEditingId(null); setEditName('') }
                    }}
                    onClick={(e) => e.stopPropagation()}
                  />
                ) : (
                  <span
                    className="portfolio-tab-name"
                    onDoubleClick={(e) => { e.stopPropagation(); startRename(p.id, p.name) }}
                    title="Double-click to rename"
                  >{p.name}</span>
                )}
                {!isEditing && isActive && (
                  <button
                    className="portfolio-tab-rename"
                    title="Rename portfolio"
                    onClick={(e) => { e.stopPropagation(); startRename(p.id, p.name) }}
                  >✎</button>
                )}
                {!isEditing && portfolios.length > 1 && (
                  <button
                    className="portfolio-tab-delete"
                    title="Delete portfolio"
                    onClick={(e) => { e.stopPropagation(); handleDeletePortfolio(p.id) }}
                  >×</button>
                )}
              </div>
            )
          })}
          <button className="portfolio-tab-add" onClick={handleAddPortfolio}>+ New</button>
          <button className="portfolio-tab-add" onClick={() => fileInputRef.current?.click()}>↑ Import</button>
          <input
            ref={fileInputRef}
            type="file"
            accept=".json,.xlsx,.csv,.pdf"
            style={{ display: 'none' }}
            onChange={handleFileSelected}
          />
        </div>
      </header>

      <main className="main">
        {ready && activeId && (
          <PortfolioContent
            key={`${activeId}-${contentKey}`}
            portfolioId={activeId}
            displayCurrency={displayCurrency}
            convert={convert}
            showAddModal={showAddModal}
            onCloseAddModal={() => setShowAddModal(false)}
          />
        )}
        {!ready && (
          <div className="empty-state">
            <p>Loading portfolios…</p>
          </div>
        )}
      </main>

      {importData && (
        <ImportModal
          fileName={importData.fileName}
          positions={importData.positions}
          currentPortfolioName={portfolios.find((p) => p.id === activeId)?.name ?? 'Current'}
          hasTaxOverrides={!!importData.taxOverrides && Object.keys(importData.taxOverrides).length > 0}
          hasManualPrices={!!importData.manualPrices && Object.keys(importData.manualPrices).length > 0}
          onConfirm={handleImportConfirm}
          onClose={() => setImportData(null)}
        />
      )}
      {columnMapData && (
        <ColumnMappingModal
          fileName={columnMapData.fileName}
          rows={columnMapData.rows}
          onConfirm={handleColumnMappingConfirm}
          onClose={() => setColumnMapData(null)}
        />
      )}
    </div>
  )
}
