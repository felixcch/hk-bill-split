import type { Participant, Share, SplitMethod } from './types'

export const currency = (minor: number) => new Intl.NumberFormat('zh-HK', {
  style: 'currency', currency: 'HKD', currencyDisplay: 'narrowSymbol',
}).format(minor / 100)

export const decimal = (minor: number) => (minor / 100).toFixed(2)

export function cents(input: string): number {
  if (!/^\d{1,9}(\.\d{1,2})?$/.test(input)) throw new Error('請輸入最多兩位小數嘅金額。')
  const [whole, fraction = ''] = input.split('.')
  const value = Number(whole) * 100 + Number(fraction.padEnd(2, '0'))
  if (value > 100_000_000) throw new Error('每筆金額上限係 HK$1,000,000。')
  return value
}

export function preview(amount: string, method: SplitMethod, participants: Participant[]): Share[] {
  const total = cents(amount)
  if (!total || !participants.length) throw new Error('請輸入金額，並選擇最少一位成員。')
  const people = [...participants].sort((a, b) => a.memberId.localeCompare(b.memberId))
  if (method === 'EXACT') {
    const shares = people.map(p => ({ memberId: p.memberId, amountMinor: cents(p.value ?? '') }))
    if (shares.reduce((sum, s) => sum + s.amountMinor, 0) !== total) throw new Error('分攤金額加埋要等於總額。')
    return shares
  }
  const weights = people.map(p => method === 'EQUAL' ? 1 : cents(p.value ?? ''))
  const divisor = weights.reduce((a, b) => a + b, 0)
  if (method === 'PERCENT' && divisor !== 10000) throw new Error('百分比加埋要等於 100%。')
  const allocated = weights.map(w => Math.floor(total * w / divisor))
  const remaining = total - allocated.reduce((a, b) => a + b, 0)
  const order = weights.map((w, i) => ({ i, remainder: total * w % divisor }))
    .sort((a, b) => b.remainder - a.remainder || a.i - b.i)
  order.slice(0, remaining).forEach(({ i }) => { allocated[i]++ })
  return people.map((p, i) => ({ memberId: p.memberId, amountMinor: allocated[i] }))
}
