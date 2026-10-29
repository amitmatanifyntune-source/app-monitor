import { useState, type FormEvent } from 'react'
import { api, errorText, setToken } from '../api'

export default function Login() {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      const r = await api.post<{ token: string }>('/auth/login', { username, password })
      setToken(r.data.token)
      location.assign('/')
    } catch (err) {
      setError(errorText(err))
      setBusy(false)
    }
  }

  return <div className="login">
    <form onSubmit={submit}>
      <div className="brand" style={{ padding: '0 0 18px' }}>
        <div className="logo"><svg width="18" height="18" viewBox="0 0 32 32" aria-hidden><path d="M3 17h6l3-8 5 15 3-9 2 2h7" fill="none" stroke="#fff" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" /></svg></div>
        <div><h1>Fyntune App Monitor</h1><p>Sign in to see your applications</p></div>
      </div>
      {error && <div className="err">{error}</div>}
      <div className="field"><label htmlFor="u">Username</label><input id="u" autoFocus autoComplete="username" value={username} onChange={e => setUsername(e.target.value)} /></div>
      <div className="field"><label htmlFor="p">Password</label><input id="p" type="password" autoComplete="current-password" value={password} onChange={e => setPassword(e.target.value)} /></div>
      <button className="btn pri" style={{ width: '100%' }} disabled={busy || !username || !password}>{busy ? 'Signing in…' : 'Sign in'}</button>
    </form>
  </div>
}
