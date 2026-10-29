import { useState } from 'react'
import { NavLink, Navigate, Route, Routes, useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { get, setToken, token, type AppCard, type User } from './api'
import { STATUS_TEXT } from './ui'
import { applyTheme, theme, type Theme } from './theme'
import Login from './pages/Login'
import AppsPage from './pages/AppsPage'
import AppPage from './pages/AppPage'
import UsersPage from './pages/UsersPage'
import AuditPage from './pages/AuditPage'
import HelpPage from './pages/HelpPage'

export default function App() {
  if (!token()) return <Routes><Route path="*" element={<Login />} /></Routes>
  return <Shell />
}

function Shell() {
  const nav = useNavigate()
  const me = useQuery({ queryKey: ['me'], queryFn: () => get<User>('/auth/me') })
  const apps = useQuery({ queryKey: ['apps'], queryFn: () => get<AppCard[]>('/apps'), refetchInterval: 15000 })
  const admin = me.data?.role === 'ADMIN'
  const [mode, setMode] = useState<Theme>(theme())
  const flip = () => { const t: Theme = mode === 'dark' ? 'light' : 'dark'; applyTheme(t); setMode(t) }
  return <div className="shell">
    <aside className="rail">
      <div className="brand">
        <div className="logo"><svg width="16" height="16" viewBox="0 0 32 32" aria-hidden><path d="M3 17h6l3-8 5 15 3-9 2 2h7" fill="none" stroke="#fff" strokeWidth="3.5" strokeLinecap="round" strokeLinejoin="round" /></svg></div>
        <div><h1>App Monitor</h1><p>Fyntune, self-hosted</p></div>
      </div>
      <nav className="nav">
        <div className="ng">Monitoring</div>
        <NavLink end to="/" className={({ isActive }) => `nv${isActive ? ' on' : ''}`}>All apps</NavLink>
        {(apps.data ?? []).map(a => <NavLink key={a.code} to={`/apps/${a.code}`} className={({ isActive }) => `nv${isActive ? ' on' : ''}`}>
          <span className={`dot ${a.status}`} role="img" aria-label={STATUS_TEXT[a.status]} />{a.name}</NavLink>)}
        <div className="ng">Guide</div>
        <NavLink to="/help" className={({ isActive }) => `nv${isActive ? ' on' : ''}`}>Help: install &amp; track</NavLink>
        {admin && <>
          <div className="ng">Administration</div>
          <NavLink to="/admin/users" className={({ isActive }) => `nv${isActive ? ' on' : ''}`}>Users &amp; access</NavLink>
          <NavLink to="/admin/audit" className={({ isActive }) => `nv${isActive ? ' on' : ''}`}>Audit log</NavLink>
        </>}
      </nav>
      <div className="railfoot">
        <div className="who"><b>{me.data?.displayName}</b>{me.data?.role === 'ADMIN' ? 'Administrator' : 'Viewer'}</div>
        <button className="btn sm" onClick={flip} aria-label={`Switch to ${mode === 'dark' ? 'light' : 'dark'} theme`} title="Switch theme">{mode === 'dark' ? '☀' : '☾'}</button>
        <button className="btn sm" onClick={() => { setToken(null); nav('/login'); location.reload() }}>Sign out</button>
      </div>
    </aside>
    <main className="main">
      <Routes>
        <Route path="/" element={<AppsPage admin={admin} />} />
        <Route path="/apps/:code" element={<AppPage admin={admin} />} />
        <Route path="/help" element={<HelpPage admin={admin} />} />
        {admin && <Route path="/admin/users" element={<UsersPage />} />}
        {admin && <Route path="/admin/audit" element={<AuditPage />} />}
        <Route path="/login" element={<Navigate to="/" replace />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </main>
  </div>
}
