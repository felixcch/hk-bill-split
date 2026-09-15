import { useEffect, useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { Session } from '@supabase/supabase-js'
import { ApiError, authClient, config, requestClient } from './api'
import type { Request } from './api'
import type { Config, Group, User } from './types'
import { Avatar, Brand, Dialog, ErrorMessage, Field } from './ui'
import { GroupDashboard } from './GroupDashboard'

const pendingInvite = new URLSearchParams(window.location.hash.slice(1)).get('invite')
if (pendingInvite) {
  sessionStorage.setItem('bill-invite', pendingInvite)
  window.history.replaceState(null, '', window.location.pathname + window.location.search)
}

export default function App() {
  const settings = useQuery({ queryKey: ['config'], queryFn: config, retry: 2, staleTime: Infinity })
  if (settings.isPending) return <main className="loading"><Brand /><p>準備緊你嘅分帳簿…</p></main>
  if (settings.error) return <main className="loading"><ErrorMessage error={settings.error} /><button onClick={() => void settings.refetch()}>重新連接</button></main>
  return <Auth settings={settings.data} />
}

function Auth({ settings }: { settings: Config }) {
  const client = useMemo(() => authClient(settings), [settings])
  const [session, setSession] = useState<Session | null>(null)
  const [ready, setReady] = useState(settings.demo)
  const [error, setError] = useState<Error | null>(null)
  const [demoId, setDemoId] = useState(() => sessionStorage.getItem('bill-demo-user') ?? '')
  useEffect(() => {
    if (!client) return
    let alive = true
    void client.auth.getSession().then(({ data, error: failure }) => {
      if (alive) { setSession(data.session); setError(failure); setReady(true) }
    }).catch((failure: unknown) => { if (alive) { setError(failure instanceof Error ? failure : new Error('登入失敗。')); setReady(true) } })
    const { data } = client.auth.onAuthStateChange((_event, next) => { if (alive) setSession(next) })
    return () => { alive = false; data.subscription.unsubscribe() }
  }, [client])
  const userId = settings.demo ? demoId : session?.user.id ?? ''
  const api = useMemo(() => requestClient(async () => (await client?.auth.getSession())?.data.session?.access_token ?? null, settings.demo ? demoId : undefined), [client, demoId, settings.demo])
  if (!ready) return <main className="loading"><Brand /><p>登入中…</p></main>
  if (!userId) return <main className="welcome">
    <header><Brand /><span className="pill">為香港朋友而設</span></header>
    <div className="welcome-grid"><section><p className="eyebrow">SHARED MOMENTS, SIMPLE SPLITS</p><h1>開心一齊玩，<br /><em>條數輕鬆夾。</em></h1><p className="intro">食飯、旅行、日常小聚。記低每筆開支，清楚知道邊個欠邊個，朋友之間唔使再估估吓。</p>
      <ErrorMessage error={error} />
      {settings.demo ? <div className="demo-login"><p>本機示範模式 · 揀一位成員試用</p>{settings.demoUsers.map(u => <button key={u.id} onClick={() => { sessionStorage.setItem('bill-demo-user', u.id); setDemoId(u.id) }}>{u.name}登入 →</button>)}</div>
        : <button className="primary big" onClick={() => { void client?.auth.signInWithOAuth({ provider: 'google', options: { redirectTo: window.location.origin + '/' } }).then(({ error: failure }) => setError(failure)) }}>使用 Google 登入 →</button>}
      <p className="hint">HKD 分帳 · 私人群組 · 收款確認</p></section>
      <section className="welcome-card"><span className="eyebrow">一餐飯，一筆清楚嘅帳</span><h2>聚餐後，三步搞掂。</h2><ol><li><span>01</span><div><strong>建立你哋嘅群組</strong><p>分享邀請連結，朋友登入就加入到。</p></div></li><li><span>02</span><div><strong>記低邊個埋單</strong><p>平均分、指定金額或百分比，都得。</p></div></li><li><span>03</span><div><strong>還款後確認收妥</strong><p>錢另行轉帳，條數喺呢度對清楚。</p></div></li></ol><div className="card-footer">少啲計數，多啲相聚。</div></section>
    </div><footer>一齊夾 · 只記錄開支及外部還款，唔會代你轉帳。</footer>
  </main>
  return <Account key={userId} api={api} userId={userId} settings={settings} logout={() => {
    if (settings.demo) { sessionStorage.removeItem('bill-demo-user'); setDemoId('') }
    else void client?.auth.signOut().then(({ error: failure }) => { if (failure) setError(failure) })
  }} />
}

function Account({ api, userId, settings, logout }: { api: Request; userId: string; settings: Config; logout: () => void }) {
  const user = useQuery({ queryKey: ['me', userId], queryFn: () => api<User>('/me'), retry: false })
  const [name, setName] = useState(settings.demoUsers.find(u => u.id === userId)?.name ?? '')
  const qc = useQueryClient()
  const profile = useMutation({ mutationFn: () => api<User>('/me', 'PUT', { displayName: name }), onSuccess: u => qc.setQueryData(['me', userId], u) })
  if (user.error instanceof ApiError && user.error.code === 'PROFILE_REQUIRED') return <main className="onboarding"><Brand /><h1>朋友點稱呼你？</h1><p className="muted">呢個稱呼會顯示喺你加入嘅群組。</p><form onSubmit={e => { e.preventDefault(); profile.mutate() }}><Field label="你嘅稱呼"><input required maxLength={60} value={name} onChange={e => setName(e.target.value)} autoFocus /></Field><ErrorMessage error={profile.error} /><button className="primary full" disabled={profile.isPending || !name.trim()}>開始分帳 →</button></form><button className="text-button" onClick={logout}>登出</button></main>
  if (user.isPending) return <main className="loading"><p>載入資料…</p></main>
  if (user.error || !user.data) return <main className="loading"><ErrorMessage error={user.error} /><button onClick={() => void user.refetch()}>重試</button><button onClick={logout}>重新登入</button></main>
  return <Workspace api={api} user={user.data} demo={settings.demo} logout={logout} />
}

function Workspace({ api, user, demo, logout }: { api: Request; user: User; demo: boolean; logout: () => void }) {
  const qc = useQueryClient()
  const groups = useQuery({ queryKey: ['groups', user.id], queryFn: () => api<Group[]>('/groups') })
  const [selected, setSelected] = useState(() => new URLSearchParams(window.location.search).get('group') ?? '')
  const [creating, setCreating] = useState(false)
  const [name, setName] = useState('')
  const [invite, setInvite] = useState(() => sessionStorage.getItem('bill-invite') ?? '')
  const choose = (id: string) => {
    setSelected(id)
    const url = new URL(window.location.href)
    url.searchParams.set('group', id)
    window.history.replaceState(null, '', url)
  }
  const create = useMutation({
    mutationFn: () => api<Group>('/groups', 'POST', { name }),
    onSuccess: g => { void qc.invalidateQueries({ queryKey: ['groups'] }); choose(g.id); setCreating(false); setName('') },
  })
  const join = useMutation({
    mutationFn: () => api<Group>('/invitations/accept', 'POST', { token: invite }),
    onSuccess: g => { sessionStorage.removeItem('bill-invite'); setInvite(''); void qc.invalidateQueries({ queryKey: ['groups'] }); choose(g.id) },
  })
  const active = groups.data?.find(g => g.id === selected) ?? groups.data?.[0]
  return <div className="app-shell">
    <header className="topbar"><Brand /><div className="account"><span className="pill">{demo ? '本機示範' : 'HKD'}</span><Avatar name={user.displayName} /><strong>{user.displayName}</strong><button className="text-button" onClick={logout}>登出</button></div></header>
    <aside className="sidebar"><div className="sidebar-title"><span>我嘅群組</span><button className="icon-button" aria-label="新增群組" onClick={() => { create.reset(); setCreating(true) }}>+</button></div>
      {groups.isPending && <p>載入中…</p>}
      <ErrorMessage error={groups.error} />
      {groups.error && <button onClick={() => void groups.refetch()}>重試</button>}
      <nav>{groups.data?.map(g => <button className={`group-nav ${g.id === active?.id ? 'active' : ''}`} key={g.id} onClick={() => choose(g.id)}><span className="group-icon" aria-hidden="true">{g.name.slice(0, 1)}</span><span>{g.name}<small>港幣分帳簿</small></span></button>)}</nav>
      <button className="new-group" onClick={() => { create.reset(); setCreating(true) }}>+ 建立新群組</button>
      <div className="sidebar-note"><strong>相聚開心，條數清楚。</strong><p>所有結餘由已記錄嘅支出同已確認還款計算。</p></div>
    </aside>
    <main className="workspace">
      {invite && <section className="join-banner"><div><strong>朋友邀請你加入分帳群組</strong><p>加入後，群組成員可以睇到你嘅稱呼。</p></div><div className="actions"><button disabled={join.isPending} onClick={() => { sessionStorage.removeItem('bill-invite'); setInvite('') }}>略過</button><button className="primary" disabled={join.isPending} onClick={() => join.mutate()}>{join.isPending ? '加入中…' : '接受邀請'}</button></div><ErrorMessage error={join.error} /></section>}
      {active ? <GroupDashboard key={`${user.id}:${active.id}`} api={api} user={user} group={active} />
        : !groups.isPending && !groups.error && <section className="empty-welcome"><p className="eyebrow">YOUR SHARED EXPENSES</p><h1>由下一次相聚開始。</h1><p>建立一個群組，邀請朋友加入，再記低第一筆支出。</p><button className="primary big" onClick={() => setCreating(true)}>+ 建立第一個群組</button><div className="empty-steps"><span>01 建立群組</span><span>02 邀請朋友</span><span>03 一齊分帳</span></div></section>}
      <footer>以 HKD 記帳 · 付款需要喺 FPS、PayMe、銀行或現金另行完成。</footer>
    </main>
    {creating && <Dialog title="建立新群組" onClose={() => { if (!create.isPending) setCreating(false) }}><form onSubmit={e => { e.preventDefault(); create.mutate() }}><Field label="群組名稱"><input required maxLength={80} value={name} onChange={e => setName(e.target.value)} placeholder="例如：週五飯腳、日本之旅" autoFocus /></Field><p className="hint">建立後就可以產生邀請連結。群組使用港幣 HKD。</p><ErrorMessage error={create.error} /><div className="dialog-actions"><button type="button" disabled={create.isPending} onClick={() => setCreating(false)}>取消</button><button className="primary" disabled={create.isPending || !name.trim()}>{create.isPending ? '建立中…' : '建立群組'}</button></div></form></Dialog>}
  </div>
}
