import { useState } from 'react'
import { getApiKey, saveApiKey } from '../utils/apiKey'

interface Props {
  onClose: () => void
  /** The server refused the key this browser has. */
  rejected?: boolean
}

/**
 * navigator.clipboard is only exposed in secure contexts (HTTPS/localhost) — this app is
 * commonly self-hosted over plain HTTP on a LAN/Tailscale IP (see CasaOS deployment in
 * CLAUDE.md), so on a browser like Brave/Chrome for Android that click silently no-ops.
 * Falls back to the legacy execCommand('copy') textarea trick, which still works over HTTP.
 */
async function copyToClipboard(text: string): Promise<boolean> {
  if (navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text)
      return true
    } catch {
      // insecure context or permission denial — fall through to the legacy path below
    }
  }
  const textarea = document.createElement('textarea')
  textarea.value = text
  textarea.style.position = 'fixed'
  textarea.style.opacity = '0'
  document.body.appendChild(textarea)
  textarea.focus()
  textarea.select()
  let ok = false
  try {
    ok = document.execCommand('copy')
  } catch {
    ok = false
  }
  document.body.removeChild(textarea)
  return ok
}

/** Enters this browser's API key, and shows it so it can be copied into the Android app's Settings screen. The server owns the key; see show-key and rotate-key in server/index.js. */
export function ApiKeyModal({ onClose, rejected }: Props) {
  const API_KEY = getApiKey()
  const [draft, setDraft] = useState('')
  const [revealed, setRevealed] = useState(false)
  const [copied, setCopied] = useState(false)
  const [copyFailed, setCopyFailed] = useState(false)

  const handleCopy = async () => {
    if (!API_KEY) return
    const ok = await copyToClipboard(API_KEY)
    if (ok) {
      setCopied(true)
      setCopyFailed(false)
      setTimeout(() => setCopied(false), 1500)
    } else {
      setCopyFailed(true)
    }
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
          {rejected && <p style={{ color: 'var(--loss, #f66)' }}>The server did not accept this browser's API key. Enter the current one.</p>}
          <form
            style={{ display: 'flex', gap: 9 }}
            onSubmit={(e) => {
              e.preventDefault()
              if (!draft.trim()) return
              saveApiKey(draft)
              window.location.reload()
            }}
          >
            <input
              aria-label="API key"
              type="password"
              autoComplete="off"
              placeholder="Paste the server's API key"
              value={draft}
              onChange={(e) => setDraft(e.target.value)}
              style={{ flex: 1 }}
            />
            <button className="btn-primary" type="submit" disabled={!draft.trim()}>Save</button>
          </form>
          <p className="muted" style={{ fontSize: 12.5 }}>
            Get it on the server with <code>docker exec stock-tracker node server/index.js show-key</code>
          </p>
          {API_KEY && (
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
              {copyFailed && (
                <p className="muted" style={{ fontSize: 12.5, color: 'var(--loss, #f66)' }}>
                  Couldn't copy automatically — tap Show, then select and copy the key manually.
                </p>
              )}
            </>
          )}
        </div>
      </div>
    </div>
  )
}
