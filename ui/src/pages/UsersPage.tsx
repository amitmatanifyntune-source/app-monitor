import { useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { api, errorText, get, type AppCard } from '../api'
import { Pan } from '../ui'

interface Row { id: number; username: string; displayName: string; role: 'ADMIN' | 'VIEWER'; active: boolean; apps: string }

export default function UsersPage() {
  const qc = useQueryClient()
  const users = useQuery({ queryKey: ['users'], queryFn: () => get<Row[]>('/admin/users') })
  const apps = useQuery({ queryKey: ['apps'], queryFn: () => get<AppCard[]>('/apps') })
  const [f, setF] = useState({ username: '', displayName: '', password: '', role: 'VIEWER', apps: [] as string[] })
  const [error, setError] = useState<string | null>(null)
  const refresh = () => qc.invalidateQueries({ queryKey: ['users'] })

  async function create() {
    setError(null)
    try { await api.post('/admin/users', f); setF({ username: '', displayName: '', password: '', role: 'VIEWER', apps: [] }); refresh() } catch (e) { setError(errorText(e)) }
  }
  async function update(id: number, body: Record<string, unknown>) {
    setError(null)
    try { await api.patch(`/admin/users/${id}`, body); refresh() } catch (e) { setError(errorText(e)) }
  }

  return <>
    <div className="top"><div><h2>Users &amp; access</h2><div className="sub">Administrators see and manage every app. Viewers see only the apps ticked for them.</div></div></div>
    <div className="work">
      {error && <div className="err">{error}</div>}
      <Pan title="Users" flush>
        <div className="scroll"><table><thead><tr><th>User</th><th>Role</th><th>Apps</th><th>Active</th></tr></thead>
          <tbody>{(users.data ?? []).map(u => {
            const has: string[] = JSON.parse(u.apps || '[]')
            return <tr key={u.id}>
              <td><b>{u.displayName}</b><div className="mono small muted">{u.username}</div></td>
              <td><select value={u.role} onChange={e => update(u.id, { role: e.target.value })} style={{ width: 120 }}><option>VIEWER</option><option>ADMIN</option></select></td>
              <td>{u.role === 'ADMIN' ? <span className="muted small">all apps</span> : <div className="row">{(apps.data ?? []).map(a =>
                <label key={a.code} className="small" style={{ margin: 0 }}><input type="checkbox" checked={has.includes(a.code)}
                  onChange={e => update(u.id, { apps: e.target.checked ? [...has, a.code] : has.filter(x => x !== a.code) })} /> {a.name}</label>)}</div>}</td>
              <td><input type="checkbox" checked={u.active} onChange={e => update(u.id, { active: e.target.checked })} /></td>
            </tr>
          })}</tbody></table></div>
      </Pan>
      <Pan title="Add a user">
        <div className="grid2">
          <div className="field"><label>Username</label><input value={f.username} onChange={e => setF({ ...f, username: e.target.value })} /></div>
          <div className="field"><label>Name</label><input value={f.displayName} onChange={e => setF({ ...f, displayName: e.target.value })} /></div>
          <div className="field"><label>Password (10+ characters)</label><input type="password" autoComplete="new-password" value={f.password} onChange={e => setF({ ...f, password: e.target.value })} /></div>
          <div className="field"><label>Role</label><select value={f.role} onChange={e => setF({ ...f, role: e.target.value })}><option>VIEWER</option><option>ADMIN</option></select></div>
        </div>
        {f.role === 'VIEWER' && <div className="field"><label>Apps this viewer may see</label><div className="row">{(apps.data ?? []).map(a =>
          <label key={a.code} className="small" style={{ margin: 0 }}><input type="checkbox" checked={f.apps.includes(a.code)}
            onChange={e => setF({ ...f, apps: e.target.checked ? [...f.apps, a.code] : f.apps.filter(x => x !== a.code) })} /> {a.name}</label>)}</div></div>}
        <button className="btn pri" disabled={!f.username || f.password.length < 10} onClick={create}>Add user</button>
      </Pan>
    </div>
  </>
}
