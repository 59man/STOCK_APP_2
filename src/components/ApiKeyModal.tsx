import { useState } from 'react'

interface Props {
  onClose: () => void
}

const API_KEY = import.meta.env.VITE_PERSIST_API_KEY ?? ''

/** Shows the API key baked into this build so it can be copied into the Android app's Settings screen — never a "generator" that mutates .env, since that value is read once at server startup / baked in at build time, not something a running instance can rotate live. */
export function ApiKeyModal({ onClose }: Props) {
  const [revealed, setRevealed] = useState(false)
  const [copied, setCopied] = useState(false)

  const handleCopy = async () => {
    if (!API_KEY) return
    await navigator.clipboard.writeText(API_KEY)
    setCopied(true)
    setTimeout(() => setCopied(false), 1500)
  }

  const masked = '•'.repeat(Math.min(API_KEY.length, 40))

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal" style={{ maxWidth: 480 }} onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <h2>🔑 Persist API Key</h2>
          <button className="close-btn" onClick={onClose}>✕</button>
        </div>
        <div className="modal-form">
          {!API_KEY ? (
            <>
              <p>No key is configured for this build.</p>
              <p className="muted" style={{ fontSize: 12.5 }}>
                Create <code>.env</code> from <code>.env.example</code> with a value for both{' '}
                <code>PERSIST_API_KEY</code> and <code>VITE_PERSIST_API_KEY</code>, then restart{' '}
                <code>npm run dev</code> (or rebuild for production).
              </p>
            </>
          ) : (
            <>
              <p className="muted" style={{ fontSize: 12.5 }}>
                Paste this into the Android app's <strong>Settings → API Key</strong> field to sync with this server.
              </p>
              <div className="api-key-box">
                <code>{revealed ? API_KEY : masked}</code>
              </div>
              <div style={{ display: 'flex', gap: 9 }}>
                <button className="btn-secondary" onClick={() => setRevealed((r) => !r)}>
                  {revealed ? 'Hide' : 'Show'}
                </button>
                <button className="btn-primary" onClick={handleCopy}>
                  {copied ? '✓ Copied' : 'Copy'}
                </button>
              </div>
              <p className="muted" style={{ fontSize: 11.5 }}>
                This is the key baked into this build at build time (same value as <code>PERSIST_API_KEY</code> in <code>.env</code>). To change it, edit <code>.env</code> and rebuild — a running instance can't rotate it live.
              </p>
            </>
          )}
        </div>
      </div>
    </div>
  )
}
