import { createClient } from '@supabase/supabase-js'
import type { Config } from './types'

export const errorMessages: Record<string, string> = {
  UNAUTHENTICATED: '登入已過期，請重新登入。',
  PROFILE_REQUIRED: '請先設定你嘅稱呼。',
  NOT_FOUND: '搵唔到資料，或者你未加入呢個群組。',
  INVALID_INPUT: '請檢查輸入資料。',
  INVALID_AMOUNT: '請輸入 HK$0.01 至 HK$1,000,000，最多兩位小數。',
  INVALID_SPLIT: '請選擇要分攤嘅成員。',
  INVALID_MEMBER: '成員資料已改變，請重新整理。',
  INVALID_RECIPIENT: '請選擇另一位群組成員。',
  INVALID_DATE: '日期必須介乎 2000 年同今日之間。',
  SPLIT_TOTAL_MISMATCH: '分攤金額加埋要等於總額。',
  PERCENT_TOTAL_MISMATCH: '百分比加埋要等於 100%。',
  DUPLICATE_PARTICIPANT: '同一位成員唔可以重複。',
  STALE_VERSION: '呢筆支出已被更新。請關閉表格，重新整理後再試。',
  VERSION_REQUIRED: '請重新開啟支出再修改。',
  EXPENSE_VOIDED: '呢筆支出已作廢。',
  SETTLEMENT_FINAL: '呢筆還款已處理，請重新整理。',
  IDEMPOTENCY_CONFLICT: '呢次提交已處理，但內容不同。請關閉表格再試。',
  INVITE_INVALID: '邀請已過期或撤銷，請朋友發一條新連結。',
  INVITE_USED: '邀請已被使用，請朋友發一條新連結。',
  GROUP_ARCHIVED: '群組已封存。',
  RATE_LIMITED: '操作太密，請一分鐘後再試。',
  CONFLICT: '資料有衝突，請重新整理再試。',
}

export class ApiError extends Error {
  constructor(public code: string, public status: number) {
    super(errorMessages[code] ?? '暫時未能完成，請稍後再試。')
  }
}

export async function config(): Promise<Config> {
  const response = await fetch('/api/config')
  if (!response.ok) throw new Error('未能連接伺服器，請重新整理。')
  return response.json()
}

export function authClient(settings: Config) {
  return settings.demo ? null : createClient(settings.supabaseUrl, settings.supabasePublishableKey, {
    auth: { flowType: 'pkce', detectSessionInUrl: true },
  })
}

export type Request = <T>(path: string, method?: string, body?: unknown, key?: string) => Promise<T>

export function requestClient(token: () => Promise<string | null>, demoId?: string): Request {
  return async <T>(path: string, method = 'GET', body?: unknown, key?: string): Promise<T> => {
    const headers: Record<string, string> = {}
    if (demoId) headers['X-Demo-User'] = demoId
    else {
      const accessToken = await token()
      if (accessToken) headers.Authorization = `Bearer ${accessToken}`
    }
    if (body !== undefined) headers['Content-Type'] = 'application/json'
    if (key) headers['Idempotency-Key'] = key
    let response: Response
    try {
      response = await fetch(`/api/v1${path}`, {
        method, headers, body: body === undefined ? undefined : JSON.stringify(body),
      })
    } catch {
      throw new Error('連線中斷。請保持表格開啟，恢復連線後重試。')
    }
    if (!response.ok) {
      const error: { code?: string } = await response.json().catch(() => ({}))
      throw new ApiError(error.code ?? 'UNKNOWN', response.status)
    }
    const text = await response.text()
    return text ? JSON.parse(text) as T : undefined as T
  }
}
