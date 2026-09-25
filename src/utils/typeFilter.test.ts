import { describe, expect, it } from 'vitest'
import cases from '../../test-fixtures/type-filter/cases.json'
import {
  effectiveTypeFilter, filterPositionsByType, parseStoredTypes, toggleType, typeFilterLabel, type AssetType,
} from './typeFilter'
import type { Position } from '../types'

describe('type filter (shared cases)', () => {
  it.each(cases.effective)('effective %j', ({ selected, held, expected }) => {
    expect([...effectiveTypeFilter(selected as AssetType[], held as AssetType[])]).toEqual(expected)
  })
  it.each(cases.toggle)('toggle %j', ({ selected, type, expected }) => {
    expect(toggleType(selected as AssetType[], type as AssetType)).toEqual(expected)
  })
  it.each(cases.label)('label %j', ({ filter, expected }) => {
    expect(typeFilterLabel(new Set(filter as AssetType[]))).toBe(expected)
  })
})

describe('type filter (web only)', () => {
  const p = (type: AssetType) => ({ id: type, ticker: type, type } as Position)
  it('an empty filter keeps every position', () => {
    expect(filterPositionsByType([p('stock'), p('etf')], new Set())).toHaveLength(2)
  })
  it('keeps only selected types', () => {
    expect(filterPositionsByType([p('stock'), p('etf')], new Set<AssetType>(['etf'])).map((x) => x.type)).toEqual(['etf'])
  })
  it('parses stored values defensively', () => {
    expect(parseStoredTypes(null)).toEqual([])
    expect(parseStoredTypes('not json')).toEqual([])
    expect(parseStoredTypes('["etf","bogus",3]')).toEqual(['etf'])
  })
})
