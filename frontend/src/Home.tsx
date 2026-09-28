import { useState } from 'react'
import type { FormEvent } from 'react'
import { useMutation } from '@tanstack/react-query'
import { api } from './api'
import { forgetGroup, rememberGroup, useRecentGroups } from './store'
import { emojis } from './types'
import { Brand, ErrorMessage } from './ui'

export function Home({ navigate }: { navigate: (path: string) => void }) {
  const recent = useRecentGroups()
  const [creating, setCreating] = useState(false)
  return <main className="home">
    <header className="home-hero">
      <Brand />
      <h1>三步搞掂朋友分帳</h1>
      <p>唔使登記、唔使裝 app。開一個群組，將連結傳去 WhatsApp 群，大家都可以記支出、睇結餘。</p>
      <ol className="steps">
        <li><span>1</span>開群組，填埋朋友名</li>
        <li><span>2</span>分享私人連結</li>
        <li><span>3</span>記支出，一眼睇到邊個要還幾多</li>
      </ol>
      {!creating && <button className="primary big" onClick={() => setCreating(true)}>＋ 開新群組</button>}
    </header>
    {creating && <CreateGroup navigate={navigate} onCancel={() => setCreating(false)} />}
    {recent.length > 0 && <section className="card">
      <h2>我嘅群組</h2>
      <ul className="recent">
        {recent.map(g => <li key={g.code}>
          <a href={`/g/${g.code}`} onClick={e => { e.preventDefault(); navigate(`/g/${g.code}`) }}>
            <span className="group-emoji" aria-hidden="true">{g.emoji}</span><span className="grow">{g.name}</span><span aria-hidden="true">›</span>
          </a>
          <button className="icon-button" aria-label={`移除 ${g.name}`} onClick={() => { if (confirm(`從呢部裝置移除「${g.name}」？群組資料唔會刪除，有連結就可以再開。`)) forgetGroup(g.code) }}>×</button>
        </li>)}
      </ul>
      <p className="muted small">群組只儲存喺呢部裝置嘅瀏覽器。有連結先入得，所以記得保存連結。</p>
    </section>}
    <footer className="home-foot">
      <p>金額以港幣（HK$）計算，唔會處理實際付款——大家用轉數快／PayMe 還錢後，撳「已還錢」記一筆就得。</p>
    </footer>
  </main>
}

function CreateGroup({ navigate, onCancel }: { navigate: (path: string) => void; onCancel: () => void }) {
  const [name, setName] = useState('')
  const [emoji, setEmoji] = useState(emojis[0])
  const [members, setMembers] = useState<string[]>(['', ''])
  const create = useMutation({
    mutationFn: () => api.createGroup(name.trim(), emoji, members.map(m => m.trim()).filter(Boolean)),
    onSuccess: group => {
      rememberGroup(group)
      navigate(`/g/${group.code}?new=1`)
    },
  })
  const names = members.map(m => m.trim()).filter(Boolean)
  const duplicate = new Set(names.map(n => n.toLowerCase())).size !== names.length
  const submit = (e: FormEvent) => {
    e.preventDefault()
    if (!name.trim() || !names.length || duplicate) return
    create.mutate()
  }
  const update = (i: number, value: string) => setMembers(list => {
    const next = list.map((m, j) => (j === i ? value : m))
    if (i === list.length - 1 && value.trim()) next.push('')
    return next
  })
  return <form className="card create" onSubmit={submit} aria-label="開新群組">
    <h2>開新群組</h2>
    <label className="field"><span>群組名</span>
      <input value={name} onChange={e => setName(e.target.value)} maxLength={80} placeholder="例如：日本之旅、週末 BBQ" required autoFocus />
    </label>
    <div className="field"><span>圖示</span>
      <div className="chips emoji-chips" role="radiogroup" aria-label="圖示">
        {emojis.map(x => <button type="button" key={x} role="radio" aria-checked={emoji === x} className={emoji === x ? 'chip on' : 'chip'} onClick={() => setEmoji(x)}>{x}</button>)}
      </div>
    </div>
    <div className="field"><span>邊個一齊夾？<small>先填自己，朋友之後都可以自己加名</small></span>
      <div className="member-inputs">
        {members.map((m, i) => <div key={i} className="member-input">
          <input value={m} onChange={e => update(i, e.target.value)} maxLength={40} placeholder={i === 0 ? '你嘅名' : `朋友 ${i}`} aria-label={`成員 ${i + 1}`} />
          {members.length > 1 && m && <button type="button" className="icon-button" aria-label={`移除成員 ${i + 1}`} onClick={() => setMembers(list => list.filter((_, j) => j !== i))}>×</button>}
        </div>)}
      </div>
      {duplicate && <div className="error" role="alert">成員名唔可以重複。</div>}
    </div>
    <ErrorMessage error={create.error} />
    <div className="actions">
      <button type="button" className="ghost" onClick={onCancel}>取消</button>
      <button type="submit" className="primary" disabled={create.isPending || !name.trim() || !names.length || duplicate}>
        {create.isPending ? '建立中…' : `建立群組${names.length ? `（${names.length} 人）` : ''}`}
      </button>
    </div>
  </form>
}
