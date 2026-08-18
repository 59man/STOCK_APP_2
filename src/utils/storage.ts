const BASE = '/api/persist'
const API_KEY = import.meta.env.VITE_PERSIST_API_KEY ?? ''

export async function getItem(key: string): Promise<string | null> {
  try {
    const res = await fetch(`${BASE}/${encodeURIComponent(key)}`, {
      headers: { 'X-API-Key': API_KEY },
    })
    if (!res.ok) return localStorage.getItem(key)
    const { value } = await res.json() as { value: string | null }
    return value
  } catch {
    return localStorage.getItem(key)
  }
}

export async function setItem(key: string, value: string): Promise<void> {
  localStorage.setItem(key, value)
  try {
    const res = await fetch(`${BASE}/${encodeURIComponent(key)}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-API-Key': API_KEY },
      body: JSON.stringify({ value }),
    })
    if (!res.ok) console.warn(`[storage] setItem failed for "${key}": HTTP ${res.status}`)
  } catch {
    // server unavailable — localStorage already updated
  }
}
