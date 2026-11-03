import { useQuery } from '@tanstack/react-query'
import { get, type HostView } from '../api'
import { useRange } from '../dash'
import { ServerSpecTable } from '../spec'
import { Bar, Empty, LineChart, Pan, Stat, ago, mb, num } from '../ui'
import type { AgentSelf } from '../api'

export default function HostTab({ code }: { code: string }) {
  const { range, every } = useRange()
  const m = Math.min(range, 1440)
  const q = useQuery({ queryKey: ['host', code, m], queryFn: () => get<HostView[]>(`/apps/${code}/host?minutes=${m}`), refetchInterval: every })
  if (!q.data?.length) return <Pan title="Servers"><Empty>{q.isLoading ? 'Loading…' : 'No agents yet.'}</Empty></Pan>
  return <>{q.data.map(h => {
    const l = h.latest
    const memPct = l?.memTotalMb ? (100 * (l.memUsedMb ?? 0)) / l.memTotalMb : 0
    const points = h.series.map(p => ({ ...p, at: Date.parse(p.at), cpuPct: p.cpu == null ? 0 : Math.round(p.cpu * 100),
      memPct: p.memTotalMb ? Math.round((100 * (p.memUsedMb ?? 0)) / p.memTotalMb) : 0 }))
    return <Pan key={h.agentId} title={`${h.agentName}${h.host ? ` · ${h.host}` : ''}`} right={<span className="small">{l ? `sample ${ago(l.at)}` : 'no samples'}</span>}>
      {!l ? <Empty>This agent has not sent host statistics yet.</Empty> : <>
        <div className="grid2" style={{ marginBottom: 14 }}>
          <Stat k="CPU" v={l.cpu == null ? '—' : `${Math.round(l.cpu * 100)}%`} s={`${l.cores ?? '?'} cores${l.load1 != null ? ` · load ${l.load1}` : ''}`} />
          <Stat k="Memory" v={`${mb(l.memUsedMb)} / ${mb(l.memTotalMb)}`} s={<Bar pct={memPct} level={memPct >= 92 ? 'bad' : memPct >= 80 ? 'warn' : undefined} />} />
          {(l.disks ?? []).map(d => {
            const used = d.totalMb ? 100 - (100 * d.freeMb) / d.totalMb : 0
            return <Stat key={d.path} k={`Disk ${d.path}`} v={`${mb(d.freeMb)} free`} s={<Bar pct={used} level={used >= 92 ? 'bad' : used >= 80 ? 'warn' : undefined} />} />
          })}
        </div>
        {(l.processes?.length || l.ports?.length) ? <div className="row" style={{ marginBottom: 14 }}>
          {(l.processes ?? []).map(p => <span key={p.name} className={`badge ${p.up ? 'good' : 'bad'}`}>{p.name}{p.up ? ` · ${p.count}` : ' · not running'}</span>)}
          {(l.ports ?? []).map(p => <span key={p.name} className={`badge ${p.up ? 'good' : 'bad'}`}>{p.name} :{p.port}{p.up ? '' : ' · closed'}</span>)}
        </div> : null}
        {l.agent && <AgentOverhead a={l.agent} memTotalMb={l.memTotalMb} cores={l.cores} />}
        {points.length > 1 && <LineChart points={points} format={v => `${Math.round(v)}%`} height={130}
          series={[{ key: 'cpuPct', label: 'CPU %', color: '--accent' }, { key: 'memPct', label: 'Memory %', color: '--amb' }]} />}
      </>}
      <div className="kv-h" style={{ paddingLeft: 0 }}>Specification</div>
      <div className="pan" style={{ margin: '4px 0 0' }}><ServerSpecTable spec={h.spec} specAt={h.specAt} /></div>
    </Pan>
  })}</>
}

/** What the monitoring agent itself uses on this server, so the client can see its cost next to the app's. */
function AgentOverhead({ a, memTotalMb, cores }: { a: AgentSelf; memTotalMb?: number; cores?: number }) {
  const cpuPct = a.cpu == null ? null : a.cpu * 100
  const memPct = memTotalMb && a.rssMb != null ? (100 * a.rssMb) / memTotalMb : null
  const up = a.uptimeSec == null ? '' : a.uptimeSec >= 86400 ? `${Math.floor(a.uptimeSec / 86400)} d` : a.uptimeSec >= 3600 ? `${Math.floor(a.uptimeSec / 3600)} h` : `${Math.floor(a.uptimeSec / 60)} min`
  return <>
    <div className="kv-h" style={{ paddingLeft: 0 }}>Monitoring agent overhead</div>
    <div className="grid2" style={{ marginBottom: 14 }}>
      <Stat k="Agent CPU" v={cpuPct == null ? '—' : `${cpuPct < 0.1 ? cpuPct.toFixed(2) : cpuPct.toFixed(1)}%`}
        s={`of the whole server${cores ? ` (${cores} cores)` : ''}`} tone={cpuPct != null && cpuPct >= 5 ? 'warn' : undefined} />
      <Stat k="Agent memory" v={mb(a.rssMb)} s={`${memPct == null ? '' : `${memPct.toFixed(1)}% of RAM · `}heap ${mb(a.heapUsedMb)} of ${mb(a.heapMaxMb)}`} />
      <Stat k="Agent disk (spool)" v={a.spoolKb == null ? '—' : a.spoolKb >= 1024 ? mb(Math.round(a.spoolKb / 1024)) : `${a.spoolKb} KB`}
        s={`${a.threads ?? '?'} threads${up ? ` · running ${up}` : ''}`} tone={(a.spoolKb ?? 0) > 50 * 1024 ? 'warn' : undefined} />
      <Stat k="OpenTelemetry (last minute)" v={a.otlp ? `${num(a.spansReceived ?? 0)} spans` : 'off'}
        s={a.otlp ? `${num(a.tracesKept ?? 0)} traces kept${a.spansDropped ? ` · ${num(a.spansDropped)} spans over the limit` : ''}` : 'receiver not listening'}
        tone={a.spansDropped ? 'warn' : undefined} />
    </div>
  </>
}
