import { describe, expect, it } from 'vitest'
import { cents, evaluate, preview } from './money'

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

describe('amount calculator', () => {
  it('evaluates sums with precedence and rounds to cents', () => {
    expect(evaluate('120+80')).toBe('200')
    expect(evaluate('100 + 20 * 2')).toBe('140')
    expect(evaluate('300/4')).toBe('75')
    expect(evaluate('10/3')).toBe('3.33')
    expect(evaluate('50×2')).toBe('100')
    expect(evaluate('99.5+0.25')).toBe('99.75')
  })
  it('passes through plain amounts and rejects unsupported input', () => {
    expect(evaluate('88.80')).toBe('88.80')
    expect(evaluate(' 12 ')).toBe('12')
    expect(evaluate('12+')).toBe('12+')
    expect(evaluate('10-20')).toBe('10-20')
    expect(evaluate('abc')).toBe('abc')
    expect(evaluate('1/0')).toBe('1/0')
  })
})
