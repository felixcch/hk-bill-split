import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, ApiError } from './api'
import { ExpenseSheet } from './ExpenseSheet'
import { TransferSheet } from './TransferSheet'
import { currency } from './money'
import { rememberGroup, useIdentity } from './store'
import { categories, emojis, paymentMethods } from './types'
import type { Expense, Member, Snapshot, Transfer } from './types'
import { Avatar, Brand, Empty, ErrorMessage, Field, MemberChips, Sheet, relativeDate, timeAgo } from './ui'

type Tab = 'expenses' | 'balances' | 'activity'
type Modal =
  | { kind: 'expense'; expense?: Expense }
  | { kind: 'transfer'; preset?: { from: string; to: string; amountMinor: number } }
  | { kind: 'share' }
  | { kind: 'identity' }
  | { kind: 'settings' }
  | null

export function GroupScreen({ code, fresh, navigate }: { code: string; fresh: boolean; navigate: (path: string) => void }) {
  const [me, setMe] = useIdentity(code)
  const [tab, setTab] = useState<Tab>('expenses')
  const [modal, setModal] = useState<Modal>(fresh ? { kind: 'share' } : null)
  const query = useQuery({
    queryKey: ['group', code],
    queryFn: () => api.snapshot(code),
    refetchInterval: 8000,
    refetchIntervalInBackground: false,
    retry: (count, error) => !(error instanceof ApiError && error.status === 404) && count < 2,
  })
  const snapshot = query.data
  useEffect(() => { if (snapshot) rememberGroup(snapshot.group) }, [snapshot])
  useEffect(() => {
    if (snapshot && me && !snapshot.members.some(m => m.id === me)) setMe(null)
  }, [snapshot, me, setMe])
  useEffect(() => {
    if (snapshot && !me && modal === null) setModal({ kind: 'identity' })
  }, [snapshot, me, modal])

  if (query.isPending) return <main className="screen centre"><Brand compact /><p className="muted">載入群組中…</p></main>
  if (query.isError || !snapshot) {
    const missing = query.error instanceof ApiError && query.error.status === 404
    return <main className="screen centre">
      <Brand compact />
      <Empty icon={missing ? '🔗' : '⚠️'} title={missing ? '搵唔到呢個群組' : '暫時未能載入'}
        hint={missing ? '請確認連結完整無誤，或者叫朋友再傳一次。' : query.error instanceof Error ? query.error.message : undefined}
        action={<div className="row"><button className="ghost" onClick={() => navigate('/')}>返回首頁</button>{!missing && <button className="primary" onClick={() => query.refetch()}>再試一次</button>}</div>} />
    </main>
  }

  const { group, members } = snapshot
  const myself = members.find(m => m.id === me)
  const close = () => setModal(null)

  return <main className="screen">
    <header className="group-header">
      <button className="icon-button" aria-label="返回首頁" onClick={() => navigate('/')}>‹</button>
      <button className="group-title" onClick={() => setModal({ kind: 'settings' })} aria-label="群組設定">
        <span className="group-emoji" aria-hidden="true">{group.emoji}</span>
        <span className="grow"><strong>{group.name}</strong><small>{members.length} 人 · {query.isFetching ? '更新中…' : '已同步'}</small></span>
      </button>
      <button className="icon-button" aria-label="分享連結" onClick={() => setModal({ kind: 'share' })}>⤴</button>
    </header>
    <button className="identity" onClick={() => setModal({ kind: 'identity' })}>
      <Avatar member={myself} size="sm" />{myself ? <>我係 <strong>{myself.name}</strong></> : '你係邊位？'}<span aria-hidden="true">▾</span>
    </button>
    <nav className="tabs" role="tablist" aria-label="群組內容">
      {([['expenses', '支出'], ['balances', '結餘'], ['activity', '動態']] as [Tab, string][]).map(([id, label]) => (
        <button key={id} role="tab" aria-selected={tab === id} className={tab === id ? 'on' : ''} onClick={() => setTab(id)}>{label}</button>
      ))}
    </nav>
    <section role="tabpanel" className="panel">
      {tab === 'expenses' && <Expenses snapshot={snapshot} onEdit={expense => setModal({ kind: 'expense', expense })} onAdd={() => setModal({ kind: 'expense' })} />}
      {tab === 'balances' && <Balances snapshot={snapshot} me={me} code={code} onTransfer={preset => setModal({ kind: 'transfer', preset })} />}
      {tab === 'activity' && <ActivityFeed code={code} members={members} />}
    </section>
    {tab !== 'activity' && <div className="fab-bar">
      <button className="ghost fab-secondary" onClick={() => setModal({ kind: 'transfer' })}>已還錢</button>
      <button className="primary fab" onClick={() => setModal({ kind: 'expense' })}>＋ 記支出</button>
    </div>}
    {modal?.kind === 'expense' && <ExpenseSheet code={code} me={me} snapshot={snapshot} expense={modal.expense} onClose={close} />}
    {modal?.kind === 'transfer' && <TransferSheet code={code} me={me} snapshot={snapshot} preset={modal.preset} onClose={close} />}
    {modal?.kind === 'share' && <ShareSheet snapshot={snapshot} fresh={fresh} onClose={close} />}
    {modal?.kind === 'identity' && <IdentitySheet code={code} snapshot={snapshot} me={me} onPick={id => { setMe(id); close() }} onClose={close} />}
    {modal?.kind === 'settings' && <SettingsSheet code={code} me={me} snapshot={snapshot} onClose={close} />}
  </main>
}

function Expenses({ snapshot, onEdit, onAdd }: { snapshot: Snapshot; onEdit: (e: Expense) => void; onAdd: () => void }) {
  const { expenses, members } = snapshot
  const total = expenses.reduce((sum, e) => sum + e.amountMinor, 0)
  if (!expenses.length) return <Empty icon="🧾" title="仲未有支出" hint="撳「＋ 記支出」記低第一筆，例如晚飯或者的士。" action={<button className="primary" onClick={onAdd}>＋ 記支出</button>} />
  const groups = new Map<string, Expense[]>()
  for (const e of expenses) groups.set(e.incurredOn, [...(groups.get(e.incurredOn) ?? []), e])
  return <>
    <div className="summary-card"><span>總支出</span><strong>{currency(total)}</strong><small>{expenses.length} 筆 · {members.length} 人</small></div>
    {[...groups.entries()].map(([date, list]) => <section key={date} className="day">
      <h3>{relativeDate(date)}<small>{currency(list.reduce((s, e) => s + e.amountMinor, 0))}</small></h3>
      <ul className="list">
        {list.map(e => {
          const cat = categories.find(c => c.id === e.category)
          return <li key={e.id}><button className="row-button" onClick={() => onEdit(e)}>
            <span className="cat" aria-label={cat?.label}>{cat?.icon}</span>
            <span className="grow"><strong>{e.description}</strong><small>{nameOf(members, e.payerMemberId)} 付款 · {e.shares.length === members.length ? '全部人分' : `${e.shares.length} 人分`}</small></span>
            <span className="money">{currency(e.amountMinor)}</span>
          </button></li>
        })}
      </ul>
    </section>)}
  </>
}

function Balances({ snapshot, me, code, onTransfer }: { snapshot: Snapshot; me: string | null; code: string; onTransfer: (preset: { from: string; to: string; amountMinor: number }) => void }) {
  const queries = useQueryClient()
  const { members, balances, suggestions, transfers } = snapshot
  const mine = balances.find(b => b.memberId === me)
  const max = Math.max(1, ...balances.map(b => Math.abs(b.amountMinor)))
  const remove = useMutation({
    mutationFn: (id: string) => api.deleteTransfer(code, me, id),
    onSuccess: () => queries.invalidateQueries({ queryKey: ['group', code] }),
  })
  const settled = suggestions.length === 0
  return <>
    {mine && <div className={`summary-card ${mine.amountMinor > 0 ? 'positive' : mine.amountMinor < 0 ? 'negative' : ''}`}>
      <span>{mine.amountMinor > 0 ? '有人要還畀你' : mine.amountMinor < 0 ? '你要還' : '你已經清數'}</span>
      <strong>{currency(Math.abs(mine.amountMinor))}</strong>
      <small>你付咗 {currency(mine.paidMinor)} · 應分 {currency(mine.shareMinor)}</small>
    </div>}
    <section>
      <h3>每人結餘</h3>
      <ul className="balances">
        {balances.map(b => <li key={b.memberId} className={b.amountMinor >= 0 ? 'positive' : 'negative'}>
          <Avatar member={members.find(m => m.id === b.memberId)} size="sm" />
          <span className="grow">{nameOf(members, b.memberId)}{b.memberId === me && <small>（我）</small>}
            <span className="bar"><span style={{ width: `${Math.abs(b.amountMinor) / max * 100}%` }} /></span>
          </span>
          <span className="money">{b.amountMinor > 0 ? '+' : b.amountMinor < 0 ? '−' : ''}{currency(Math.abs(b.amountMinor))}</span>
        </li>)}
      </ul>
    </section>
    <section>
      <h3>最少轉賬方案<small>{settled ? '' : `${suggestions.length} 筆`}</small></h3>
      {settled ? <Empty icon="🎉" title="大家已經清數" hint="記多啲支出，呢度會計出最少要轉幾多筆數。" />
        : <ul className="list">
          {suggestions.map(s => <li key={`${s.senderMemberId}-${s.recipientMemberId}`} className="suggestion">
            <span className="grow"><strong>{nameOf(members, s.senderMemberId)}</strong> → <strong>{nameOf(members, s.recipientMemberId)}</strong><small>{s.senderMemberId === me ? '你要還' : s.recipientMemberId === me ? '要還畀你' : ''}</small></span>
            <span className="money">{currency(s.amountMinor)}</span>
            <button className="small-button" onClick={() => onTransfer({ from: s.senderMemberId, to: s.recipientMemberId, amountMinor: s.amountMinor })}>已還錢</button>
          </li>)}
        </ul>}
    </section>
    {transfers.length > 0 && <section>
      <h3>還錢記錄</h3>
      <ul className="list">
        {transfers.map((t: Transfer) => <li key={t.id} className="transfer">
          <span className="cat" aria-hidden="true">💸</span>
          <span className="grow"><strong>{nameOf(members, t.fromMemberId)} 還畀 {nameOf(members, t.toMemberId)}</strong><small>{paymentMethods.find(p => p.id === t.method)?.label} · {relativeDate(t.incurredOn)}</small></span>
          <span className="money">{currency(t.amountMinor)}</span>
          <button className="icon-button" aria-label="刪除還錢記錄" disabled={remove.isPending} onClick={() => { if (confirm('刪除呢筆還錢記錄？')) remove.mutate(t.id) }}>×</button>
        </li>)}
      </ul>
      <ErrorMessage error={remove.error} />
    </section>}
  </>
}

const actionLabels: Record<string, string> = {
  GROUP_CREATED: '開咗群組', GROUP_RENAMED: '改咗群組名', MEMBER_ADDED: '加入咗成員', MEMBER_RENAMED: '改咗成員名',
  EXPENSE_ADDED: '記咗支出', EXPENSE_EDITED: '修改咗支出', EXPENSE_DELETED: '刪除咗支出',
  TRANSFER_ADDED: '記咗還錢', TRANSFER_DELETED: '刪除咗還錢記錄',
}

function ActivityFeed({ code, members }: { code: string; members: Member[] }) {
  const feed = useInfiniteQuery({
    queryKey: ['activity', code],
    queryFn: ({ pageParam }) => api.activity(code, pageParam),
    initialPageParam: 0,
    getNextPageParam: (last, pages) => last.hasMore ? pages.reduce((n, p) => n + p.items.length, 0) : undefined,
    refetchInterval: 15000,
  })
  const items = feed.data?.pages.flatMap(p => p.items) ?? []
  if (feed.isPending) return <p className="muted centre">載入中…</p>
  if (feed.isError) return <ErrorMessage error={feed.error} />
  return <>
    <ul className="list activity">
      {items.map(a => <li key={a.id}>
        <Avatar member={members.find(m => m.id === a.actorMemberId)} size="sm" />
        <span className="grow"><strong>{a.actorMemberId ? nameOf(members, a.actorMemberId) : '有人'}</strong> {actionLabels[a.action] ?? a.action}<small>{a.detail}</small></span>
        <time dateTime={a.createdAt}>{timeAgo(a.createdAt)}</time>
      </li>)}
    </ul>
    {feed.hasNextPage && <button className="ghost wide" disabled={feed.isFetchingNextPage} onClick={() => feed.fetchNextPage()}>載入更多</button>}
  </>
}

function ShareSheet({ snapshot, fresh, onClose }: { snapshot: Snapshot; fresh: boolean; onClose: () => void }) {
  const url = `${location.origin}/g/${snapshot.group.code}`
  const [copied, setCopied] = useState(false)
  const text = `${snapshot.group.emoji} 「${snapshot.group.name}」分帳群組，開連結就可以記支出、睇結餘：${url}`
  const copy = async () => {
    try { await navigator.clipboard.writeText(url); setCopied(true); setTimeout(() => setCopied(false), 2000) } catch { /* fall back to manual select */ }
  }
  const canShare = typeof navigator.share === 'function'
  return <Sheet title={fresh ? '群組開好咗 🎉' : '分享連結'} onClose={onClose}>
    <div className="stack">
      <p>將呢條連結傳去 WhatsApp 群，朋友唔使登記，開咗就可以一齊記帳。<strong>有連結嘅人都睇得到，請只傳畀群組成員。</strong></p>
      <div className="share-box"><input readOnly value={url} onFocus={e => e.target.select()} aria-label="群組連結" /><button className="primary" onClick={copy}>{copied ? '已複製 ✓' : '複製'}</button></div>
      <div className="row">
        <a className="button whatsapp" href={`https://wa.me/?text=${encodeURIComponent(text)}`} target="_blank" rel="noopener noreferrer">用 WhatsApp 傳送</a>
        {canShare && <button className="ghost" onClick={() => navigator.share({ title: snapshot.group.name, text, url }).catch(() => undefined)}>其他方式分享…</button>}
      </div>
      <p className="muted small">連結係唯一鎖匙，冇得重設。如果傳錯人，請開一個新群組。</p>
      {fresh && <button className="primary wide" onClick={onClose}>開始記帳</button>}
    </div>
  </Sheet>
}

function IdentitySheet({ code, snapshot, me, onPick, onClose }: { code: string; snapshot: Snapshot; me: string | null; onPick: (id: string) => void; onClose: () => void }) {
  const queries = useQueryClient()
  const [name, setName] = useState('')
  const add = useMutation({
    mutationFn: () => api.addMember(code, me, name.trim()),
    onSuccess: member => { queries.invalidateQueries({ queryKey: ['group', code] }); onPick(member.id) },
  })
  return <Sheet title="你係邊位？" onClose={onClose}>
    <div className="stack">
      <p className="muted">揀返你嘅名，記支出時會預設你付款，結餘會標示你要還／收幾多。呢個選擇只會記喺呢部裝置。</p>
      <ul className="pick-list">
        {snapshot.members.map(m => <li key={m.id}><button className={m.id === me ? 'row-button on' : 'row-button'} onClick={() => onPick(m.id)}><Avatar member={m} /><span className="grow">{m.name}</span>{m.id === me && <span aria-hidden="true">✓</span>}</button></li>)}
      </ul>
      <form className="share-box" onSubmit={(e: FormEvent) => { e.preventDefault(); if (name.trim()) add.mutate() }}>
        <input value={name} onChange={e => setName(e.target.value)} maxLength={40} placeholder="唔喺名單？加自己嘅名" aria-label="新成員名" />
        <button type="submit" className="ghost" disabled={!name.trim() || add.isPending}>加入</button>
      </form>
      <ErrorMessage error={add.error} />
    </div>
  </Sheet>
}

function SettingsSheet({ code, me, snapshot, onClose }: { code: string; me: string | null; snapshot: Snapshot; onClose: () => void }) {
  const queries = useQueryClient()
  const [name, setName] = useState(snapshot.group.name)
  const [emoji, setEmoji] = useState(snapshot.group.emoji)
  const [newMember, setNewMember] = useState('')
  const [renaming, setRenaming] = useState<Member | null>(null)
  const [renameTo, setRenameTo] = useState('')
  const refresh = () => queries.invalidateQueries({ queryKey: ['group', code] })
  const rename = useMutation({ mutationFn: () => api.renameGroup(code, name.trim(), emoji), onSuccess: refresh })
  const add = useMutation({ mutationFn: () => api.addMember(code, me, newMember.trim()), onSuccess: () => { setNewMember(''); refresh() } })
  const renameMember = useMutation({ mutationFn: () => api.renameMember(code, me, renaming!.id, renameTo.trim()), onSuccess: () => { setRenaming(null); refresh() } })
  const dirty = name.trim() !== snapshot.group.name || emoji !== snapshot.group.emoji
  return <Sheet title="群組設定" onClose={onClose}>
    <div className="stack">
      <form className="stack" onSubmit={e => { e.preventDefault(); if (dirty && name.trim()) rename.mutate() }}>
        <Field label="群組名"><input value={name} onChange={e => setName(e.target.value)} maxLength={80} required /></Field>
        <div className="field"><span>圖示</span>
          <div className="chips emoji-chips" role="radiogroup" aria-label="圖示">
            {(emojis.includes(emoji) ? emojis : [emoji, ...emojis]).map(x => <button type="button" key={x} role="radio" aria-checked={emoji === x} className={emoji === x ? 'chip on' : 'chip'} onClick={() => setEmoji(x)}>{x}</button>)}
          </div>
        </div>
        <ErrorMessage error={rename.error} />
        <div className="actions"><button type="submit" className="primary" disabled={!dirty || !name.trim() || rename.isPending}>儲存</button></div>
      </form>
      <section>
        <h3>成員<small>撳名可以改名</small></h3>
        <MemberChips members={snapshot.members} selected={renaming?.id ?? null} onToggle={id => { const m = snapshot.members.find(x => x.id === id)!; setRenaming(m); setRenameTo(m.name) }} label="成員" single />
        {renaming && <form className="share-box" onSubmit={e => { e.preventDefault(); if (renameTo.trim()) renameMember.mutate() }}>
          <input value={renameTo} onChange={e => setRenameTo(e.target.value)} maxLength={40} aria-label={`${renaming.name} 新名`} autoFocus />
          <button type="submit" className="ghost" disabled={!renameTo.trim() || renameTo.trim() === renaming.name || renameMember.isPending}>改名</button>
        </form>}
        <ErrorMessage error={renameMember.error} />
        <form className="share-box" onSubmit={e => { e.preventDefault(); if (newMember.trim()) add.mutate() }}>
          <input value={newMember} onChange={e => setNewMember(e.target.value)} maxLength={40} placeholder="加新成員" aria-label="新成員名" />
          <button type="submit" className="ghost" disabled={!newMember.trim() || add.isPending}>加入</button>
        </form>
        <ErrorMessage error={add.error} />
        <p className="muted small">成員唔可以刪除，以免影響已記低嘅支出；改名就可以。</p>
      </section>
    </div>
  </Sheet>
}

const nameOf = (members: Member[], id: string) => members.find(m => m.id === id)?.name ?? '成員'
