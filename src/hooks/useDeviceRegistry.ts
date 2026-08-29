import { useEffect } from 'react'
import { randomUUID } from '../utils/uuid'
import { guessDeviceLabel } from '../utils/deviceLabel'
import { heartbeat } from '../utils/deviceApi'

const DEVICE_ID_KEY = 'stock_tracker_device_id'
const HEARTBEAT_INTERVAL_MS = 5 * 60_000

function getOrCreateDeviceId(): string {
  const existing = localStorage.getItem(DEVICE_ID_KEY)
  if (existing) return existing
  const id = randomUUID()
  localStorage.setItem(DEVICE_ID_KEY, id)
  return id
}

/** Registers this browser with the server's device registry on mount, then keeps `lastSeen` fresh every 5 min while open. */
export function useDeviceRegistry(): void {
  useEffect(() => {
    const id = getOrCreateDeviceId()
    const label = guessDeviceLabel(navigator.userAgent)

    heartbeat(id, label)
    const interval = setInterval(() => heartbeat(id, label), HEARTBEAT_INTERVAL_MS)
    return () => clearInterval(interval)
  }, [])
}
