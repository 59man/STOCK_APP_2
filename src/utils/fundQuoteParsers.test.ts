import { describe, it, expect } from 'vitest'
import { parseOnemarketsCsv, parseFioFundJson } from './fundQuoteParsers'

// Fixtures recorded live from each provider's own undocumented data endpoint
// and cross-checked against the fund's public product page on the same day
// (see plan notes) — not synthetic data.

describe('parseOnemarketsCsv', () => {
  it('parses LU2606422355 (OM BlackRock Global Equity Dyn.)', () => {
    const csv = '25.08.2026;155.59;; 26.08.2026;155.79;; 27.08.2026;156.34;; 28.08.2026;156.04;;'
    expect(parseOnemarketsCsv(csv)).toEqual({ price: 156.04, prevClose: 156.34, date: '2026-08-28' })
  })

  it('parses LU2606421548 (OM Fidelity World Equity Income)', () => {
    const csv = '25.08.2026;142.65;; 26.08.2026;142.66;; 27.08.2026;141.96;; 28.08.2026;142.24;;'
    expect(parseOnemarketsCsv(csv)).toEqual({ price: 142.24, prevClose: 141.96, date: '2026-08-28' })
  })

  it('parses LU2595011649 (OM Pictet Global Opport. Alloc.)', () => {
    const csv = '25.08.2026;134.02;; 26.08.2026;134.13;; 27.08.2026;134.66;; 28.08.2026;134.61;;'
    expect(parseOnemarketsCsv(csv)).toEqual({ price: 134.61, prevClose: 134.66, date: '2026-08-28' })
  })

  it('falls back to prevClose === price when only one data point is returned', () => {
    const csv = '28.08.2026;156.04;;'
    expect(parseOnemarketsCsv(csv)).toEqual({ price: 156.04, prevClose: 156.04, date: '2026-08-28' })
  })

  it('throws on empty/unparseable input', () => {
    expect(() => parseOnemarketsCsv('')).toThrow()
  })
})

describe('parseFioFundJson', () => {
  it('parses the FIO Global Fond CZK NAV history', () => {
    const json = [
      { x: '2026-08-25', value: 2.0284 },
      { x: '2026-08-26', value: 2.0209 },
      { x: '2026-08-27', value: 2.0287 },
      { x: '2026-08-28', value: 2.031 },
      { x: '2026-08-31', value: 2.0101 },
    ]
    expect(parseFioFundJson(json)).toEqual({ price: 2.0101, prevClose: 2.031, date: '2026-08-31' })
  })

  it('falls back to prevClose === price when only one data point is returned', () => {
    expect(parseFioFundJson([{ x: '2026-08-31', value: 2.0101 }]))
      .toEqual({ price: 2.0101, prevClose: 2.0101, date: '2026-08-31' })
  })

  it('throws on empty input', () => {
    expect(() => parseFioFundJson([])).toThrow()
  })
})
