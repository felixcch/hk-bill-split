import { useMemo, useState } from 'react'
import type { FormEvent } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { api, ApiError } from './api'
import { cents, currency, decimal, evaluate, preview } from './money'
import { categories } from './types'
import type { Category, Expense, ExpenseInput, Member, Share, Snapshot, SplitMethod } from './types'
import { Chips, ErrorMessage, Field, MemberChips, Sheet, today } from './ui'

const methods: { id: SplitMethod; label: string }[] = [
  { id: 'EQUAL', label: '平分' },
  { id: 'EXACT', label: '自訂金額' },
  { id: 'PERCENT', label: '百分比' },
]

interface Props {
  code: string
  me: string | null
  snapshot: Snapshot
  expense?: Expense
  onClose: () => void
}

export function ExpenseSheet({ code, me, snapshot, expense, onClose }: Props) {
  const queries = useQueryClient()
  const members = snapshot.members
  const [description, setDescription] = useState(expense?.description ?? '')
  const [category, setCategory] = useState<Category>(expense?.category ?? 'FOOD')
  const [amount, setAmount] = useState(expense ? decimal(expense.amountMinor) : '')
  const [date, setDate] = useState(expense?.incurredOn ?? today())
  const [payer, setPayer] = useState<string | null>(expense?.payerMemberId ?? me ?? members[0]?.id ?? null)
  const [method, setMethod] = useState<SplitMethod>(expense?.splitMethod ?? 'EQUAL')
  const [selected, setSelected] = useState<Set<string>>(() => new Set(expense ? expense.shares.map(s => s.memberId) : members.map(m => m.id)))
  const [values, setValues] = useState<Record<string, string>>(() => {
    if (!expense || expense.splitMethod !== 'EXACT') return {}
    return Object.fromEntries(expense.shares.map(s => [s.memberId, decimal(s.amountMinor)]))
  })
  const [locked, setLocked] = useState<{ key: string; body: ExpenseInput } | null>(null)
  const [touchedCategory, setTouchedCategory] = useState(!!expense)

  const history = useMemo(() => {
    const seen = new Map<string, Category>()
    for (const e of snapshot.expenses) if (!seen.has(e.description)) seen.set(e.description, e.category)
    return seen
  }, [snapshot.expenses])

  const describe = (text: string) => {
    setDescription(text)
    if (!touchedCategory) {
      const guess = history.get(text.trim()) ?? guessCategory(text)
      if (guess) setCategory(guess)
    }
  }

  const evaluated = evaluate(amount)
  const isExpression = evaluated !== amount.trim() && /[+\-*/×÷xX]/.test(amount)
  const participants = members.filter(m => selected.has(m.id)).map(m => ({ memberId: m.id, value: method === 'EQUAL' ? undefined : values[m.id] ?? '' }))
  const shares = ((): Share[] | string => {
    try { return preview(evaluated, method, participants) } catch (e) { return e instanceof Error ? e.message : '' }
  })()
  const exactRemaining = method === 'EXACT' ? safeCents(evaluated) - participants.reduce((sum, p) => sum + safeCents(p.value ?? ''), 0) : 0
  const percentRemaining = method === 'PERCENT' ? 10000 - participants.reduce((sum, p) => sum + safeCents(p.value ?? ''), 0) : 0

  const save = useMutation({
    mutationFn: async () => {
      const body: ExpenseInput = locked?.body ?? {
        payerMemberId: payer!, description: description.trim(), category, amount: evaluated,
        splitMethod: method, incurredOn: date, participants, version: expense?.version,
      }
      if (expense) return api.editExpense(code, me, expense.id, body)
      const key = locked?.key ?? crypto.randomUUID()
      setLocked({ key, body })
      return api.createExpense(code, me, key, body)
    },
    onSuccess: () => {
      queries.invalidateQueries({ queryKey: ['group', code] })
      onClose()
    },
    onError: error => { if (error instanceof ApiError && error.status === 400) setLocked(null) },
  })
  const remove = useMutation({
    mutationFn: () => api.deleteExpense(code, me, expense!.id),
    onSuccess: () => { queries.invalidateQueries({ queryKey: ['group', code] }); onClose() },
  })

  const canSubmit = !!payer && description.trim() && typeof shares !== 'string' && !save.isPending
  const submit = (e: FormEvent) => { e.preventDefault(); if (canSubmit) save.mutate() }
  const busy = save.isPending || remove.isPending
  const disabled = !!locked && !save.isError

  return <Sheet title={expense ? '修改支出' : '記支出'} onClose={onClose}>
    <form onSubmit={submit} className="stack">
      <Field label="金額 (HK$)" hint="可以直接計數，例如 120+80">
        <input className="amount" inputMode="decimal" value={amount} onChange={e => setAmount(e.target.value)}
          onBlur={() => { if (isExpression) setAmount(evaluated) }} placeholder="0.00" required disabled={disabled} autoFocus={!expense} />
        {isExpression && <small className="muted">= HK${evaluated}</small>}
      </Field>
      <Field label="係咩支出？">
        <input list="expense-history" value={description} onChange={e => describe(e.target.value)} maxLength={160} placeholder="例如：晚飯、的士、酒店" required disabled={disabled} />
        <datalist id="expense-history">{[...history.keys()].map(d => <option key={d} value={d} />)}</datalist>
      </Field>
      {history.size > 0 && !expense && <div className="suggestions" aria-label="最近記過">
        {[...history.keys()].slice(0, 6).map(d => <button type="button" key={d} className="tag" onClick={() => describe(d)} disabled={disabled}>{d}</button>)}
      </div>}
      <div className="field"><span>類別</span><Chips options={categories} value={category} onChange={c => { setCategory(c); setTouchedCategory(true) }} label="類別" /></div>
      <div className="field"><span>邊個先付錢？</span><MemberChips members={members} selected={payer} onToggle={setPayer} label="付款人" single /></div>
      <div className="field"><span>邊個要分？<small>撳一下取消／加入</small></span>
        <MemberChips members={members} selected={selected} onToggle={id => setSelected(s => { const n = new Set(s); if (n.has(id)) n.delete(id); else n.add(id); return n })} label="分攤成員" />
        <div className="row">
          <button type="button" className="link" onClick={() => setSelected(new Set(members.map(m => m.id)))} disabled={disabled}>全選</button>
          <button type="button" className="link" onClick={() => setSelected(new Set(payer ? [payer] : []))} disabled={disabled}>只係付款人</button>
        </div>
      </div>
      <div className="field"><span>點樣分？</span><Chips options={methods} value={method} onChange={setMethod} label="分攤方式" /></div>
      {method !== 'EQUAL' && <div className="exact">
        {members.filter(m => selected.has(m.id)).map(m => <label key={m.id} className="exact-row">
          <span>{m.name}</span>
          <input inputMode="decimal" value={values[m.id] ?? ''} onChange={e => setValues(v => ({ ...v, [m.id]: e.target.value }))}
            placeholder={method === 'EXACT' ? '0.00' : '%'} aria-label={`${m.name} ${method === 'EXACT' ? '金額' : '百分比'}`} disabled={disabled} />
        </label>)}
        <p className="muted small">
          {method === 'EXACT' ? (exactRemaining === 0 ? '金額啱啱好。' : exactRemaining > 0 ? `仲有 ${currency(exactRemaining)} 未分。` : `多分咗 ${currency(-exactRemaining)}。`)
            : (percentRemaining === 0 ? '合共 100%。' : `仲有 ${(percentRemaining / 100).toFixed(2).replace(/\.?0+$/, '')}% 未分。`)}
        </p>
      </div>}
      <Field label="日期"><input type="date" value={date} max={today()} min="2000-01-01" onChange={e => setDate(e.target.value)} required disabled={disabled} /></Field>
      <div className="preview" aria-live="polite">
        {typeof shares === 'string' ? <span className="muted">{shares || '輸入金額同成員後會顯示每人分幾多。'}</span>
          : <ul>{shares.map(s => <li key={s.memberId}><span>{nameOf(members, s.memberId)}</span><strong>{currency(s.amountMinor)}</strong></li>)}</ul>}
      </div>
      <ErrorMessage error={save.error ?? remove.error} />
      <div className="actions">
        {expense && <button type="button" className="danger ghost" disabled={busy} onClick={() => { if (confirm('刪除呢筆支出？結餘會即時更新。')) remove.mutate() }}>刪除</button>}
        <button type="submit" className="primary" disabled={!canSubmit || busy}>{save.isPending ? '儲存中…' : expense ? '儲存修改' : '記低'}</button>
      </div>
    </form>
  </Sheet>
}

const nameOf = (members: Member[], id: string) => members.find(m => m.id === id)?.name ?? '成員'
function safeCents(v: string) { try { return cents(v) } catch { return 0 } }

const keywords: [RegExp, Category][] = [
  [/飯|餐|食|茶|咖啡|飲|酒|燒|火鍋|壽司|麵|拉麵|甜品|早餐|午餐|晚餐|lunch|dinner|breakfast|food|cafe|bar|ramen|sushi|bbq/i, 'FOOD'],
  [/的士|taxi|uber|地鐵|mtr|巴士|bus|車|油|泊車|機票|flight|火車|train|船|ferry|租車|grab|uber/i, 'TRANSPORT'],
  [/酒店|hotel|airbnb|民宿|住|hostel|旅館|ryokan/i, 'STAY'],
  [/買|超市|supermarket|shopping|手信|禮物|gift|donki|藥妝|outlet|服/i, 'SHOPPING'],
  [/門票|ticket|樂園|disney|usj|karaoke|k 房|唱k|戲|movie|遊戲|game|spa|按摩|溫泉|onsen|show|演唱會|concert|球|bowling/i, 'FUN'],
]
function guessCategory(text: string): Category | null {
  for (const [pattern, category] of keywords) if (pattern.test(text)) return category
  return null
}
