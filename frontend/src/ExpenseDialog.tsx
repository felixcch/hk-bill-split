import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import type { Request } from './api'
import type { Expense, ExpenseInput, Member, SplitMethod } from './types'
import { currency, decimal, preview } from './money'
import { Dialog, ErrorMessage, Field } from './ui'

export function ExpenseDialog({ api, groupId, members, me, expense, done, close }: {
  api: Request; groupId: string; members: Member[]; me: Member; expense?: Expense; done: () => void; close: () => void;
}) {
  const [description, setDescription] = useState(expense?.description ?? '')
  const [amount, setAmount] = useState(expense ? decimal(expense.amountMinor) : '')
  const [payer, setPayer] = useState(expense?.payerMemberId ?? me.id)
  const [method, setMethod] = useState<SplitMethod>(expense ? 'EXACT' : 'EQUAL')
  const [date, setDate] = useState(expense?.incurredOn ?? new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Hong_Kong', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date()))
  const [selected, setSelected] = useState(expense?.shares.map(s => s.memberId) ?? members.map(m => m.id))
  const [values, setValues] = useState<Record<string, string>>(Object.fromEntries(expense?.shares.map(s => [s.memberId, decimal(s.amountMinor)]) ?? []))
  const [operation] = useState(() => crypto.randomUUID())
  const [lockedPayload, setLockedPayload] = useState<ExpenseInput | null>(null)
  const participants = selected.map(memberId => ({ memberId, value: method === 'EQUAL' ? undefined : values[memberId] ?? '' }))
  let shares: ReturnType<typeof preview> = []
  let previewError = ''
  try { shares = preview(amount, method, participants) } catch (e) { previewError = e instanceof Error ? e.message : '' }
  const save = useMutation({
    mutationFn: (input: ExpenseInput) => api<Expense>(expense ? `/expenses/${expense.id}` : `/groups/${groupId}/expenses`, expense ? 'PATCH' : 'POST', input, expense ? undefined : operation),
    onSuccess: done,
  })
  const frozen = save.isPending || lockedPayload !== null
  return <Dialog title={expense ? '修改支出' : '記低一筆支出'} onClose={save.isPending ? () => {} : close}>
    <form onSubmit={e => {
      e.preventDefault()
      if (previewError && !lockedPayload) return
      const input = lockedPayload ?? { payerMemberId: payer, description, amount, splitMethod: method, incurredOn: date, participants, ...(expense ? { version: expense.version } : {}) }
      if (!expense) setLockedPayload(input)
      save.mutate(input)
    }}>
      <fieldset disabled={frozen}>
        <Field label="咩嘢開支？"><input required maxLength={160} value={description} onChange={e => setDescription(e.target.value)} placeholder="例如：星期五打邊爐" autoFocus /></Field>
        <div className="form-grid">
          <Field label="總額（HKD）"><input required inputMode="decimal" value={amount} onChange={e => setAmount(e.target.value)} placeholder="0.00" /></Field>
          <Field label="日期"><input type="date" required min="2000-01-01" value={date} onChange={e => setDate(e.target.value)} /></Field>
        </div>
        <Field label="邊個埋單？"><select value={payer} onChange={e => setPayer(e.target.value)}>{members.map(m => <option key={m.id} value={m.id}>{m.displayName}</option>)}</select></Field>
        <Field label="點樣分？"><select value={method} onChange={e => setMethod(e.target.value as SplitMethod)}><option value="EQUAL">平均分攤</option><option value="EXACT">指定金額</option><option value="PERCENT">按百分比分攤</option></select></Field>
        {expense && <p className="hint">修改時預設使用已入帳嘅實際分攤金額；你可以重新選擇分攤方式。</p>}
        <div className="split-list">{members.map(m => <div className="split-person" key={m.id}>
          <label><input type="checkbox" checked={selected.includes(m.id)} onChange={e => setSelected(e.target.checked ? [...selected, m.id] : selected.filter(id => id !== m.id))} />{m.displayName}</label>
          {selected.includes(m.id) && (method === 'EQUAL'
            ? <strong>{currency(shares.find(s => s.memberId === m.id)?.amountMinor ?? 0)}</strong>
            : <div className="share-input"><input aria-label={`${m.displayName}嘅${method === 'EXACT' ? '金額' : '百分比'}`} inputMode="decimal" required value={values[m.id] ?? ''} onChange={e => setValues({ ...values, [m.id]: e.target.value })} placeholder="0.00" /><span>{method === 'EXACT' ? 'HKD' : '%'}</span></div>)}
        </div>)}</div>
      </fieldset>
      <div className={`notice ${previewError ? '' : 'success'}`}>{previewError || `合共 ${currency(shares.reduce((sum, s) => sum + s.amountMinor, 0))}，由 ${selected.length} 位成員分攤。`}</div>
      <p className="hint">服務費請計入總額。尾數會按固定次序分配，最後以伺服器計算為準。</p>
      <ErrorMessage error={save.error} />
      {lockedPayload && save.isError && <p className="hint">重試會使用同一筆提交，避免重複入帳。如要修改內容，請先關閉並重新整理，確認有冇已成功入帳。</p>}
      <div className="dialog-actions"><button type="button" disabled={save.isPending} onClick={close}>取消</button><button className="primary" disabled={save.isPending || (!!previewError && !lockedPayload)}>{save.isPending ? '儲存中…' : save.isError && lockedPayload ? '重試同一筆提交' : '儲存支出'}</button></div>
    </form>
  </Dialog>
}
