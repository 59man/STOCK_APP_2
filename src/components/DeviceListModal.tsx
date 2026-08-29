import { useEffect, useState } from 'react'
import { DeviceEntry, listDevices, renameDevice, removeDevice } from '../utils/deviceApi'
import { formatRelativeTime } from '../utils/deviceLabel'

interface Props {
  onClose: () => void
}

const PLATFORM_ICON: Record<DeviceEntry['platform'], string> = { web: '🖥️', android: '📱' }

export function DeviceListModal({ onClose }: Props) {
  const [devices, setDevices] = useState<DeviceEntry[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [editingId, setEditingId] = useState<string | null>(null)
  const [editLabel, setEditLabel] = useState('')
  const [pendingRemove, setPendingRemove] = useState<DeviceEntry | null>(null)

  const load = () => {
    listDevices()
      .then((d) => { setDevices(d); setError(null) })
      .catch(() => setError('Could not load devices — is the persist server reachable?'))
  }

  useEffect(load, [])

  const startEdit = (d: DeviceEntry) => { setEditingId(d.id); setEditLabel(d.label) }

  const commitEdit = async () => {
    if (!editingId) return
    const id = editingId
    const label = editLabel.trim()
    setEditingId(null)
    if (!label) return
    try {
      await renameDevice(id, label)
      load()
    } catch {
      setError('Rename failed — is the persist server reachable?')
    }
  }

  const confirmRemove = async () => {
    if (!pendingRemove) return
    const id = pendingRemove.id
    setPendingRemove(null)
    try {
      await removeDevice(id)
      load()
    } catch {
      setError('Remove failed — is the persist server reachable?')
    }
  }

  return (
    <>
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal" style={{ maxWidth: 520 }} onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <h2>📶 Devices</h2>
          <button className="close-btn" onClick={onClose}>✕</button>
        </div>
        <div className="modal-form">
          <p className="muted" style={{ fontSize: 12.5 }}>
            Devices that have synced with this server. "Last synced" is based on the last successful
            sync — there's no live connection to show.
          </p>
          {error && <p className="muted" style={{ fontSize: 12.5, color: 'var(--loss, #f66)' }}>{error}</p>}
          {devices === null && !error && <p className="muted">Loading…</p>}
          {devices !== null && devices.length === 0 && <p className="muted">No devices have synced yet.</p>}
          {devices !== null && devices.length > 0 && (
            <div className="device-list">
              {devices.map((d) => (
                <div key={d.id} className="device-row">
                  <span className="device-platform-icon" title={d.platform}>{PLATFORM_ICON[d.platform]}</span>
                  <div className="device-info">
                    {editingId === d.id ? (
                      <input
                        className="device-label-input"
                        value={editLabel}
                        autoFocus
                        onChange={(e) => setEditLabel(e.target.value)}
                        onBlur={commitEdit}
                        onKeyDown={(e) => { if (e.key === 'Enter') commitEdit(); if (e.key === 'Escape') setEditingId(null) }}
                      />
                    ) : (
                      <span className="device-label" onClick={() => startEdit(d)} title="Click to rename">{d.label}</span>
                    )}
                    <span className="device-meta">
                      First seen {new Date(d.firstSeen).toLocaleDateString()} · Last synced {formatRelativeTime(d.lastSeen)}
                    </span>
                  </div>
                  <button className="device-remove-btn" title="Remove device" onClick={() => setPendingRemove(d)}>×</button>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>

    {pendingRemove && (
        <div className="modal-overlay" onClick={() => setPendingRemove(null)}>
          <div className="modal" style={{ maxWidth: 420 }} onClick={(e) => e.stopPropagation()}>
            <div className="modal-header">
              <h2>Remove device?</h2>
              <button className="close-btn" onClick={() => setPendingRemove(null)}>✕</button>
            </div>
            <p style={{ padding: '0 4px 20px', color: '#aaa', fontSize: 14 }}>
              Remove <strong style={{ color: '#e2e8f0' }}>{pendingRemove.label}</strong> from the device
              list? It reappears if that device syncs again.
            </p>
            <div className="modal-actions">
              <button className="btn-secondary" onClick={() => setPendingRemove(null)}>Cancel</button>
              <button className="btn-danger" onClick={confirmRemove}>Remove</button>
            </div>
          </div>
        </div>
      )}
    </>
  )
}
