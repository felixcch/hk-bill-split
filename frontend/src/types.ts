export type SplitMethod = 'EQUAL' | 'EXACT' | 'PERCENT'
export type PaymentMethod = 'FPS' | 'PAYME' | 'BANK' | 'CASH'
export type Category = 'FOOD' | 'TRANSPORT' | 'STAY' | 'SHOPPING' | 'FUN' | 'OTHER'

export interface Group {
  id: string
  code: string
  name: string
  emoji: string
  currency: string
}

export interface Member {
  id: string
  name: string
}

export interface Share {
  memberId: string
  amountMinor: number
}

export interface Expense {
  id: string
  payerMemberId: string
  description: string
  category: Category
  amountMinor: number
  splitMethod: SplitMethod
  incurredOn: string
  version: number
  createdAt: string
  shares: Share[]
}

export interface Transfer {
  id: string
  fromMemberId: string
  toMemberId: string
  amountMinor: number
  method: PaymentMethod
  incurredOn: string
  createdAt: string
}

export interface Balance {
  memberId: string
  amountMinor: number
  paidMinor: number
  shareMinor: number
}

export interface Suggestion {
  senderMemberId: string
  recipientMemberId: string
  amountMinor: number
}

export interface Snapshot {
  group: Group
  members: Member[]
  expenses: Expense[]
  transfers: Transfer[]
  balances: Balance[]
  suggestions: Suggestion[]
}

export interface Activity {
  id: string
  actorMemberId: string | null
  entityId: string
  action: string
  detail: string
  createdAt: string
}

export interface Page<T> {
  items: T[]
  hasMore: boolean
}

export interface Participant {
  memberId: string
  value?: string
}

export interface ExpenseInput {
  payerMemberId: string
  description: string
  category: Category
  amount: string
  splitMethod: SplitMethod
  incurredOn: string
  participants: Participant[]
  version?: number
}

export interface TransferInput {
  fromMemberId: string
  toMemberId: string
  amount: string
  method: PaymentMethod
  incurredOn: string
}

export const categories: { id: Category; label: string; icon: string }[] = [
  { id: 'FOOD', label: '食飯', icon: '🍜' },
  { id: 'TRANSPORT', label: '交通', icon: '🚕' },
  { id: 'STAY', label: '住宿', icon: '🏨' },
  { id: 'SHOPPING', label: '購物', icon: '🛍️' },
  { id: 'FUN', label: '玩樂', icon: '🎉' },
  { id: 'OTHER', label: '其他', icon: '🧾' },
]

export const paymentMethods: { id: PaymentMethod; label: string }[] = [
  { id: 'FPS', label: '轉數快' },
  { id: 'PAYME', label: 'PayMe' },
  { id: 'BANK', label: '銀行轉賬' },
  { id: 'CASH', label: '現金' },
]

export const emojis = ['🐻', '🍜', '🗼', '🏝️', '🎿', '🍻', '🏠', '🎂', '⛺', '🚗', '🎤', '🛒']
