import { useState } from 'react'
import { Position } from '../types'

interface Props {
  fileName: string
  positions: Position[]
  currentPortfolioName: string
  hasTaxOverrides: boolean
  hasManualPrices: boolean
  currencyUncertain?: boolean
  onConfirm: (mode: 'new' | 'current', newPortfolioName?: string, currencyOverride?: string) => void
  onClose: () => void
}

export function ImportModal({ fileName, positions, currentPortfolioName, hasTaxOverrides, hasManualPrices, currencyUncertain, onConfirm, onClose }: Props) {
  const baseName = fileName.replace(/\.(json|xlsx|pdf)$/i, '')
  const [mode, setMode] = useState<'new' | 'current'>('new')
  const [newName, setNewName] = useState(baseName)
  const [currency, setCurrency] = useState('CZK')

  const tickers = [...new Set(positions.map((p) => p.ticker))].slice(0, 8)
  const openCount = positions.filter((p) => !p.sellPrice || !p.sellDate).length
  const closedCount = positions.length - openCount

  const handleConfirm = () => {
    if (mode === 'new' && !newName.trim()) return
    onConfirm(mode, mode === 'new' ? newName.trim() : undefined, currencyUncertain ? currency : undefined)
  }

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal" style={{ maxWidth: 460 }} onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <h2>Import Positions</h2>
          <button className="close-btn" onClick={onClose}>✕</button>
        </div>

        {/* File summary */}
        <div className="import-summary">
          <div className="import-summary-row">
            <span className="muted">File</span>
            <span>{fileName}</span>
          </div>
          <div className="import-summary-row">
            <span className="muted">Positions</span>
            <span>
              <strong>{positions.length}</strong> total
              {openCount > 0 && <span className="gain"> · {openCount} open</span>}
              {closedCount > 0 && <span className="muted"> · {closedCount} closed</span>}
            </span>
          </div>
          <div className="import-summary-row">
            <span className="muted">Tickers</span>
            <span style={{ fontSize: 12 }}>
              {tickers.join(', ')}{positions.length > tickers.length ? ' …' : ''}
            </span>
          </div>
          {hasTaxOverrides && (
            <div className="import-summary-row">
              <span className="muted">Tax overrides</span>
              <span className="gain">✓ included</span>
            </div>
          )}
          {hasManualPrices && (
            <div className="import-summary-row">
              <span className="muted">Manual prices</span>
              <span className="gain">✓ included</span>
            </div>
          )}
          {currencyUncertain && (
            <div className="import-summary-row">
              <span className="muted">Account currency</span>
              <span>
                <select
                  value={currency}
                  onChange={(e) => setCurrency(e.target.value)}
                  title="The statement doesn't state its account currency (filename has no EUR_/CZK_ prefix) — pick the currency the amounts are in"
                >
                  {['CZK', 'EUR', 'USD', 'GBP', 'CHF', 'JPY', 'CAD', 'AUD'].map((c) => (
                    <option key={c} value={c}>{c}</option>
                  ))}
                </select>
              </span>
            </div>
          )}
        </div>

        {/* Import target */}
        <div className="modal-form" style={{ marginTop: 16 }}>
          <label style={{ flexDirection: 'row', alignItems: 'center', gap: 10, cursor: 'pointer', textTransform: 'none', letterSpacing: 0, fontWeight: 400 }}>
            <input
              type="radio"
              name="import-mode"
              checked={mode === 'new'}
              onChange={() => setMode('new')}
              style={{ width: 'auto', margin: 0 }}
            />
            <span style={{ color: 'var(--text)', fontSize: 13 }}>Create new portfolio</span>
          </label>

          {mode === 'new' && (
            <input
              type="text"
              value={newName}
              autoFocus
              placeholder="Portfolio name"
              onChange={(e) => setNewName(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter') handleConfirm() }}
              style={{ marginTop: 4 }}
            />
          )}

          <label style={{ flexDirection: 'row', alignItems: 'center', gap: 10, cursor: 'pointer', textTransform: 'none', letterSpacing: 0, fontWeight: 400 }}>
            <input
              type="radio"
              name="import-mode"
              checked={mode === 'current'}
              onChange={() => setMode('current')}
              style={{ width: 'auto', margin: 0 }}
            />
            <span style={{ color: 'var(--text)', fontSize: 13 }}>
              Add to current portfolio: <strong>{currentPortfolioName}</strong>
            </span>
          </label>

          {mode === 'current' && (
            <p style={{ fontSize: 12, color: 'var(--muted)', marginTop: 4, paddingLeft: 26 }}>
              Imported positions will be appended. Tax overrides and manual prices are merged — imported values override existing ones for the same key.
            </p>
          )}
        </div>

        <div className="modal-actions">
          <button className="btn-secondary" onClick={onClose}>Cancel</button>
          <button
            className="btn-primary"
            onClick={handleConfirm}
            disabled={mode === 'new' && !newName.trim()}
          >
            Import {positions.length} positions
          </button>
        </div>
      </div>
    </div>
  )
}
