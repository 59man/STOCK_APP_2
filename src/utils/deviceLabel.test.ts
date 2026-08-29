import { describe, it, expect } from 'vitest'
import { guessDeviceLabel, formatRelativeTime } from './deviceLabel'

describe('guessDeviceLabel', () => {
  it('detects Chrome on Linux', () => {
    const ua = 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36'
    expect(guessDeviceLabel(ua)).toBe('Chrome on Linux')
  })

  it('detects Safari on macOS', () => {
    const ua = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15'
    expect(guessDeviceLabel(ua)).toBe('Safari on macOS')
  })

  it('detects Firefox on Windows', () => {
    const ua = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:126.0) Gecko/20100101 Firefox/126.0'
    expect(guessDeviceLabel(ua)).toBe('Firefox on Windows')
  })

  it('prefers Edge over the underlying Chrome token', () => {
    const ua = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 Edg/124.0.0.0'
    expect(guessDeviceLabel(ua)).toBe('Edge on Windows')
  })

  it('falls back to "Web browser" for an unrecognized UA', () => {
    expect(guessDeviceLabel('SomeWeirdBot/1.0')).toBe('Web browser')
  })

  it('falls back to "Web browser" for an empty UA', () => {
    expect(guessDeviceLabel('')).toBe('Web browser')
  })
})

describe('formatRelativeTime', () => {
  const now = new Date('2026-08-29T12:00:00.000Z').getTime()

  it('returns "just now" for under a minute', () => {
    expect(formatRelativeTime('2026-08-29T11:59:30.000Z', now)).toBe('just now')
  })

  it('returns minutes for under an hour', () => {
    expect(formatRelativeTime('2026-08-29T11:55:00.000Z', now)).toBe('5 min ago')
  })

  it('returns hours for under a day', () => {
    expect(formatRelativeTime('2026-08-29T09:00:00.000Z', now)).toBe('3 hr ago')
  })

  it('returns days for under 30 days', () => {
    expect(formatRelativeTime('2026-08-27T12:00:00.000Z', now)).toBe('2 days ago')
  })

  it('uses singular "day" for exactly one day', () => {
    expect(formatRelativeTime('2026-08-28T12:00:00.000Z', now)).toBe('1 day ago')
  })

  it('falls back to a plain date past 30 days', () => {
    const iso = '2026-01-01T12:00:00.000Z'
    expect(formatRelativeTime(iso, now)).toBe(new Date(iso).toLocaleDateString())
  })

  it('treats a slightly-future timestamp (clock skew) as "just now"', () => {
    expect(formatRelativeTime('2026-08-29T12:00:05.000Z', now)).toBe('just now')
  })
})
