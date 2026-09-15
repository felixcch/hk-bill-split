export interface User { id: string; displayName: string }
export interface Config {
  supabaseUrl: string; supabasePublishableKey: string; demo: boolean;
  demoUsers: { id: string; name: string }[];
}
export interface Group { id: string; name: string; currency: 'HKD'; archivedAt: string | null }
export interface Member { id: string; userId: string; displayName: string; role: 'OWNER' | 'MEMBER' }
export type SplitMethod = 'EQUAL' | 'EXACT' | 'PERCENT'
export interface Participant { memberId: string; value?: string }
export interface Share { memberId: string; amountMinor: number }
export interface ExpenseInput {
  payerMemberId: string; description: string; amount: string; splitMethod: SplitMethod;
  incurredOn: string; participants: Participant[]; version?: number;
}
export interface Expense {
  id: string; groupId: string; payerMemberId: string; createdBy: string; description: string;
  amountMinor: number; splitMethod: SplitMethod; incurredOn: string; version: number;
  voidedAt: string | null; shares: Share[];
}
export interface Balance { memberId: string; amountMinor: number }
export interface Suggestion { senderMemberId: string; recipientMemberId: string; amountMinor: number }
export interface Balances { balances: Balance[]; suggestions: Suggestion[] }
export type PaymentMethod = 'FPS' | 'PAYME' | 'BANK' | 'CASH'
export interface Settlement {
  id: string; senderMemberId: string; recipientMemberId: string; amountMinor: number;
  method: PaymentMethod; status: 'PENDING' | 'CONFIRMED' | 'REJECTED' | 'CANCELLED';
  version: number; createdAt: string; confirmedAt: string | null;
}
export interface Invite { id: string; token: string; expiresAt: string }
export interface InviteSummary { id: string; expiresAt: string; redeemedAt: string | null; revokedAt: string | null }
export interface Audit { id: string; actorId: string; entityId: string; action: string; detail: string; createdAt: string }
export interface Page<T> { items: T[]; hasMore: boolean }
