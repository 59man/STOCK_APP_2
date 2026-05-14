const BASE = '/api/persist'

export async function getItem(key: string): Promise<string | null> {
  try {
    const res = await fetch(`${BASE}/${encodeURIComponent(key)}`)
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
    await fetch(`${BASE}/${encodeURIComponent(key)}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ value }),
    })
  } catch {
    // server unavailable — localStorage already updated
  }
}
