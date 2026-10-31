import { getApiKey } from './apiKey'

const BASE = '/api/devices'

export interface DeviceEntry {
  id: string
  label: string
  platform: 'web' | 'android'
  firstSeen: string
  lastSeen: string
}

const authHeaders = () => ({ 'Content-Type': 'application/json', 'X-API-Key': getApiKey() })

export async function heartbeat(id: string, label: string): Promise<void> {
  try {
    await fetch(`${BASE}/heartbeat`, {
      method: 'POST',
      headers: authHeaders(),
      body: JSON.stringify({ id, label, platform: 'web' }),
    })
  } catch {
    // best-effort — a failed heartbeat isn't worth surfacing to the user
  }
}

export async function listDevices(): Promise<DeviceEntry[]> {
  const res = await fetch(BASE, { headers: authHeaders() })
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  const { devices } = await res.json() as { devices: DeviceEntry[] }
  return devices
}

export async function renameDevice(id: string, label: string): Promise<void> {
  const res = await fetch(`${BASE}/${encodeURIComponent(id)}`, {
    method: 'PATCH',
    headers: authHeaders(),
    body: JSON.stringify({ label }),
  })
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
}

export async function removeDevice(id: string): Promise<void> {
  const res = await fetch(`${BASE}/${encodeURIComponent(id)}`, { method: 'DELETE', headers: authHeaders() })
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
}
