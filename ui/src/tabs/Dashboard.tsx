import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { get, type CheckState, type DbSample, type Endpoint, type HostView, type Issues, type Summary, type Traffic } from '../api'
import { BarGauge, Panel, Row, StateTimeline, StatValue, TimeSeries, colorOf, rangeText, useRange, type Steps } from '../dash'
import { STATUS_TEXT, mb, ms, num } from '../ui'
import { DbSpecTable, ServerSpecTable } from '../spec'

const C = { green: 'var(--grn)', orange: 'var(--amb)', red: 'var(--red)', blue: 'var(--blu)', yellow: 'var(--yel)', purple: 'var(--pur)', text: 'var(--strong)' }
const pct = (v: number) => `${v < 10 ? v.toFixed(v < 1 ? 2 : 1) : Math.round(v)}%`
const rate = (v: number) => v >= 100 ? num(Math.round(v)) : v.toFixed(v < 10 ? 2 : 1).replace(/\.?0+$/, '') || '0'
const ROWS = ['Overview', 'Traffic and latency', 'Errors', 'Endpoints', 'Checks', 'Servers', 'Database', 'Specifications'] as const

/** One page with everything about an app for the chosen time range, laid out like a Grafana dashboard. */
export default function Dashboard({ code, summary, go }: { code: string; summary?: Summary; go: (t: string, e?: Record<string, string>) => void }) {
  const { range, every } = useRange()
  const hours = Math.max(1, Math.ceil(range / 60))
  const opts = { refetchInterval: every }
  const traffic = useQuery({ queryKey: ['traffic', code, range], queryFn: () => get<Traffic>(`/apps/${code}/traffic?minutes=${range}`), ...opts })
  const endpoints = useQuery({ queryKey: ['endpoints', code, range], queryFn: () => get<Endpoint[]>(`/apps/${code}/endpoints?minutes=${range}`), ...opts })
  const issues = useQuery({ queryKey: ['issues', code, hours, ''], queryFn: () => get<Issues>(`/apps/${code}/issues?hours=${hours}`), ...opts })
  const checks = useQuery({ queryKey: ['checks', code], queryFn: () => get<CheckState[]>(`/apps/${code}/checks`), ...opts })
  const host = useQuery({ queryKey: ['host', code, Math.min(range, 1440)], queryFn: () => get<HostView[]>(`/apps/${code}/host?minutes=${Math.min(range, 1440)}`), ...opts })
  const db = useQuery({ queryKey: ['db', code], queryFn: () => get<DbSample[]>(`/apps/${code}/db`), ...opts })
  const [closed, setClosed] = useState<Set<string>>(new Set())
  const row = (t: typeof ROWS[number]) => ({ open: !closed.has(t), onToggle: () => setClosed(s => { const n = new Set(s); n.has(t) ? n.delete(t) : n.add(t); return n }) })

  const slow = summary?.slowMs ?? 2000
  const series = traffic.data?.series ?? []
  // the server groups long ranges into steps of several minutes; show everything per minute so ranges compare
  const step = series.length > 1 ? Math.max(1, (series[1].at - series[0].at) / 60000) : 1
  const pts = series.map(p => ({ at: p.at, rpm: p.requests / step, epm: p.errors / step, refpm: p.refused / step, slowpm: p.slow / step,
    erate: p.requests ? (100 * p.errors) / p.requests : 0, p95: p.p95, avg: p.avg }))
  const w = traffic.data?.range
  const errRate = w?.requests ? (100 * w.errors) / w.requests : 0
  const hasTraffic = pts.some(p => p.rpm > 0)
  const failing = (checks.data ?? []).filter(c => !c.ok)
  const now = Date.now()

  const latency: Steps = { base: C.green, steps: [[slow, C.orange], [slow * 2, C.red]] }
  const errSteps: Steps = { base: C.green, steps: [[0.0001, C.yellow], [1, C.orange], [5, C.red]] }
  const statusColor = { UP: C.green, DEGRADED: C.orange, DOWN: C.red, NO_DATA: 'var(--faint)' }[summary?.status ?? 'NO_DATA']
  const noTraffic = <div className="empty">No requests in {rangeText(range).toLowerCase()}. Traffic appears once an agent reads the access log.</div>

  const totals = issues.data?.totals ?? {}
  const issueRows = ([['ERROR', 'Server errors', C.red], ['LOG_ERROR', 'Log errors', C.orange], ['CHECK_FAIL', 'Failed checks', C.red],
    ['SLOW', 'Slow requests', C.yellow], ['REFUSED', 'Refused', C.blue]] as const).map(([k, l, c]) => ({ label: l, value: totals[k] ?? 0, text: num(totals[k] ?? 0), color: c }))
  const issueMax = Math.max(1, ...issueRows.map(r => r.value))

  const eps = endpoints.data ?? []
  const totalMs = eps.reduce((s, e) => s + e.totalMs, 0)
  const byTime = [...eps].sort((a, b) => b.totalMs - a.totalMs).slice(0, 10)
  const bySlow = [...eps].filter(e => e.requests > 0).sort((a, b) => b.p95 - a.p95).slice(0, 10)

  return <div className="dash">
    {/* ------------------------------------------------ overview */}
    <Row title="Overview" note={rangeText(range)} {...row('Overview')} />
    {row('Overview').open && <>
      <Panel title="Status" w={4} h={4} info="Down: a health check fails or no agent reported for 3 minutes. Degraded: recent server errors, another failing check, or slow responses.">
        <StatValue value={STATUS_TEXT[summary?.status ?? 'NO_DATA']} color={statusColor}
          sub={summary?.reasons?.[0] ?? (summary?.status === 'UP' ? 'Agents reporting, checks passing' : undefined)} />
      </Panel>
      <Panel title="Throughput" w={4} h={4} info="Requests per minute across all agents, averaged over the range.">
        <StatValue value={rate(w?.perMinute ?? 0)} unit=" req/min" color={C.blue} sub={`${num(w?.requests)} requests`} spark={pts.map(p => p.rpm)} />
      </Panel>
      <Panel title="Error rate" w={4} h={4} info="Server errors (5xx) as a share of all requests.">
        <StatValue value={w?.requests ? pct(errRate) : '—'} color={w?.requests ? colorOf(errRate, errSteps) : 'var(--faint)'}
          sub={`${num(w?.errors)} server errors`} spark={pts.map(p => p.erate)} />
      </Panel>
      <Panel title="95% within" w={4} h={4} info={`95% of requests finished within this time. Orange above the app's slow threshold (${ms(slow)}), red above twice it.`}>
        <StatValue value={w?.requests ? ms(w.p95) : '—'} color={w?.requests ? colorOf(w.p95, latency) : 'var(--faint)'}
          sub={w?.requests ? `median ${ms(w.p50)}, max ${ms(w.max)}` : undefined} spark={pts.map(p => p.p95)} />
      </Panel>
      <Panel title="Slow requests" w={4} h={4} info={`Requests slower than ${ms(slow)}.`}>
        <StatValue value={num(w?.slow)} color={w?.slow ? C.orange : C.green} sub={w?.requests ? `${pct((100 * w.slow) / w.requests)} of requests` : undefined}
          spark={pts.map(p => p.slowpm)} />
      </Panel>
      <Panel title="Checks" w={4} h={4} info="HTTP and SQL checks run by the agents. A failing health check marks the app Down.">
        <StatValue value={checks.data?.length ? `${checks.data.length - failing.length}/${checks.data.length}` : '—'} unit={checks.data?.length ? ' passing' : undefined}
          color={!checks.data?.length ? 'var(--faint)' : failing.some(c => c.health) ? C.red : failing.length ? C.orange : C.green}
          sub={failing.length ? failing.map(c => c.name).join(', ') : checks.data?.length ? 'All passing' : 'No checks yet'} />
      </Panel>
    </>}

    {/* ------------------------------------------------ traffic */}
    <Row title="Traffic and latency" {...row('Traffic and latency')} />
    {row('Traffic and latency').open && <>
      <Panel title="Requests per minute" w={12} h={9}>
        {hasTraffic ? <TimeSeries points={pts} format={rate} series={[{ key: 'rpm', label: 'Requests', color: C.green },
          { key: 'epm', label: 'Server errors', color: C.red }, { key: 'refpm', label: 'Refused (4xx)', color: C.blue }]} /> : noTraffic}
      </Panel>
      <Panel title="Response time" w={12} h={9} info="95% of requests finished within the yellow line; the green line is the average. The dashed line is the slow threshold.">
        {hasTraffic ? <TimeSeries points={pts} format={v => ms(v)} threshold={{ value: slow, color: C.red, label: `Slow above ${ms(slow)}` }}
          series={[{ key: 'p95', label: '95% within', color: C.yellow }, { key: 'avg', label: 'Average', color: C.green }]} /> : noTraffic}
      </Panel>
    </>}

    {/* ------------------------------------------------ errors */}
    <Row title="Errors" {...row('Errors')} />
    {row('Errors').open && <>
      <Panel title="Error rate" w={10} h={8}>
        {hasTraffic ? <TimeSeries points={pts} format={pct} legend="list" series={[{ key: 'erate', label: 'Server errors, % of requests', color: C.red, fill: 0.2 }]} /> : noTraffic}
      </Panel>
      <Panel title={`Issues by type, last ${hours} h`} w={6} h={8}>
        <BarGauge rows={issueRows.map(r => ({ ...r, max: issueMax }))} />
      </Panel>
      <Panel title="Top issues" w={8} h={8} flush right={<button className="btn sm" onClick={() => go('issues')}>Open Errors &amp; issues</button>}>
        {!issues.data?.groups.length ? <div className="empty">No issues in the last {hours} h.</div> : <div className="scroll"><table>
          <thead><tr><th>Type</th><th>Where</th><th className="num">Count</th></tr></thead>
          <tbody>{issues.data.groups.slice(0, 8).map((g, i) => <tr key={i} className={g.lastRequestId ? 'click' : ''} tabIndex={g.lastRequestId ? 0 : undefined}
            onClick={() => g.lastRequestId && go('issues', { rid: g.lastRequestId })} onKeyDown={e => e.key === 'Enter' && g.lastRequestId && go('issues', { rid: g.lastRequestId })}>
            <td><span className={`tag ${g.type}`}>{g.type}</span></td>
            <td className="mono small" title={g.lastMessage}>{g.method ? `${g.method} ` : ''}{g.path ?? '—'}</td><td className="num">{num(g.count)}</td></tr>)}</tbody>
        </table></div>}
      </Panel>
    </>}

    {/* ------------------------------------------------ endpoints */}
    <Row title="Endpoints" note={`${eps.length} seen`} {...row('Endpoints')} />
    {row('Endpoints').open && <>
      <Panel title="Share of server time" w={10} h={9} info="Which endpoints the server spends its time on: requests × duration.">
        <BarGauge rows={byTime.map(e => {
          const share = totalMs ? (100 * e.totalMs) / totalMs : 0
          return { label: `${e.method} ${e.pattern}`, value: share, max: (100 * (byTime[0]?.totalMs ?? 1)) / (totalMs || 1), text: pct(share), color: C.blue }
        })} />
      </Panel>
      <Panel title="Slowest endpoints" w={14} h={9} flush right={<button className="btn sm" onClick={() => go('apis')}>All endpoints</button>}>
        {!bySlow.length ? <div className="empty">No API requests in this range.</div> : <div className="scroll"><table>
          <thead><tr><th>Endpoint</th><th className="num">Requests</th><th className="num">Errors</th><th className="num">Median</th><th className="num">95% within</th><th className="num">Max</th></tr></thead>
          <tbody>{bySlow.map(e => <tr key={e.method + e.pattern}>
            <td><span className="tag">{e.method}</span> <span className="mono small">{e.pattern}</span></td>
            <td className="num">{num(e.requests)}</td>
            <td className="num" style={{ color: e.errors ? C.red : undefined }}>{num(e.errors)}</td>
            <td className="num">{ms(e.p50)}</td>
            <td className="num" style={{ color: colorOf(e.p95, latency) }}>{ms(e.p95)}</td>
            <td className="num">{ms(e.max)}</td></tr>)}</tbody>
        </table></div>}
      </Panel>
    </>}

    {/* ------------------------------------------------ checks */}
    <Row title="Checks" note={checks.data?.length ? `${failing.length} failing` : undefined} {...row('Checks')} />
    {row('Checks').open && (() => {
      const lanes = (checks.data ?? []).map(c => ({ label: c.name, runs: c.recent.map(r => ({ at: Date.parse(r.at), ok: r.ok })),
        note: c.failCount24h ? `${c.failCount24h} failed, 24 h` : 'none failed, 24 h' }))
      const from = Math.min(now - 3600_000, ...lanes.flatMap(l => l.runs.map(r => r.at)))
      return <Panel title="Check results" w={24} h={Math.max(3, Math.min(10, lanes.length + 2))} info="Each block is one run, green when it passed, red when it failed: the last 60 runs of each check.">
        <StateTimeline lanes={lanes} from={from} to={now} />
      </Panel>
    })()}

    {/* ------------------------------------------------ servers */}
    <Row title="Servers" note={range > 1440 ? 'last 24 hours (host samples are kept 7 days, charted up to 24 hours)' : undefined} {...row('Servers')} />
    {row('Servers').open && (!host.data?.length ? <Panel title="Servers" w={24} h={3}><div className="empty">No agents yet.</div></Panel> : host.data.map(hv => {
      const l = hv.latest
      const hp = hv.series.map(p => ({ at: Date.parse(p.at), cpu: p.cpu == null ? 0 : p.cpu * 100, mem: p.memTotalMb ? (100 * (p.memUsedMb ?? 0)) / p.memTotalMb : 0 }))
      const level = (v: number) => colorOf(v, { base: C.green, steps: [[80, C.orange], [92, C.red]] })
      const memPct = l?.memTotalMb ? (100 * (l.memUsedMb ?? 0)) / l.memTotalMb : 0
      const res = l ? [
        { label: `CPU (${l.cores ?? '?'} cores)`, value: (l.cpu ?? 0) * 100, max: 100, text: pct((l.cpu ?? 0) * 100), color: level((l.cpu ?? 0) * 100) },
        { label: 'Memory', value: memPct, max: 100, text: pct(memPct), color: level(memPct), title: `${mb(l.memUsedMb)} of ${mb(l.memTotalMb)}` },
        ...(l.disks ?? []).map(d => { const u = d.totalMb ? 100 - (100 * d.freeMb) / d.totalMb : 0
          return { label: `Disk ${d.path}`, value: u, max: 100, text: pct(u), color: level(u), title: `${mb(d.freeMb)} free of ${mb(d.totalMb)}` } }),
      ] : []
      return [
        <Panel key={hv.agentId + 'ts'} title={`${hv.agentName}${hv.host ? `, ${hv.host}` : ''}: CPU and memory`} w={16} h={8}>
          {hp.length > 1 ? <TimeSeries points={hp} format={v => `${Math.round(v)}%`} yMax={100}
            series={[{ key: 'cpu', label: 'CPU', color: C.green }, { key: 'mem', label: 'Memory', color: C.purple }]} />
            : <div className="empty">This agent has not sent host statistics yet.</div>}
        </Panel>,
        <Panel key={hv.agentId + 'bg'} title="Now" w={8} h={8} info="Latest sample: orange above 80%, red above 92%.">
          <BarGauge rows={res} />
          {l && (l.processes?.length || l.ports?.length) ? <div className="row" style={{ marginTop: 8 }}>
            {(l.processes ?? []).map(p => <span key={p.name} className={`badge ${p.up ? 'good' : 'bad'}`}>{p.name}</span>)}
            {(l.ports ?? []).map(p => <span key={p.name} className={`badge ${p.up ? 'good' : 'bad'}`}>{p.name} :{p.port}</span>)}
          </div> : null}
        </Panel>,
      ]
    }))}

    {/* ------------------------------------------------ database */}
    <Row title="Database" {...row('Database')} />
    {row('Database').open && (!db.data?.length
      ? <Panel title="Database" w={24} h={3}><div className="empty">No database snapshot yet. Add a db section with a read-only user to agent.json.</div></Panel>
      : db.data.map(d => {
        const s = d.snapshot
        const conn = s.maxConnections ? (100 * (s.connections ?? 0)) / s.maxConnections : 0
        return [
          <Panel key={d.agentId + 'c'} title="Connections" w={6} h={4} info="Open connections against the server's limit.">
            <StatValue value={num(s.connections)} unit={s.maxConnections ? ` / ${s.maxConnections}` : undefined}
              color={colorOf(conn, { base: C.green, steps: [[70, C.orange], [85, C.red]] })} sub={s.maxConnections ? `${pct(conn)} of the limit` : undefined} />
          </Panel>,
          <Panel key={d.agentId + 'h'} title="Cache hit" w={6} h={4} info={d.engine === 'mysql' ? 'InnoDB buffer pool hit ratio' : 'Shared buffers hit ratio'}>
            <StatValue value={s.cacheHitPct == null ? '—' : pct(s.cacheHitPct)} color={s.cacheHitPct == null ? 'var(--faint)' : s.cacheHitPct < 90 ? C.red : s.cacheHitPct < 99 ? C.orange : C.green} />
          </Panel>,
          <Panel key={d.agentId + 'l'} title="Locks waiting" w={6} h={4}>
            <StatValue value={num(s.locksWaiting)} color={s.locksWaiting ? C.red : C.green} sub={s.deadlocks != null ? `${num(s.deadlocks)} deadlocks since start` : undefined} />
          </Panel>,
          <Panel key={d.agentId + 's'} title={`${d.engine} ${d.version}`} w={6} h={4} info="Database size, and how long the probe's ping took.">
            <StatValue value={mb(s.sizeMb)} color={C.text} sub={`ping ${ms(s.pingMs)}`} />
          </Panel>,
          <Panel key={d.agentId + 'q'} title="Heaviest statements" w={24} h={8} flush right={<button className="btn sm" onClick={() => go('database')}>Database details</button>}>
            {!s.statements?.available ? <div className="empty">{s.statements?.note ?? 'Statement statistics are not available.'}</div> : <div className="scroll"><table>
              <thead><tr><th>Statement</th><th className="num">Calls</th><th className="num">Mean</th><th className="num">Max</th><th className="num">Share</th></tr></thead>
              <tbody>{s.statements.rows.slice(0, 8).map((r, i) => <tr key={i}><td><div className="q" style={{ maxHeight: 40, overflow: 'hidden' }}>{r.query}</div></td>
                <td className="num">{num(r.calls)}</td><td className="num" style={{ color: colorOf(r.meanMs, latency) }}>{ms(r.meanMs)}</td><td className="num">{ms(r.maxMs)}</td>
                <td className="num">{r.pct == null ? '—' : `${r.pct}%`}</td></tr>)}</tbody>
            </table></div>}
          </Panel>,
        ]
      }))}

    {/* ------------------------------------------------ specifications */}
    <Row title="Specifications" note="what the app and database servers are" {...row('Specifications')} />
    {row('Specifications').open && <>
      {(host.data ?? []).map(hv => <Panel key={hv.agentId + 'spec'} title={`App server: ${hv.agentName}`} w={12} h={12} flush
        info="Sent by the agent when it starts and every hour.">
        <div className="scroll"><ServerSpecTable spec={hv.spec} specAt={hv.specAt} /></div>
      </Panel>)}
      {(db.data ?? []).map(d => <Panel key={d.agentId + 'dbspec'} title={`Database server: ${d.engine}`} w={12} h={12} flush
        info="Read from the database's own settings by the agent's read-only monitoring user, hourly.">
        <div className="scroll"><DbSpecTable engine={d.engine} version={d.version} spec={d.snapshot.spec}
          appServer={host.data?.find(h => h.agentId === d.agentId)?.spec?.hostname} /></div>
      </Panel>)}
      {!host.data?.length && !db.data?.length && <Panel title="Specifications" w={24} h={3}><div className="empty">No agents yet.</div></Panel>}
    </>}
  </div>
}
