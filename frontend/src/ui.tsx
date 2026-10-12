import { useEffect, useId, useRef } from 'react'
import type { ReactNode } from 'react'
import type { Member } from './types'

export function ErrorMessage({ error }: { error: unknown }) {
  if (!error) return null
  return <div className="error" role="alert">{error instanceof Error ? error.message : '暫時未能完成，請再試。'}</div>
}

/** Bottom sheet on phones, centred dialog on wide screens. */
export function Sheet({ title, children, onClose }: { title: string; children: ReactNode; onClose: () => void }) {
  const ref = useRef<HTMLDialogElement>(null)
  const titleId = useId()
  useEffect(() => { ref.current?.showModal() }, [])
  return <dialog ref={ref} className="sheet" aria-labelledby={titleId}
    onCancel={e => { e.preventDefault(); onClose() }}
    onClick={e => { if (e.target === ref.current) onClose() }}>
    <div className="sheet-body">
      <div className="sheet-heading"><h2 id={titleId}>{title}</h2><button type="button" className="icon-button" onClick={onClose} aria-label="關閉">×</button></div>
      {children}
    </div>
  </dialog>
}

export function Field({ label, children, hint }: { label: string; children: ReactNode; hint?: string }) {
  return <label className="field"><span>{label}{hint && <small>{hint}</small>}</span>{children}</label>
}

const palette = ['#f2a54a', '#4a9ff2', '#7bc96f', '#e46b7a', '#a97bf2', '#3cbfb0', '#f26b4a', '#c9a227']
export function hue(id: string) {
  let h = 0
  for (const ch of id) h = (h * 31 + ch.charCodeAt(0)) >>> 0
  return palette[h % palette.length]
}

export function Avatar({ member, size = 'md' }: { member: Member | undefined; size?: 'sm' | 'md' | 'lg' }) {
  const name = member?.name ?? '?'
  return <span className={`avatar avatar-${size}`} style={{ background: member ? hue(member.id) : '#bbb' }} aria-hidden="true">{[...name].slice(0, 1)}</span>
}

export function Chips<T extends string>({ options, value, onChange, label }: {
  options: { id: T; label: string; icon?: string }[]
  value: T | null
  onChange: (id: T) => void
  label: string
}) {
  return <div className="chips" role="radiogroup" aria-label={label}>
    {options.map(o => (
      <button type="button" key={o.id} role="radio" aria-checked={value === o.id} className={value === o.id ? 'chip on' : 'chip'} onClick={() => onChange(o.id)}>
        {o.icon && <span aria-hidden="true">{o.icon}</span>}{o.label}
      </button>
    ))}
  </div>
}

export function MemberChips({ members, selected, onToggle, label, single }: {
  members: Member[]
  selected: Set<string> | string | null
  onToggle: (id: string) => void
  label: string
  single?: boolean
}) {
  const isOn = (id: string) => typeof selected === 'string' ? selected === id : selected?.has(id) ?? false
  return <div className="chips" role={single ? 'radiogroup' : 'group'} aria-label={label}>
    {members.map(m => (
      <button type="button" key={m.id} role={single ? 'radio' : 'checkbox'} aria-checked={isOn(m.id)}
        className={isOn(m.id) ? 'chip member on' : 'chip member'} onClick={() => onToggle(m.id)}>
        <Avatar member={m} size="sm" />{m.name}
      </button>
    ))}
  </div>
}

export function Brand({ compact }: { compact?: boolean }) {
  return <div className="brand"><span className="brand-mark" aria-hidden="true">夾</span>{!compact && <div>一齊夾<small>朋友分帳，唔使登記</small></div>}</div>
}

export function Empty({ icon, title, hint, action }: { icon: string; title: string; hint?: string; action?: ReactNode }) {
  return <div className="empty"><span className="empty-icon" aria-hidden="true">{icon}</span><strong>{title}</strong>{hint && <p>{hint}</p>}{action}</div>
}

export const today = () => new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Hong_Kong' }).format(new Date())

const localDate = (iso: string) => {
  const [y, m, d] = iso.split('-').map(Number)
  return new Date(y, m - 1, d)
}

export function relativeDate(iso: string) {
  const date = localDate(iso)
  const now = localDate(today())
  const y = date.getFullYear()
  const days = Math.round((now.getTime() - date.getTime()) / 86_400_000)
  if (days === 0) return '今日'
  if (days === 1) return '昨日'
  if (days < 7) return `${days} 日前`
  return new Intl.DateTimeFormat('zh-HK', { month: 'numeric', day: 'numeric', ...(y !== now.getFullYear() ? { year: 'numeric' } : {}) }).format(date)
}

export const timeAgo = (iso: string) => {
  const mins = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 60_000))
  if (mins < 1) return '剛剛'
  if (mins < 60) return `${mins} 分鐘前`
  if (mins < 60 * 24) return `${Math.round(mins / 60)} 小時前`
  return new Intl.DateTimeFormat('zh-HK', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }).format(new Date(iso))
}
