import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { ApiError, type Request } from './api'
import type { Member, PaymentMethod, Settlement } from './types'
import { cents, decimal } from './money'
import { Dialog, ErrorMessage, Field } from './ui'

interface Input { recipientMemberId: string; amount: string; method: PaymentMethod }

export function RepaymentDialog({ api, groupId, members, me, initial, done, close }: {
  api: Request; groupId: string; members: Member[]; me: Member;
  initial?: { recipientMemberId: string; amountMinor: number }; done: () => void; close: () => void;
}) {
  const [recipient, setRecipient] = useState(initial?.recipientMemberId ?? members.find(m => m.id !== me.id)?.id ?? '')
  const [amount, setAmount] = useState(initial ? decimal(initial.amountMinor) : '')
  const [method, setMethod] = useState<PaymentMethod>('FPS')
  const [operation] = useState(() => crypto.randomUUID())
  const [locked, setLocked] = useState<Input | null>(null)
  const [validation, setValidation] = useState<Error | null>(null)
  const save = useMutation({
    mutationFn: (input: Input) => api<Settlement>(`/groups/${groupId}/settlements`, 'POST', input, operation),
    onSuccess: done,
    onError: error => {
      if (error instanceof ApiError && error.status === 400) setLocked(null)
    },
  })
  return <Dialog title="記低已轉出嘅還款" onClose={save.isPending ? () => {} : close}>
    <p className="notice">請先用 FPS、PayMe、銀行或現金付款。呢度只記錄還款，對方確認收款後先會更新結餘。</p>
    <form onSubmit={e => {
      e.preventDefault()
      try {
        if (cents(amount) <= 0) throw new Error('還款金額要大於零。')
        setValidation(null)
        const input = locked ?? { recipientMemberId: recipient, amount, method }
        setLocked(input)
        save.mutate(input)
      } catch (error) { setValidation(error instanceof Error ? error : new Error('請檢查金額。')) }
    }}>
      <fieldset disabled={save.isPending || locked !== null}>
        <Field label="還款畀邊個？"><select required value={recipient} onChange={e => setRecipient(e.target.value)}>{members.filter(m => m.id !== me.id).map(m => <option key={m.id} value={m.id}>{m.displayName}</option>)}</select></Field>
        <Field label="已轉出金額（HKD）"><input required inputMode="decimal" placeholder="0.00" value={amount} onChange={e => setAmount(e.target.value)} /></Field>
        <Field label="付款方式"><select value={method} onChange={e => setMethod(e.target.value as PaymentMethod)}><option value="FPS">FPS 轉數快</option><option value="PAYME">PayMe</option><option value="BANK">銀行轉帳</option><option value="CASH">現金</option></select></Field>
      </fieldset>
      <ErrorMessage error={validation ?? save.error} />
      {locked && save.isError && <p className="hint">重試會沿用同一筆提交，避免重複記錄。修改前請先關閉並重新整理，確認有冇已入帳。</p>}
      <div className="dialog-actions"><button type="button" disabled={save.isPending} onClick={close}>取消</button><button className="primary" disabled={save.isPending || !recipient}>{save.isPending ? '記錄中…' : locked && save.isError ? '重試同一筆提交' : '我已付款，記低還款'}</button></div>
    </form>
  </Dialog>
}
