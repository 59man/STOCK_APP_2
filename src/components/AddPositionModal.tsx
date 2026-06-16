import { useState, FormEvent } from 'react'
import { Position } from '../types'

interface Props {
  onAdd: (p: Omit<Position, 'id'>) => void
  onClose: () => void
}

const CURRENCIES = ['USD', 'EUR', 'GBP', 'JPY', 'CZK', 'CHF', 'CAD', 'AUD']

export function AddPositionModal({ onAdd, onClose }: Props) {
  const [form, setForm] = useState({
    ticker: '',
    name: '',
    type: 'stock' as Position['type'],
    quantity: '',
    buyPrice: '',
    buyDate: new Date().toISOString().slice(0, 10),
    currency: 'USD',
    broker: '',
    isClosed: false,
    sellPrice: '',
    sellDate: new Date().toISOString().slice(0, 10),
  })
  const [nameLoading, setNameLoading] = useState(false)

  const set = (key: string, value: string | boolean) =>
    setForm((f) => ({ ...f, [key]: value }))

  const handleTickerBlur = async () => {
    const ticker = form.ticker.trim()
    if (!ticker || form.name) return
    setNameLoading(true)
    try {
      const res = await fetch(
        `/api/yahoo/v1/finance/search?q=${encodeURIComponent(ticker)}&quotesCount=1&newsCount=0`
      )
      if (res.ok) {
        const data = await res.json()
        const hit = data?.quotes?.[0]
        if (hit) {
          if (hit.symbol) set('ticker', hit.symbol as string)
          const fetchedName = hit.longname || hit.shortname || ''
          if (fetchedName) set('name', fetchedName)
        }
      }
    } catch {
      // silently ignore — user can type name manually
    } finally {
      setNameLoading(false)
    }
  }

  const handleSubmit = (e: FormEvent) => {
    e.preventDefault()
    const base = {
      ticker: form.ticker.toUpperCase().trim(),
      name: form.name.trim() || form.ticker.toUpperCase().trim(),
      type: form.type,
      quantity: parseFloat(form.quantity),
      buyPrice: parseFloat(form.buyPrice),
      buyDate: form.buyDate,
      currency: form.currency,
      ...(form.broker.trim() ? { broker: form.broker.trim() } : {}),
    }
    onAdd(
      form.isClosed
        ? { ...base, sellPrice: parseFloat(form.sellPrice), sellDate: form.sellDate }
        : base
    )
    onClose()
  }

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <h2>Add Position</h2>
          <button className="close-btn" onClick={onClose}>✕</button>
        </div>
        <form onSubmit={handleSubmit} className="modal-form">
          <div className="form-row">
            <label>
              Ticker / ISIN *
              <input
                required
                placeholder="e.g. AAPL, SPY, US0378331005"
                value={form.ticker}
                onChange={(e) => set('ticker', e.target.value)}
                onBlur={handleTickerBlur}
              />
            </label>
            <label>
              Type
              <select value={form.type} onChange={(e) => set('type', e.target.value)}>
                <option value="stock">Stock</option>
                <option value="etf">ETF</option>
                <option value="fund">Fund</option>
                <option value="commodity">Commodity</option>
              </select>
            </label>
          </div>
          <label>
            Name {nameLoading ? <span className="muted" style={{ fontSize: 11 }}>(looking up…)</span> : '(optional)'}
            <input
              placeholder="Company / fund name"
              value={form.name}
              onChange={(e) => set('name', e.target.value)}
            />
          </label>
          <div className="form-row">
            <label>
              Quantity *
              <input
                required
                type="number"
                min="0.000001"
                step="any"
                placeholder="0"
                value={form.quantity}
                onChange={(e) => set('quantity', e.target.value)}
              />
            </label>
            <label>
              Buy Price *
              <input
                required
                type="number"
                min="0"
                step="any"
                placeholder="0.00"
                value={form.buyPrice}
                onChange={(e) => set('buyPrice', e.target.value)}
              />
            </label>
          </div>
          <div className="form-row">
            <label>
              Buy Date *
              <input
                required
                type="date"
                value={form.buyDate}
                onChange={(e) => set('buyDate', e.target.value)}
              />
            </label>
            <label>
              Currency
              <select value={form.currency} onChange={(e) => set('currency', e.target.value)}>
                {CURRENCIES.map((c) => (
                  <option key={c} value={c}>{c}</option>
                ))}
              </select>
            </label>
          </div>

          <label>
            Broker / Platform <span className="muted" style={{ fontSize: 11 }}>(optional)</span>
            <input
              list="broker-list"
              placeholder="e.g. XTB, Revolut, IBKR"
              value={form.broker}
              onChange={(e) => set('broker', e.target.value)}
            />
            <datalist id="broker-list">
              <option value="XTB" />
              <option value="Revolut" />
              <option value="Interactive Brokers" />
              <option value="Fio banka" />
              <option value="Degiro" />
              <option value="Trading 212" />
            </datalist>
          </label>

          <label className="closed-toggle">
            <input
              type="checkbox"
              checked={form.isClosed}
              onChange={(e) => set('isClosed', e.target.checked)}
            />
            Closed position (already sold)
          </label>

          {form.isClosed && (
            <div className="form-row closed-fields">
              <label>
                Sell Date *
                <input
                  required
                  type="date"
                  value={form.sellDate}
                  onChange={(e) => set('sellDate', e.target.value)}
                />
              </label>
              <label>
                Sell Price *
                <input
                  required
                  type="number"
                  min="0"
                  step="any"
                  placeholder="0.00"
                  value={form.sellPrice}
                  onChange={(e) => set('sellPrice', e.target.value)}
                />
              </label>
            </div>
          )}

          <div className="modal-actions">
            <button type="button" className="btn-secondary" onClick={onClose}>
              Cancel
            </button>
            <button type="submit" className="btn-primary">
              Add Position
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}
