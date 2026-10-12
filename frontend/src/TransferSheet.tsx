import { useState } from 'react'
import type { FormEvent } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { api, ApiError } from './api'
import { currency, decimal, evaluate } from './money'
import { paymentMethods } from './types'
import type { PaymentMethod, Snapshot, TransferInput } from './types'
import { Chips, ErrorMessage, Field, MemberChips, Sheet, today } from './ui'

interface Props {
  code: string
  me: string | null
  snapshot: Snapshot
  preset?: { from: string; to: string; amountMinor: number }
  onClose: () => void
}

export function TransferSheet({ code, me, snapshot, preset, onClose }: Props) {
  const queries = useQueryClient()
  const members = snapshot.members
  const [from, setFrom] = useState<string | null>(preset?.from ?? me ?? null)
  const [to, setTo] = useState<string | null>(preset?.to ?? null)
  const [amount, setAmount] = useState(preset ? decimal(preset.amountMinor) : '')
  const [method, setMethod] = useState<PaymentMethod>('FPS')
  const [date, setDate] = useState(today())
  const [locked, setLocked] = useState<{ key: string; body: TransferInput } | null>(null)
  const evaluated = evaluate(amount)

  const owed = from && to ? snapshot.suggestions.find(s => s.senderMemberId === from && s.recipientMemberId === to)?.amountMinor : undefined

  const save = useMutation({
    mutationFn: () => {
      const body: TransferInput = locked?.body ?? { fromMemberId: from!, toMemberId: to!, amount: evaluated, method, incurredOn: date }
      const key = locked?.key ?? crypto.randomUUID()
      setLocked({ key, body })
      return api.createTransfer(code, me, key, body)
    },
    onSuccess: () => { queries.invalidateQueries({ queryKey: ['group', code] }); onClose() },
    onError: error => { if (error instanceof ApiError && error.status === 400) setLocked(null) },
  })
  const disabled = !!locked && !save.isError
  const canSubmit = from && to && from !== to && evaluated && !save.isPending
  const submit = (e: FormEvent) => { e.preventDefault(); if (canSubmit) save.mutate() }

  return <Sheet title="已還錢" onClose={onClose}>
    <form onSubmit={submit} className="stack">
      <p className="muted">用轉數快／PayMe 過完數，喺呢度記一筆，結餘就會即時更新。唔需要對方確認。</p>
      <div className="field"><span>邊個還？</span><MemberChips members={members} selected={from} onToggle={setFrom} label="還錢人" single /></div>
      <div className="field"><span>還畀邊個？</span><MemberChips members={members.filter(m => m.id !== from)} selected={to} onToggle={setTo} label="收錢人" single /></div>
      <Field label="金額 (HK$)">
        <input className="amount" inputMode="decimal" value={amount} onChange={e => setAmount(e.target.value)} onBlur={() => setAmount(evaluated)} placeholder="0.00" required disabled={disabled} />
        {owed !== undefined && <button type="button" className="link" onClick={() => setAmount(decimal(owed))} disabled={disabled}>填入應還 {currency(owed)}</button>}
      </Field>
      <div className="field"><span>方式</span><Chips options={paymentMethods} value={method} onChange={setMethod} label="付款方式" /></div>
      <Field label="日期"><input type="date" value={date} max={today()} min="2000-01-01" onChange={e => setDate(e.target.value)} required disabled={disabled} /></Field>
      <ErrorMessage error={save.error} />
      <div className="actions">
        <button type="submit" className="primary" disabled={!canSubmit}>{save.isPending ? '儲存中…' : '記低已還'}</button>
      </div>
    </form>
  </Sheet>
}
