const STORAGE_KEY = 'stock_tracker_api_key'

/**
 * The key this browser sends. One entered here wins over the build's, so an image built with no key
 * (the only kind safe to publish) works once each browser has been given it.
 */
export function getApiKey(): string {
  try {
    const stored = localStorage.getItem(STORAGE_KEY)
    if (stored) return stored
  } catch { /* storage blocked */ }
  return import.meta.env.VITE_PERSIST_API_KEY ?? ''
}

export function saveApiKey(key: string): void {
  localStorage.setItem(STORAGE_KEY, key.trim())
}
