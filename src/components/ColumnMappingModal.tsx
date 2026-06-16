import { useState } from 'react'
import { ColumnMapping, MappingDefaults, autoDetectMapping } from '../utils/importParser'

interface Props {
  fileName: string
  rows: unknown[][]
  onConfirm: (mapping: ColumnMapping, defaults: MappingDefaults) => void
  onClose: () => void
}

const FIELDS: { key: keyof ColumnMapping; label: string; required: boolean }[] = [
  { key: 'ticker',    label: 'Ticker',     required: true },
  { key: 'date',      label: 'Buy Date',   required: true },
  { key: 'quantity',  label: 'Quantity',   required: true },
  { key: 'buyPrice',  label: 'Buy Price',  required: true },
  { key: 'name',      label: 'Name',       required: false },
  { key: 'isin',      label: 'ISIN',       required: false },
  { key: 'currency',  label: 'Currency',   required: false },
  { key: 'broker',    label: 'Broker',     required: false },
  { key: 'sellDate',  label: 'Sell Date',  required: false },
  { key: 'sellPrice', label: 'Sell Price', required: false },
]

export function ColumnMappingModal({ fileName, rows, onConfirm, onClose }: Props) {
  const header = (rows[0] ?? []) as string[]
  const colLabels = header.map((h, i) => `${String.fromCharCode(65 + i)}: ${h || `Col ${i+1}`}`)

  const [mapping, setMapping] = useState<ColumnMapping>(() => autoDetectMapping(header))
  const [skipRows, setSkipRows] = useState(1)
  const [defaultCurrency, setDefaultCurrency] = useState('CZK')
  const [defaultBroker, setDefaultBroker] = useState('')

  const set = (key: keyof ColumnMapping, val: string) =>
    setMapping(m => ({ ...m, [key]: val === '' ? null : parseInt(val) }))

  const previewRows = rows.slice(skipRows, skipRows + 3)

  const canImport = mapping.ticker !== null && mapping.date !== null &&
    mapping.quantity !== null && mapping.buyPrice !== null

  const dataCount = Math.max(0, rows.length - skipRows)

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal" style={{ maxWidth: 600 }} onClick={e => e.stopPropagation()}>
        <div className="modal-header">
          <h2>Map Columns</h2>
          <button className="close-btn" onClick={onClose}>✕</button>
        </div>

        <div style={{ fontSize: 12, color: 'var(--muted)', marginBottom: 12 }}>
          {fileName} · {rows.length} rows · {header.length} columns
        </div>

        {/* Preview */}
        <div style={{ overflowX: 'auto', marginBottom: 16, fontSize: 11 }}>
          <table style={{ borderCollapse: 'collapse', width: '100%' }}>
            <thead>
              <tr>
                {header.map((h, i) => (
                  <th key={i} style={{
                    padding: '4px 8px', background: 'var(--surface2)',
                    border: '1px solid var(--border)', textAlign: 'left',
                    color: 'var(--muted)', whiteSpace: 'nowrap',
                  }}>
                    {String.fromCharCode(65 + i)}: {h || `?`}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {previewRows.map((row, ri) => (
                <tr key={ri}>
                  {header.map((_, ci) => (
                    <td key={ci} style={{
                      padding: '3px 8px', border: '1px solid var(--border)',
                      whiteSpace: 'nowrap', maxWidth: 120, overflow: 'hidden',
                      textOverflow: 'ellipsis',
                    }}>
                      {String((row as unknown[])[ci] ?? '')}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        {/* Skip rows */}
        <div style={{ display: 'flex', gap: 12, alignItems: 'center', marginBottom: 12, fontSize: 13 }}>
          <label style={{ color: 'var(--muted)', textTransform: 'none', letterSpacing: 0, fontWeight: 400 }}>
            Skip header rows:
          </label>
          <input
            type="number" min={0} max={10} value={skipRows}
            onChange={e => setSkipRows(parseInt(e.target.value) || 0)}
            style={{ width: 56, textAlign: 'center' }}
          />
          <span style={{ color: 'var(--muted)', fontSize: 12 }}>{dataCount} data rows</span>
        </div>

        {/* Column mapping grid */}
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '6px 16px', marginBottom: 16 }}>
          {FIELDS.map(f => (
            <label key={f.key} style={{
              display: 'flex', flexDirection: 'column', gap: 3,
              textTransform: 'none', letterSpacing: 0, fontWeight: 400, fontSize: 12,
            }}>
              <span style={{ color: f.required ? 'var(--text)' : 'var(--muted)' }}>
                {f.label}{f.required ? ' *' : ''}
              </span>
              <select
                value={mapping[f.key] ?? ''}
                onChange={e => set(f.key, e.target.value)}
                style={{ fontSize: 12, padding: '3px 6px' }}
              >
                <option value="">— skip —</option>
                {colLabels.map((lbl, i) => <option key={i} value={i}>{lbl}</option>)}
              </select>
            </label>
          ))}
        </div>

        {/* Defaults */}
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '6px 16px', marginBottom: 16 }}>
          <label style={{ display: 'flex', flexDirection: 'column', gap: 3, textTransform: 'none', letterSpacing: 0, fontWeight: 400, fontSize: 12 }}>
            <span style={{ color: 'var(--muted)' }}>Default currency (if no column)</span>
            <input value={defaultCurrency} onChange={e => setDefaultCurrency(e.target.value.toUpperCase())}
              style={{ fontSize: 12 }} placeholder="CZK" />
          </label>
          <label style={{ display: 'flex', flexDirection: 'column', gap: 3, textTransform: 'none', letterSpacing: 0, fontWeight: 400, fontSize: 12 }}>
            <span style={{ color: 'var(--muted)' }}>Default broker (if no column)</span>
            <input value={defaultBroker} onChange={e => setDefaultBroker(e.target.value)}
              style={{ fontSize: 12 }} placeholder="Optional" />
          </label>
        </div>

        {!canImport && (
          <p style={{ color: 'var(--loss)', fontSize: 12, marginBottom: 8 }}>
            Ticker, Buy Date, Quantity and Buy Price are required.
          </p>
        )}

        <div className="modal-actions">
          <button className="btn-secondary" onClick={onClose}>Cancel</button>
          <button
            className="btn-primary"
            disabled={!canImport}
            onClick={() => onConfirm(mapping, { currency: defaultCurrency, broker: defaultBroker, skipRows })}
          >
            Import {dataCount} rows
          </button>
        </div>
      </div>
    </div>
  )
}
