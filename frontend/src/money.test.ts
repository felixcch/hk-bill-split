import { describe, expect, it } from 'vitest'
import { cents, preview } from './money'

describe('split previews', () => {
  it('matches deterministic rounding regardless of selection order', () => {
    expect(preview('100', 'EQUAL', [{ memberId: 'c' }, { memberId: 'a' }, { memberId: 'b' }]))
      .toEqual([{ memberId: 'a', amountMinor: 3334 }, { memberId: 'b', amountMinor: 3333 }, { memberId: 'c', amountMinor: 3333 }])
  })
  it('allocates fractional percentage cents by largest remainder', () => {
    expect(preview('0.05', 'PERCENT', [
      { memberId: 'a', value: '33.33' }, { memberId: 'b', value: '33.33' }, { memberId: 'c', value: '33.34' },
    ]).map(s => s.amountMinor)).toEqual([2, 1, 2])
  })
  it('rejects invalid totals, empty selections and excessive precision', () => {
    expect(() => preview('100', 'EXACT', [{ memberId: 'a', value: '99.99' }])).toThrow()
    expect(() => preview('100', 'PERCENT', [{ memberId: 'a', value: '99.99' }])).toThrow()
    expect(() => preview('100', 'EQUAL', [])).toThrow()
    expect(() => cents('1.001')).toThrow()
    expect(() => cents('1e3')).toThrow()
    expect(() => cents('1000000.01')).toThrow()
    expect(cents('0.29')).toBe(29)
  })
})
