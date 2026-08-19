// Shared fetch wrapper for /api/yahoo and /api/stooq — both require the same
// X-API-Key header as /api/persist in production (server/index.js requireApiKey).
// Dev's Vite proxy forwards these routes straight to Yahoo/Stooq, bypassing
// Express entirely, so the header is a no-op there but required in Docker/prod.
const API_KEY = import.meta.env.VITE_PERSIST_API_KEY ?? ''

export function proxyFetch(url: string, init: RequestInit = {}): Promise<Response> {
  return fetch(url, { ...init, headers: { ...init.headers, 'X-API-Key': API_KEY } })
}
