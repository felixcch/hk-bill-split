import { useCallback, useSyncExternalStore } from 'react'

export interface RecentGroup {
  code: string
  name: string
  emoji: string
  visitedAt: number
}

const RECENT = 'billsplit.recent'
const listeners = new Set<() => void>()

function read<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(key)
    return raw ? (JSON.parse(raw) as T) : fallback
  } catch {
    return fallback
  }
}

function write(key: string, value: unknown) {
  try {
    if (value === null) localStorage.removeItem(key)
    else localStorage.setItem(key, JSON.stringify(value))
  } catch { /* storage unavailable (private mode) */ }
  listeners.forEach(l => l())
}

const subscribe = (listener: () => void) => {
  listeners.add(listener)
  window.addEventListener('storage', listener)
  return () => {
    listeners.delete(listener)
    window.removeEventListener('storage', listener)
  }
}

let recentCache = ''
let recentValue: RecentGroup[] = []
function recentGroups(): RecentGroup[] {
  const raw = localStorage.getItem(RECENT) ?? '[]'
  if (raw !== recentCache) {
    recentCache = raw
    recentValue = read<RecentGroup[]>(RECENT, [])
  }
  return recentValue
}

export function useRecentGroups() {
  return useSyncExternalStore(subscribe, recentGroups, () => [])
}

export function rememberGroup(group: { code: string; name: string; emoji: string }) {
  const rest = read<RecentGroup[]>(RECENT, []).filter(g => g.code !== group.code)
  write(RECENT, [{ ...group, visitedAt: Date.now() }, ...rest].slice(0, 20))
}

export function forgetGroup(code: string) {
  write(RECENT, read<RecentGroup[]>(RECENT, []).filter(g => g.code !== code))
  write(`billsplit.me.${code}`, null)
}

export function useIdentity(code: string): [string | null, (member: string | null) => void] {
  const key = `billsplit.me.${code}`
  const value = useSyncExternalStore(subscribe, () => localStorage.getItem(key), () => null)
  const set = useCallback((member: string | null) => {
    try {
      if (member) localStorage.setItem(key, member)
      else localStorage.removeItem(key)
    } catch { /* storage unavailable */ }
    listeners.forEach(l => l())
  }, [key])
  return [value, set]
}
