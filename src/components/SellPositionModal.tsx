import { useState } from 'react'

interface Lot {
  id: string
  quantity: number
  buyDate: string
  buyPrice: number
  currency: string
}

interface Props {
  ticker: string
  lots: Lot[]
  onSell: (ids: string[], sellPrice: number, sellDate: string) => void
  onClose: () => void
}

function fmt(n: number, currency: string) {
  return new Intl.NumberFormat('en-US', {
    style: 'currency', currency, minimumFractionDigits: 2, maximumFractionDigits: 2,
  }).format(n)
}

export function SellPositionModal({ ticker, lots, onSell, onClose }: Props) {
  const today = new Date().toISOString().slice(0, 10)
  const [sellDate, setSellDate] = useState(today)
  const [sellPrice, setSellPrice] = useState('')
  const [error, setError] = useState<string | null>(null)

  const currency = lots[0]?.currency ?? 'USD'
  const totalQty = lots.reduce((s, l) => s + l.quantity, 0)
  const avgBuy = lots.reduce((s, l) => s + l.buyPrice * l.quantity, 0) / totalQty

  const parsedPrice = parseFloat(sellPrice.replace(/\s/g, '').replace(',', '.'))
  const previewPnl = isFinite(parsedPrice) && parsedPrice > 0
    ? (parsedPrice - avgBuy) * totalQty
    : null

  const handleSubmit = () => {
    const raw = sellPrice.replace(/\s/g, '').replace(',', '.')
    const price = parseFloat(raw)
    if (!raw || !isFinite(price) || price <= 0) { setError('Enter a valid positive sell price'); return }
    if (!sellDate) { setError('Enter a sell date'); return }
    onSell(lots.map((l) => l.id), price, sellDate)
    onClose()
  }

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal" style={{ maxWidth: 400 }} onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <h2>Sell — {ticker}</h2>
          <button className="close-btn" onClick={onClose}>✕</button>
        </div>

        {/* Lots being sold */}
        <div className="sell-lots-summary">
          {lots.length === 1 ? (
            <span>{lots[0].quantity} shares &middot; bought {lots[0].buyDate} @ {fmt(lots[0].buyPrice, currency)}</span>
          ) : (
            <>
              <span>{lots.length} open lots &middot; {totalQty} total shares</span>
              <div className="sell-lots-list">
                {lots.map((l, i) => (
                  <div key={l.id} className="sell-lot-row">
                    <span className="muted">#{i + 1}</span>
                    <span>{l.quantity} shares</span>
                    <span className="muted">{l.buyDate}</span>
                    <span>@ {fmt(l.buyPrice, currency)}</span>
                  </div>
                ))}
              </div>
            </>
          )}
        </div>

        <div className="modal-form">
          <div className="form-row">
            <label>
              Sell Date
              <input
                type="date"
                value={sellDate}
                max={today}
                onChange={(e) => { setSellDate(e.target.value); setError(null) }}
              />
            </label>
            <label>
              Sell Price ({currency})
              <input
                type="text"
                placeholder="per share"
                value={sellPrice}
                autoFocus
                onChange={(e) => { setSellPrice(e.target.value); setError(null) }}
                onKeyDown={(e) => { if (e.key === 'Enter') handleSubmit() }}
              />
            </label>
          </div>

          {error && <span style={{ color: 'var(--loss)', fontSize: 13 }}>{error}</span>}

          {previewPnl !== null && (
            <div className="sell-pnl-preview">
              <span className="muted">Estimated P&amp;L</span>
              <strong className={previewPnl >= 0 ? 'gain' : 'loss'}>
                {previewPnl >= 0 ? '+' : ''}{fmt(previewPnl, currency)}
              </strong>
            </div>
          )}
        </div>

        <div className="modal-actions">
          <button className="btn-secondary" onClick={onClose}>Cancel</button>
          <button className="btn-sell" onClick={handleSubmit}>Confirm Sell</button>
        </div>
      </div>
    </div>
  )
}
