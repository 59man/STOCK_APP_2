const STORAGE_KEY = 'stock_tracker_api_key'

/** The production server writes this into the page it serves, to LAN and Tailscale addresses only. */
function servedKey(): string {
  return (globalThis as { __STOCK_TRACKER_API_KEY__?: string }).__STOCK_TRACKER_API_KEY__ ?? ''
}

export function hasServedKey(): boolean {
  return !!servedKey()
}

/**
 * The key this browser sends. The server's wins, so a rotation only needs a reload. An entered key is
 * for a page served without one, and the build's is for `npm run dev`.
 */
export function getApiKey(): string {
  if (servedKey()) return servedKey()
  try {
    const stored = localStorage.getItem(STORAGE_KEY)
    if (stored) return stored
  } catch { /* storage blocked */ }
  return import.meta.env.VITE_PERSIST_API_KEY ?? ''
}

export function saveApiKey(key: string): void {
  localStorage.setItem(STORAGE_KEY, key.trim())
}
