import { describe, it, expect } from 'vitest'
import {
  localMidnightEpoch,
  needsIntraday,
  resolveAnchorFromDaily,
  resolveAnchorFromIntraday,
} from './anchorResolution'

const HOUR = 3600
// 2026-09-18 00:00 in Prague = 2026-09-17 22:00 UTC.
const MIDNIGHT = Date.UTC(2026, 8, 17, 22, 0, 0) / 1000

describe('localMidnightEpoch', () => {
  it('uses the offset in force at midnight on the day clocks change', () => {
    // Prague leaves summer time at 03:00 on 25 Oct 2026, so its midnight was still UTC+2.
    const afterChange = new Date(Date.UTC(2026, 9, 25, 12, 0, 0))
    expect(localMidnightEpoch(afterChange, 'Europe/Prague')).toBe(Date.UTC(2026, 9, 24, 22, 0, 0) / 1000)
    // And it enters summer time at 02:00 on 29 Mar 2026, so that midnight was still UTC+1.
    const afterSpring = new Date(Date.UTC(2026, 2, 29, 12, 0, 0))
    expect(localMidnightEpoch(afterSpring, 'Europe/Prague')).toBe(Date.UTC(2026, 2, 28, 23, 0, 0) / 1000)
  })

  it('is the start of the day in the requested zone, not UTC', () => {
    const prague = localMidnightEpoch(new Date(Date.UTC(2026, 8, 18, 10, 0, 0)), 'Europe/Prague')
    expect(prague).toBe(MIDNIGHT)
  })

  it('differs between zones for the same instant', () => {
    const at = new Date(Date.UTC(2026, 8, 18, 10, 0, 0))
    expect(localMidnightEpoch(at, 'Europe/Prague')).not.toBe(localMidnightEpoch(at, 'Asia/Tokyo'))
  })
})

describe('needsIntraday', () => {
  it('is false for a session that starts and ends inside the local day', () => {
    const periods = { regular: { start: MIDNIGHT + 9 * HOUR, end: MIDNIGHT + 17 * HOUR } }
    expect(needsIntraday(periods, MIDNIGHT)).toBe(false)
  })

  it('is true when a session was in progress at local midnight', () => {
    // A New York session for a Tokyo viewer straddles their midnight.
    const periods = { regular: { start: MIDNIGHT - 2 * HOUR, end: MIDNIGHT + 4 * HOUR } }
    expect(needsIntraday(periods, MIDNIGHT)).toBe(true)
  })

  it('sees a session running at midnight even after a newer one has become the latest', () => {
    // Real BTC-USD, Saturday 16:00 in Prague: the window is already Saturday's (UTC), but at
    // Prague's midnight, 23:00 UTC on Friday, Friday's session was still running.
    const fridayOpen = Date.UTC(2026, 9, 30) / 1000
    const satWindow = { regular: { start: Date.UTC(2026, 9, 31) / 1000, end: Date.UTC(2026, 9, 31, 23, 59) / 1000 } }
    const pragueMidnight = Date.UTC(2026, 9, 30, 23) / 1000
    const bars = [{ epoch: Date.UTC(2026, 9, 29) / 1000, close: 1 }, { epoch: fridayOpen, close: 2 }]
    expect(needsIntraday(satWindow, pragueMidnight, bars)).toBe(true)
  })

  it('leaves a stock alone when midnight falls between its sessions', () => {
    const thuOpen = Date.UTC(2026, 9, 29, 13, 30) / 1000
    const friWindow = { regular: { start: Date.UTC(2026, 9, 30, 13, 30) / 1000, end: Date.UTC(2026, 9, 30, 20) / 1000 } }
    expect(needsIntraday(friWindow, Date.UTC(2026, 9, 29, 23) / 1000, [{ epoch: thuOpen, close: 1 }])).toBe(false)
  })

  it('is true when there are no session windows at all, as for 24/7 crypto', () => {
    expect(needsIntraday(null, MIDNIGHT)).toBe(true)
  })
})

describe('resolveAnchorFromDaily', () => {
  const bars = [
    { epoch: MIDNIGHT - 3 * 24 * HOUR, close: 100 },
    { epoch: MIDNIGHT - 2 * 24 * HOUR, close: 110 },
    { epoch: MIDNIGHT - 1 * 24 * HOUR, close: 120 },
    { epoch: MIDNIGHT + 9 * HOUR, close: 130 },
  ]

  it('takes the close of the last session that ended before local midnight', () => {
    expect(resolveAnchorFromDaily(bars, MIDNIGHT, 8 * HOUR)).toBe(120)
  })

  it('ignores today’s own bar', () => {
    expect(resolveAnchorFromDaily(bars, MIDNIGHT, 8 * HOUR)).not.toBe(130)
  })

  it('returns null when every bar is newer than local midnight', () => {
    expect(resolveAnchorFromDaily([{ epoch: MIDNIGHT + HOUR, close: 5 }], MIDNIGHT, 8 * HOUR)).toBeNull()
  })

  it('returns null for an empty series', () => {
    expect(resolveAnchorFromDaily([], MIDNIGHT, 8 * HOUR)).toBeNull()
  })
})

describe('resolveAnchorFromIntraday', () => {
  const bars = [
    { epoch: MIDNIGHT - 30 * 60, close: 90 },
    { epoch: MIDNIGHT - 5 * 60, close: 95 },
    { epoch: MIDNIGHT + 5 * 60, close: 99 },
  ]

  it('takes the last bar at or before local midnight', () => {
    expect(resolveAnchorFromIntraday(bars, MIDNIGHT)).toBe(95)
  })

  it('includes a bar landing exactly on midnight', () => {
    expect(resolveAnchorFromIntraday([{ epoch: MIDNIGHT, close: 42 }], MIDNIGHT)).toBe(42)
  })

  it('returns null when trading only began after midnight', () => {
    expect(resolveAnchorFromIntraday([{ epoch: MIDNIGHT + 60, close: 42 }], MIDNIGHT)).toBeNull()
  })
})
