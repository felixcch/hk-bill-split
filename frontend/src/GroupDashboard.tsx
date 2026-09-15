import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { Request } from './api'
import type { Audit, Balances, Expense, Group, Member, Page, Settlement, User } from './types'
import { currency } from './money'
import { Avatar, Dialog, ErrorMessage, Pagination } from './ui'
import { ExpenseDialog } from './ExpenseDialog'
import { RepaymentDialog } from './RepaymentDialog'
import { InvitationsDialog } from './InvitationsDialog'

const statusLabels = { PENDING: '等緊確認', CONFIRMED: '已確認收款', REJECTED: '已拒絕', CANCELLED: '已取消' }
const methodLabels = { FPS: 'FPS 轉數快', PAYME: 'PayMe', BANK: '銀行轉帳', CASH: '現金' }
const splitLabels = { EQUAL: '平均分攤', EXACT: '指定金額', PERCENT: '按百分比' }
const auditLabels: Record<string, string> = {
  GROUP_CREATED: '建立咗群組', INVITATION_CREATED: '建立咗邀請', INVITATION_REVOKED: '撤銷咗邀請',
  MEMBER_JOINED: '加入咗群組', EXPENSE_CREATED: '記低咗支出', EXPENSE_EDITED: '修改咗支出',
  EXPENSE_VOIDED: '作廢咗支出', SETTLEMENT_RECORDED: '記低咗還款',
  SETTLEMENT_CONFIRMED: '確認收到還款', SETTLEMENT_REJECTED: '拒絕咗還款記錄', SETTLEMENT_CANCELLED: '取消咗還款記錄',
}
type RepayDraft = { recipientMemberId: string; amountMinor: number } | true | null

export function GroupDashboard({ api, user, group }: { api: Request; user: User; group: Group }) {
  const qc = useQueryClient()
  const prefix = ['group', user.id, group.id]
  const members = useQuery({ queryKey: [...prefix, 'members'], queryFn: () => api<Member[]>(`/groups/${group.id}/members`), refetchInterval: 30000 })
  const balances = useQuery({ queryKey: [...prefix, 'balances'], queryFn: () => api<Balances>(`/groups/${group.id}/balances`), refetchInterval: 30000 })
  const [tab, setTab] = useState<'expenses' | 'settlements' | 'audit'>('expenses')
  const [page, setPage] = useState(0)
  const expenses = useQuery({ queryKey: [...prefix, 'expenses', page], queryFn: () => api<Page<Expense>>(`/groups/${group.id}/expenses?offset=${page * 50}`), enabled: tab === 'expenses', refetchInterval: 30000 })
  const repayments = useQuery({ queryKey: [...prefix, 'settlements', page], queryFn: () => api<Page<Settlement>>(`/groups/${group.id}/settlements?offset=${page * 50}`), enabled: tab === 'settlements', refetchInterval: 30000 })
  const audit = useQuery({ queryKey: [...prefix, 'audit', page], queryFn: () => api<Page<Audit>>(`/groups/${group.id}/audit?offset=${page * 50}`), enabled: tab === 'audit', refetchInterval: 30000 })
  const [expenseForm, setExpenseForm] = useState<Expense | true | null>(null)
  const [detail, setDetail] = useState<Expense | null>(null)
  const [repay, setRepay] = useState<RepayDraft>(null)
  const [inviting, setInviting] = useState(false)
  const [voiding, setVoiding] = useState(false)
  const [confirmation, setConfirmation] = useState<{ settlement: Settlement; action: 'confirm' | 'reject' | 'cancel' } | null>(null)
  const [confirmed, setConfirmed] = useState(false)
  const [notice, setNotice] = useState('')
  const me = members.data?.find(m => m.userId === user.id)
  const memberName = (id: string) => members.data?.find(m => m.id === id)?.displayName ?? '成員'
  const balanceOf = (id: string) => balances.data?.balances.find(b => b.memberId === id)?.amountMinor ?? 0
  const myBalance = me ? balanceOf(me.id) : 0
  const refresh = () => { void qc.invalidateQueries({ queryKey: ['group'] }); void qc.invalidateQueries({ queryKey: ['groups'] }) }
  const voidExpense = useMutation({
    mutationFn: (e: Expense) => api(`/expenses/${e.id}/void`, 'POST', { version: e.version }),
    onSuccess: () => { setDetail(null); setVoiding(false); setNotice('支出已作廢，歷史記錄會保留。'); refresh() },
  })
  const transition = useMutation({
    mutationFn: (c: NonNullable<typeof confirmation>) => api(`/settlements/${c.settlement.id}/${c.action}`, 'POST'),
    onSuccess: () => { setConfirmation(null); setNotice('還款狀態已更新。'); refresh() },
  })
  const currentQuery = tab === 'expenses' ? expenses : tab === 'settlements' ? repayments : audit
  const canEdit = detail && (detail.createdBy === user.id || me?.role === 'OWNER') && !detail.voidedAt
  const suggestions = balances.data?.suggestions ?? []
  return <>
    <div className="page-heading"><div><p className="eyebrow">GROUP EXPENSES</p><h1>{group.name}</h1><p className="muted">{members.data?.length ?? '…'} 位成員 <span className="dot">·</span> 港幣 HKD</p></div><div className="actions">{me?.role === 'OWNER' && <button onClick={() => setInviting(true)}>邀請朋友 ↗</button>}<button className="primary" disabled={!me} onClick={() => setExpenseForm(true)}>+ 記低支出</button></div></div>
    {notice && <div className="notice success" role="status">{notice}<button className="text-button" aria-label="關閉提示" onClick={() => setNotice('')}>×</button></div>}
    <ErrorMessage error={members.error ?? balances.error} />
    {(members.error || balances.error) && <button onClick={refresh}>重新整理群組</button>}
    <div className="summary-grid">
      <section className="balance-hero"><span>{myBalance > 0 ? '你可以收返' : myBalance < 0 ? '你需要還返' : '你嘅結餘'}</span><div className="hero-amount"><small>HKD</small>{balances.isPending || balances.error ? '—' : currency(Math.abs(myBalance))}</div><p>{myBalance === 0 ? '暫時冇未清嘅欠款，輕輕鬆鬆。' : '只計算有效支出同已確認嘅還款。'}</p><span className="hero-decoration" aria-hidden="true">＝</span></section>
      <section className="quick-repay"><p className="eyebrow">SETTLE UP</p><h2>還咗錢？記低先。</h2><p>對方確認收妥，大家嘅條數就會更新。</p><button disabled={!me || (members.data?.length ?? 0) < 2} onClick={() => setRepay(true)}>記錄還款 →</button></section>
    </div>
    <div className="dashboard-grid">
      <section className="activity panel">
        <div className="tabs" role="tablist" aria-label="群組記錄">{(['expenses', 'settlements', 'audit'] as const).map(t => <button key={t} role="tab" aria-selected={tab === t} onClick={() => { setTab(t); setPage(0) }}>{t === 'expenses' ? '支出記錄' : t === 'settlements' ? '還款記錄' : '群組動態'}</button>)}</div>
        <div className="activity-body" role="tabpanel">
          {currentQuery.isPending && <p className="muted">載入記錄…</p>}
          <ErrorMessage error={currentQuery.error} />
          {currentQuery.error && <button onClick={() => void currentQuery.refetch()}>重試載入</button>}
          {currentQuery.data?.items.length === 0 && <div className="empty"><span className="empty-mark" aria-hidden="true">＋</span><h3>{tab === 'expenses' ? '仲未有支出' : tab === 'settlements' ? '仲未有還款記錄' : '仲未有動態'}</h3><p>{tab === 'expenses' ? '由一餐飯、一杯咖啡開始記低。' : '新記錄會喺呢度顯示。'}</p>{tab === 'expenses' && <button disabled={!me} onClick={() => setExpenseForm(true)}>記低第一筆支出</button>}</div>}
          {tab === 'expenses' && expenses.data?.items.map(e => <button className={`expense-row ${e.voidedAt ? 'voided' : ''}`} key={e.id} onClick={() => { setDetail(e); setVoiding(false); voidExpense.reset() }}>
            <span className="receipt-icon" aria-hidden="true">≡</span><span className="row-description"><strong>{e.description}</strong><small>{memberName(e.payerMemberId)}埋單 · {e.incurredOn}</small></span><span className="row-amount"><strong>{currency(e.amountMinor)}</strong><small>{e.voidedAt ? '已作廢' : splitLabels[e.splitMethod]}</small></span><span className="chevron" aria-hidden="true">›</span>
          </button>)}
          {tab === 'settlements' && repayments.data?.items.map(s => <article className="settlement-row" key={s.id}>
            <div className="settlement-title"><strong>{memberName(s.senderMemberId)} <span className="muted">→</span> {memberName(s.recipientMemberId)}</strong><strong>{currency(s.amountMinor)}</strong></div>
            <p className="hint">{methodLabels[s.method]} · {new Date(s.createdAt).toLocaleDateString('zh-HK')}</p>
            <div className="settlement-bottom"><span className={`status ${s.status.toLowerCase()}`}>{statusLabels[s.status]}</span>{s.status === 'PENDING' && <div className="actions">
              {me?.id === s.recipientMemberId && <><button onClick={() => { transition.reset(); setConfirmed(false); setConfirmation({ settlement: s, action: 'reject' }) }}>未收到</button><button className="primary" onClick={() => { transition.reset(); setConfirmed(false); setConfirmation({ settlement: s, action: 'confirm' }); void balances.refetch() }}>確認收款</button></>}
              {me?.id === s.senderMemberId && <button onClick={() => { transition.reset(); setConfirmation({ settlement: s, action: 'cancel' }) }}>取消記錄</button>}
            </div>}</div>
          </article>)}
          {tab === 'audit' && audit.data?.items.map(a => <article className="audit-row" key={a.id}><span className="audit-dot" /><div><strong>{members.data?.find(m => m.userId === a.actorId)?.displayName ?? '成員'}</strong> {auditLabels[a.action] ?? a.action}<small>{new Date(a.createdAt).toLocaleString('zh-HK')}</small>{a.action.startsWith('EXPENSE_') || a.action.startsWith('SETTLEMENT_') ? <details><summary>查看完整修改記錄</summary><pre>{a.detail}</pre></details> : null}</div></article>)}
          <Pagination page={page} hasMore={currentQuery.data?.hasMore ?? false} change={setPage} />
        </div>
      </section>
      <aside className="balance-sidebar"><section className="panel members-panel"><h2>大家嘅結餘</h2><p className="hint">正數係可收返，負數係要還返。</p>{members.data?.map(m => <div className="member-row" key={m.id}><Avatar name={m.displayName} /><span>{m.displayName}{m.id === me?.id && <small>你</small>}</span><strong className={balanceOf(m.id) > 0 ? 'positive' : balanceOf(m.id) < 0 ? 'negative' : ''}>{balances.error ? '—' : `${balanceOf(m.id) > 0 ? '+' : ''}${currency(balanceOf(m.id))}`}</strong></div>)}</section>
        <section className="panel suggestions"><h2>建議點樣還</h2><p className="hint">根據目前結餘計算，可能會隨新支出而變。</p>
          {suggestions.length === 0 && <p className="muted">{balances.isPending ? '計算中…' : '暫時唔需要還款。'}</p>}
          {suggestions.map(s => <div className="suggestion" key={`${s.senderMemberId}:${s.recipientMemberId}`}><p><strong>{memberName(s.senderMemberId)}</strong> → {memberName(s.recipientMemberId)}</p><div><strong>{currency(s.amountMinor)}</strong>{s.senderMemberId === me?.id && <button className="text-button" onClick={() => setRepay(s)}>已還款，記低 →</button>}</div></div>)}
          <p className="hint">如有待確認還款，請先等對方確認，避免重複轉帳。</p>
        </section>
      </aside>
    </div>
    {expenseForm && me && members.data && <ExpenseDialog api={api} groupId={group.id} members={members.data} me={me} expense={expenseForm === true ? undefined : expenseForm} close={() => setExpenseForm(null)} done={() => { setExpenseForm(null); setPage(0); setTab('expenses'); setNotice('支出已儲存，大家嘅結餘已更新。'); refresh() }} />}
    {repay && me && members.data && <RepaymentDialog api={api} groupId={group.id} members={members.data} me={me} initial={repay === true ? undefined : repay} close={() => setRepay(null)} done={() => { setRepay(null); setPage(0); setTab('settlements'); setNotice('已記錄還款，等對方確認收妥。'); refresh() }} />}
    {inviting && <InvitationsDialog api={api} groupId={group.id} userId={user.id} close={() => setInviting(false)} />}
    {detail && <Dialog title={detail.description} onClose={() => { if (!voidExpense.isPending) setDetail(null) }}><div className="detail-total">{currency(detail.amountMinor)} <span className="pill">HKD</span></div><p className="muted">{memberName(detail.payerMemberId)}埋單 · {detail.incurredOn} · {splitLabels[detail.splitMethod]}</p>{detail.voidedAt && <div className="notice">呢筆支出已作廢，唔會影響結餘。</div>}<h3>每人分攤</h3>{detail.shares.map(s => <div className="split-person" key={s.memberId}><span>{memberName(s.memberId)}</span><strong>{currency(s.amountMinor)}</strong></div>)}<p className="hint">版本 {detail.version + 1} · 修改歷史可喺「群組動態」查看。</p><ErrorMessage error={voidExpense.error} />
      {voiding ? <div className="notice"><p>確定作廢呢筆支出？原有已確認還款會保留，結餘會重新計算。</p><div className="actions"><button disabled={voidExpense.isPending} onClick={() => setVoiding(false)}>返回</button><button className="danger" disabled={voidExpense.isPending} onClick={() => voidExpense.mutate(detail)}>確定作廢</button></div></div>
        : canEdit && <div className="dialog-actions"><button className="danger-text" onClick={() => setVoiding(true)}>作廢支出</button><button className="primary" onClick={() => { setExpenseForm(detail); setDetail(null) }}>修改支出</button></div>}
    </Dialog>}
    {confirmation && <Dialog title={confirmation.action === 'confirm' ? '確認已收到還款' : confirmation.action === 'reject' ? '未收到呢筆還款？' : '取消呢筆還款記錄？'} onClose={() => { if (!transition.isPending) setConfirmation(null) }}>
      <div className="detail-total">{currency(confirmation.settlement.amountMinor)}</div><p>{memberName(confirmation.settlement.senderMemberId)} → {memberName(confirmation.settlement.recipientMemberId)} · {methodLabels[confirmation.settlement.method]}</p>
      {confirmation.action === 'confirm' ? <>
        <p className="notice">請檢查你嘅收款紀錄。確認後會更新雙方結餘，唔會實際轉帳。</p>
        <p className="hint">你目前嘅結餘：{currency(myBalance)}。如期間有新支出或其他還款，結餘可能有變。</p>
        {balanceOf(confirmation.settlement.senderMemberId) + confirmation.settlement.amountMinor > 0 && <p className="notice">呢筆還款可能超過對方目前欠款。只喺確實收到呢個金額時確認，多付金額會成為對方嘅應收結餘。</p>}
        <label className="confirm-check"><input type="checkbox" checked={confirmed} onChange={e => setConfirmed(e.target.checked)} />我已核實收到上述金額</label>
      </> : <p className="notice">處理後會保留記錄，結餘唔會改變。</p>}
      <ErrorMessage error={transition.error} /><div className="dialog-actions"><button disabled={transition.isPending} onClick={() => setConfirmation(null)}>返回</button><button className="primary" disabled={transition.isPending || (confirmation.action === 'confirm' && !confirmed)} onClick={() => transition.mutate(confirmation)}>{transition.isPending ? '處理中…' : confirmation.action === 'confirm' ? '確認收妥' : '確定'}</button></div>
    </Dialog>}
  </>
}
