import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { Request } from './api'
import type { Invite, InviteSummary } from './types'
import { Dialog, ErrorMessage } from './ui'

export function InvitationsDialog({ api, groupId, userId, close }: { api: Request; groupId: string; userId: string; close: () => void }) {
  const qc = useQueryClient()
  const key = ['group', userId, groupId, 'invites']
  const invites = useQuery({ queryKey: key, queryFn: () => api<InviteSummary[]>(`/groups/${groupId}/invitations`) })
  const [link, setLink] = useState('')
  const [copied, setCopied] = useState(false)
  const create = useMutation({
    mutationFn: () => api<Invite>(`/groups/${groupId}/invitations`, 'POST'),
    onSuccess: invite => {
      setLink(`${window.location.origin}/#invite=${encodeURIComponent(invite.token)}`)
      setCopied(false)
      void qc.invalidateQueries({ queryKey: key })
    },
  })
  const revoke = useMutation({
    mutationFn: (id: string) => api<void>(`/groups/${groupId}/invitations/${id}/revoke`, 'POST'),
    onSuccess: () => { setLink(''); void qc.invalidateQueries({ queryKey: key }) },
  })
  return <Dialog title="邀請朋友一齊夾" onClose={close}>
    <p className="muted">每條連結只可使用一次，七日後到期。朋友登入並加入後，就可以一齊分帳。</p>
    <button className="primary full" disabled={create.isPending} onClick={() => create.mutate()}>{create.isPending ? '建立中…' : '產生邀請連結'}</button>
    {link && <div className="invite-link"><label className="field"><span>複製後傳畀朋友</span><input readOnly value={link} onFocus={e => e.target.select()} /></label><button onClick={() => { void navigator.clipboard.writeText(link).then(() => setCopied(true)).catch(() => setCopied(false)) }}>{copied ? '已複製' : '複製連結'}</button></div>}
    <ErrorMessage error={create.error ?? revoke.error ?? invites.error} />
    <h3>最近嘅邀請</h3>
    {invites.isPending && <p>載入中…</p>}
    {invites.data?.length === 0 && <p className="hint">未有邀請，建立一條連結開始。</p>}
    {invites.data?.map(i => {
      const status = i.revokedAt ? '已撤銷' : i.redeemedAt ? '已使用' : new Date(i.expiresAt) < new Date() ? '已過期' : '可使用'
      return <div className="invite-row" key={i.id}><div><strong>{status}</strong><small>{new Date(i.expiresAt).toLocaleString('zh-HK')} 到期</small></div>{status === '可使用' && <button disabled={revoke.isPending} onClick={() => revoke.mutate(i.id)}>撤銷</button>}</div>
    })}
  </Dialog>
}
