import { useQuery } from '@tanstack/react-query'
import { get, type HostView } from '../api'
import { useRange } from '../dash'
import { ServerSpecTable } from '../spec'
import { Bar, Empty, LineChart, Pan, Stat, ago, mb } from '../ui'

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
        {points.length > 1 && <LineChart points={points} format={v => `${Math.round(v)}%`} height={130}
          series={[{ key: 'cpuPct', label: 'CPU %', color: '--accent' }, { key: 'memPct', label: 'Memory %', color: '--amb' }]} />}
      </>}
      <div className="kv-h" style={{ paddingLeft: 0 }}>Specification</div>
      <div className="pan" style={{ margin: '4px 0 0' }}><ServerSpecTable spec={h.spec} specAt={h.specAt} /></div>
    </Pan>
  })}</>
}
