import type {
  Activity, Expense, ExpenseInput, Group, Member, Page, Snapshot, Transfer, TransferInput,
} from './types'

export const errorMessages: Record<string, string> = {
  NOT_FOUND: '搵唔到呢個群組，請確認連結完整。',
  INVALID_INPUT: '請檢查輸入資料。',
  INVALID_AMOUNT: '請輸入 HK$0.01 至 HK$1,000,000，最多兩位小數。',
  INVALID_SPLIT: '請選擇要分攤嘅成員。',
  UNKNOWN_MEMBER: '成員資料已改變，請重新整理。',
  SAME_MEMBER: '收錢同還錢唔可以係同一個人。',
  INVALID_DATE: '日期必須介乎 2000 年同今日之間。',
  SPLIT_TOTAL_MISMATCH: '分攤金額加埋要等於總額。',
  PERCENT_TOTAL_MISMATCH: '百分比加埋要等於 100%。',
  DUPLICATE_PARTICIPANT: '同一位成員唔可以重複。',
  DUPLICATE_MEMBER: '已經有人用呢個名，請改一個。',
  TOO_MANY_MEMBERS: '群組最多 50 人。',
  STALE_VERSION: '呢筆支出剛被其他人更新，請關閉表格再試。',
  VERSION_REQUIRED: '請重新開啟支出再修改。',
  IDEMPOTENCY_MISMATCH: '呢次提交已處理，但內容不同。請關閉表格再試。',
  RATE_LIMITED: '操作太密，請一分鐘後再試。',
}

export class ApiError extends Error {
  constructor(public code: string, public status: number) {
    super(errorMessages[code] ?? '暫時未能完成，請稍後再試。')
  }
}

interface Options {
  method?: string
  body?: unknown
  key?: string
  member?: string | null
}

async function request<T>(path: string, { method = 'GET', body, key, member }: Options = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (key) headers['Idempotency-Key'] = key
  if (member) headers['X-Member'] = member
  let response: Response
  try {
    response = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) })
  } catch {
    throw new Error('未能連接伺服器，請檢查網絡後再試。')
  }
  if (!response.ok) {
    let code = 'UNKNOWN'
    try { code = (await response.json()).code ?? code } catch { /* non-JSON error body */ }
    throw new ApiError(code, response.status)
  }
  return response.json()
}

export const api = {
  createGroup: (name: string, emoji: string, members: string[]) =>
    request<Group>('/api/v1/groups', { method: 'POST', body: { name, emoji, members } }),
  snapshot: (code: string) => request<Snapshot>(`/api/v1/g/${code}`),
  renameGroup: (code: string, name: string, emoji: string) =>
    request<Group>(`/api/v1/g/${code}`, { method: 'PATCH', body: { name, emoji } }),
  addMember: (code: string, member: string | null, name: string) =>
    request<Member>(`/api/v1/g/${code}/members`, { method: 'POST', member, body: { name } }),
  renameMember: (code: string, member: string | null, id: string, name: string) =>
    request<Member>(`/api/v1/g/${code}/members/${id}`, { method: 'PATCH', member, body: { name } }),
  createExpense: (code: string, member: string | null, key: string, body: ExpenseInput) =>
    request<Expense>(`/api/v1/g/${code}/expenses`, { method: 'POST', key, member, body }),
  editExpense: (code: string, member: string | null, id: string, body: ExpenseInput) =>
    request<Expense>(`/api/v1/g/${code}/expenses/${id}`, { method: 'PATCH', member, body }),
  deleteExpense: (code: string, member: string | null, id: string) =>
    request<unknown>(`/api/v1/g/${code}/expenses/${id}`, { method: 'DELETE', member }),
  createTransfer: (code: string, member: string | null, key: string, body: TransferInput) =>
    request<Transfer>(`/api/v1/g/${code}/transfers`, { method: 'POST', key, member, body }),
  deleteTransfer: (code: string, member: string | null, id: string) =>
    request<unknown>(`/api/v1/g/${code}/transfers/${id}`, { method: 'DELETE', member }),
  activity: (code: string, offset: number) =>
    request<Page<Activity>>(`/api/v1/g/${code}/activity?offset=${offset}`),
}
