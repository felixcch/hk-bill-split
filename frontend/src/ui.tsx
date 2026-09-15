import { useEffect, useId, useRef } from 'react'
import type { ReactNode } from 'react'

export function ErrorMessage({ error }: { error: unknown }) {
  if (!error) return null
  return <div className="error" role="alert">{error instanceof Error ? error.message : '暫時未能完成，請再試。'}</div>
}

export function Dialog({ title, children, onClose }: { title: string; children: ReactNode; onClose: () => void }) {
  const ref = useRef<HTMLDialogElement>(null)
  const titleId = useId()
  useEffect(() => { ref.current?.showModal() }, [])
  return <dialog ref={ref} aria-labelledby={titleId} onCancel={e => { e.preventDefault(); onClose() }}>
    <div className="dialog-heading"><h2 id={titleId}>{title}</h2><button className="icon-button" onClick={onClose} aria-label="關閉">×</button></div>
    {children}
  </dialog>
}

export function Field({ label, children }: { label: string; children: ReactNode }) {
  return <label className="field"><span>{label}</span>{children}</label>
}

export function Avatar({ name }: { name: string }) {
  return <span className="avatar" aria-hidden="true">{name.slice(-1)}</span>
}

export function Brand() {
  return <div className="brand"><span className="brand-mark" aria-hidden="true">夾</span><div>一齊夾<small>朋友分帳，簡單清楚</small></div></div>
}

export function Pagination({ page, hasMore, change }: { page: number; hasMore: boolean; change: (page: number) => void }) {
  if (page === 0 && !hasMore) return null
  return <nav className="pagination" aria-label="分頁"><button disabled={page === 0} onClick={() => change(page - 1)}>上一頁</button><span>第 {page + 1} 頁</span><button disabled={!hasMore} onClick={() => change(page + 1)}>下一頁</button></nav>
}
