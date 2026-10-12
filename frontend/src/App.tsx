import { useCallback, useEffect, useState } from 'react'
import { GroupScreen } from './GroupScreen'
import { Home } from './Home'

function route(url: string) {
  const { pathname, searchParams } = new URL(url, location.origin)
  const match = /^\/g\/([A-Za-z0-9_-]{20,64})\/?$/.exec(pathname)
  return match ? { code: match[1], fresh: searchParams.get('new') === '1' } : null
}

export default function App() {
  const [href, setHref] = useState(() => location.href)
  useEffect(() => {
    const onChange = () => setHref(location.href)
    window.addEventListener('popstate', onChange)
    return () => window.removeEventListener('popstate', onChange)
  }, [])
  const navigate = useCallback((path: string) => {
    history.pushState(null, '', path)
    setHref(location.href)
    window.scrollTo(0, 0)
  }, [])
  const group = route(href)
  if (group) return <GroupScreen key={group.code} code={group.code} fresh={group.fresh} navigate={navigate} />
  return <Home navigate={navigate} />
}
