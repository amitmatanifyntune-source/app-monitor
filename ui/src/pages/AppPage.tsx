import { Link, useParams, useSearchParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { errorText, get, type CheckState, type Issues, type Summary } from '../api'
import { Badge, num, time } from '../ui'
import { RefreshPicker, TimePicker } from '../dash'
import Dashboard from '../tabs/Dashboard'
import Overview from '../tabs/Overview'
import Apis from '../tabs/Apis'
import Database from '../tabs/Database'
import IssuesTab from '../tabs/Issues'
import ChecksTab from '../tabs/Checks'
import HostTab from '../tabs/Host'
import Setup from '../tabs/Setup'

export type Tab = 'dashboard' | 'overview' | 'apis' | 'database' | 'issues' | 'checks' | 'host' | 'setup'
const TABS: [Tab, string][] = [['dashboard', 'Dashboard'], ['overview', 'Highlights'], ['apis', 'APIs'], ['database', 'Database'], ['issues', 'Errors & issues'],
  ['checks', 'Checks'], ['host', 'Servers'], ['setup', 'Setup']]

export default function AppPage({ admin }: { admin: boolean }) {
  const { code = '' } = useParams()
  const [params, setParams] = useSearchParams()
  const tab = (params.get('tab') as Tab) || 'dashboard'
  // a new tab keeps the time range and refresh, as Grafana keeps them across dashboards
  const go = (t: string, extra?: Record<string, string>) => setParams(p => {
    const n = new URLSearchParams({ tab: t, ...(extra ?? {}) })
    for (const k of ['range', 'refresh']) { const v = p.get(k); if (v != null) n.set(k, v) }
    return n
  })
  const s = useQuery({ queryKey: ['summary', code], queryFn: () => get<Summary>(`/apps/${code}/summary`), refetchInterval: 10000 })
  // Same keys as the tabs' default views, so the counts and the tabs share one request.
  const issues = useQuery({ queryKey: ['issues', code, 24, ''], queryFn: () => get<Issues>(`/apps/${code}/issues?hours=24`), refetchInterval: 15000 })
  const checks = useQuery({ queryKey: ['checks', code], queryFn: () => get<CheckState[]>(`/apps/${code}/checks`), refetchInterval: 15000 })
  const t = issues.data?.totals ?? {}
  const errors24h = (t.ERROR ?? 0) + (t.LOG_ERROR ?? 0) + (t.CHECK_FAIL ?? 0)
  const failing = (checks.data ?? []).filter(c => !c.ok).length
  const count: Partial<Record<Tab, [string, string]>> = {
    ...(errors24h ? { issues: [num(errors24h), `${num(errors24h)} errors in 24 hours`] as [string, string] } : {}),
    ...(failing ? { checks: [`${failing} failing`, `${failing} failing`] as [string, string] } : {}),
  }
  const st = s.data?.status

  return <>
    <div className="top">
      <div>
        <div className="crumbs"><Link to="/">All apps</Link> › {s.data?.environment ?? '…'}</div>
        <div className="row"><h2>{s.data?.name ?? code}</h2>{s.data && <Badge status={s.data.status} />}</div>
        {s.data && (st === 'DOWN' || st === 'DEGRADED') && s.data.reasons.length > 0 &&
          <p className={`reason ${st}`}>{s.data.reasons.join('. ')}</p>}
        <div className="sub">{s.data ? `${code}. Status updated ${time(s.data.at)}.` : ' '}</div>
      </div>
      <span className="spacer" />
      {tab !== 'setup' && <div className="row"><TimePicker /><RefreshPicker /></div>}
    </div>
    <nav className="tabbar" aria-label="App sections">
      {TABS.map(([k, l]) =>
        <button key={k} type="button" className={tab === k ? 'on' : ''} aria-current={tab === k ? 'page' : undefined} onClick={() => go(k)}>
          {l}{count[k] && <span className="cnt" aria-label={count[k]![1]}>{count[k]![0]}</span>}</button>)}
    </nav>
    <div className="work">
      {s.isError && <div className="err">{errorText(s.error)}</div>}
      {tab === 'dashboard' && <Dashboard code={code} summary={s.data} go={go} />}
      {tab === 'overview' && <Overview code={code} summary={s.data} go={go} />}
      {tab === 'apis' && <Apis code={code} />}
      {tab === 'database' && <Database code={code} />}
      {tab === 'issues' && <IssuesTab code={code} requestId={params.get('rid') ?? ''} go={go} />}
      {tab === 'checks' && <ChecksTab code={code} />}
      {tab === 'host' && <HostTab code={code} />}
      {tab === 'setup' && <Setup code={code} admin={admin} />}
    </div>
  </>
}
