import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useQueries, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, errorText, get, type AppCard, type Traffic } from '../api'
import { Activity, Badge, Empty, ago, ms, num } from '../ui'
import { Panel, StatValue } from '../dash'

export default function AppsPage({ admin }: { admin: boolean }) {
  const q = useQuery({ queryKey: ['apps'], queryFn: () => get<AppCard[]>('/apps'), refetchInterval: 15000 })
  const [adding, setAdding] = useState(false)
  const apps = [...(q.data ?? [])].sort((a, b) => rank(a.status) - rank(b.status) || a.name.localeCompare(b.name))
  const attention = apps.filter(a => a.status === 'DOWN' || a.status === 'DEGRADED').length
  // The last hour per app, for the activity strip. Shares its cache with the Overview tab's 1 h view.
  const traffic = useQueries({ queries: apps.map(a => ({ queryKey: ['traffic', a.code, 60], queryFn: () => get<Traffic>(`/apps/${a.code}/traffic?minutes=60`),
    refetchInterval: 60000 })) })

  return <>
    <div className="top">
      <div>
        <h2>{!q.isSuccess ? 'All applications' : apps.length === 0 ? 'No applications yet'
          : attention ? `${attention} of ${apps.length} app${apps.length === 1 ? '' : 's'} need${attention === 1 ? 's' : ''} attention`
          : apps.every(a => a.status === 'UP') ? `All ${apps.length} app${apps.length === 1 ? ' is' : 's are'} up` : 'All applications'}</h2>
        <div className="sub">Problems first. Refreshes every 15 s.</div>
      </div>
      <span className="spacer" />
      {admin && <button className="btn pri" onClick={() => setAdding(true)}>Add an application</button>}
    </div>
    <div className="work">
      {q.isError && <div className="err">{errorText(q.error)}</div>}
      {q.isLoading && <div className="pan"><Empty>Loading applications…</Empty></div>}
      {q.isSuccess && apps.length === 0 && <div className="pan"><Empty>
        {admin ? <>Add an application, then install an agent on its server from the app's Setup tab.</> : <>No application has been shared with you yet. Ask an administrator for access.</>}
      </Empty></div>}
      {apps.length > 0 && <div className="dash">
        {([['Applications', apps.length, 'var(--strong)'], ['Up', apps.filter(a => a.status === 'UP').length, 'var(--grn)'],
          ['Degraded', apps.filter(a => a.status === 'DEGRADED').length, 'var(--amb)'], ['Down', apps.filter(a => a.status === 'DOWN').length, 'var(--red)']] as const)
          .map(([t, n, c]) => <Panel key={t} title={t} w={6} h={3}><StatValue value={n} color={n || t === 'Applications' ? c : 'var(--faint)'} /></Panel>)}
      </div>}
      {apps.length > 0 && <ul className="board">
        {apps.map((a, i) => {
          const series = traffic[i]?.data?.series
          return <li key={a.code}><Link to={`/apps/${a.code}`} className={`brow ${a.status}`}>
            <div className="b-name">
              <div className="row"><h4>{a.name}</h4><Badge status={a.status} /></div>
              <div className="b-meta">{a.environment}, {a.code}</div>
            </div>
            <div className="b-why">{a.reasons.length ? a.reasons.slice(0, 2).join('. ') : a.status === 'NO_DATA' ? 'Waiting for the first agent report' : `Last report ${ago(a.lastSeen)}`}</div>
            <div className="b-act">
              {series?.length ? <Activity points={series} label={`Requests per minute over the last hour for ${a.name}; red minutes had server errors`} /> : <div className="act-empty" />}
              <div className="b-axis"><span>1 h ago</span><span>now</span></div>
            </div>
            <dl className="b-nums">
              <div><dt>req/min</dt><dd>{num(a.perMinute)}</dd></div>
              <div><dt>95% within</dt><dd>{a.perMinute ? ms(a.p95) : '—'}</dd></div>
              <div className={a.errors24h ? 'bad' : ''}><dt>errors, 24 h</dt><dd>{num(a.errors24h)}</dd></div>
              <div><dt>agents</dt><dd>{a.agents}</dd></div>
            </dl>
          </Link></li>
        })}
      </ul>}
    </div>
    {adding && <NewApp onClose={() => setAdding(false)} />}
  </>
}

const rank = (s: string) => ({ DOWN: 0, DEGRADED: 1, UP: 2, NO_DATA: 3 } as Record<string, number>)[s] ?? 4

function NewApp({ onClose }: { onClose: () => void }) {
  const qc = useQueryClient()
  const nav = useNavigate()
  const [f, setF] = useState({ name: '', code: '', environment: 'production', description: '', slowMs: 2000 })
  const [error, setError] = useState<string | null>(null)
  const set = (k: keyof typeof f, v: string | number) => setF(x => ({ ...x, [k]: v,
    ...(k === 'name' && (!x.code || x.code === slug(x.name)) ? { code: slug(String(v)) } : {}) }))

  async function save() {
    try {
      await api.post('/admin/apps', { ...f, slowMs: Number(f.slowMs) })
      await qc.invalidateQueries({ queryKey: ['apps'] })
      nav(`/apps/${f.code}?tab=setup`)
    } catch (e) { setError(errorText(e)) }
  }

  return <div className="modal-bg" onClick={onClose}><div className="modal" onClick={e => e.stopPropagation()}>
    <h3>Add an application</h3>
    {error && <div className="err">{error}</div>}
    <div className="field"><label>Name</label><input autoFocus value={f.name} onChange={e => set('name', e.target.value)} placeholder="e.g. Client X portal (Laravel)" /></div>
    <div className="grid2">
      <div className="field"><label>Code</label><input value={f.code} onChange={e => set('code', e.target.value)} className="mono" />
        <div className="hint">Lower-case letters, digits and hyphens; used in links.</div></div>
      <div className="field"><label>Environment</label><select value={f.environment} onChange={e => set('environment', e.target.value)}>
        {['production', 'uat', 'staging', 'development'].map(x => <option key={x}>{x}</option>)}</select></div>
    </div>
    <div className="field"><label>Description (optional)</label><input value={f.description} onChange={e => set('description', e.target.value)} placeholder="Stack, owner, where it runs" /></div>
    <div className="field"><label>Slow request above (ms)</label><input type="number" value={f.slowMs} onChange={e => set('slowMs', e.target.value)} style={{ maxWidth: 160 }} /></div>
    <div className="row"><span className="spacer" /><button className="btn" onClick={onClose}>Cancel</button>
      <button className="btn pri" disabled={!f.name || !f.code} onClick={save}>Add and set up</button></div>
  </div></div>
}

const slug = (s: string) => s.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 40)
