import { useQuery } from '@tanstack/react-query'
import { get, type Summary, type Traffic } from '../api'
import { rangeText, useRange } from '../dash'
import { Empty, LineChart, Pan, Stat, ms, num } from '../ui'

export default function Overview({ code, summary, go }: { code: string; summary?: Summary; go: (t: string, e?: Record<string, string>) => void }) {
  const { range, every } = useRange()
  const t = useQuery({ queryKey: ['traffic', code, range], queryFn: () => get<Traffic>(`/apps/${code}/traffic?minutes=${range}`), refetchInterval: every })
  const w = t.data?.last5, r = t.data?.range

  return <>
    {(summary?.highlights ?? []).length > 0 && <Pan title="Key highlights">
      <div className="hl">
        {(summary?.highlights ?? []).map((h, i) =>
          <button key={i} className={`hl-i ${h.level}`} onClick={() => h.ref ? go('issues', { rid: h.ref }) : go(h.tab)}>
            <b>{h.title}</b><span>{h.text}</span>
          </button>)}
      </div>
    </Pan>}

    <div className="grid2" style={{ marginBottom: 16 }}>
      <Stat k="Requests / min (5 min)" v={num(w?.perMinute)} s={`${num(w?.requests)} requests`} />
      <Stat k="95% within (5 min)" v={w?.requests ? ms(w.p95) : '—'} s={`median ${w?.requests ? ms(w.p50) : '—'} · max ${w?.requests ? ms(w.max) : '—'}`} />
      <Stat k={`Server errors (${label(range)})`} v={num(r?.errors)} tone={r?.errors ? 'bad' : undefined} s={r?.requests ? `${((100 * (r.errors ?? 0)) / r.requests).toFixed(2)}% of requests` : undefined} />
      <Stat k={`Refused (${label(range)})`} v={num(r?.refused)} s="4xx other than 401/403/404" />
      <Stat k={`Slow (${label(range)})`} v={num(r?.slow)} tone={r?.slow ? 'warn' : undefined} s="over the app's slow threshold" />
    </div>

    <Pan title={`Traffic, ${rangeText(range).toLowerCase()}`}>
      {t.data && t.data.series.some(p => p.requests > 0)
        ? <div className="grid">
            <div><div className="small mid" style={{ marginBottom: 6 }}>Requests per {step(range)}</div>
              <LineChart points={t.data.series} series={[{ key: 'requests', label: 'Requests', color: '--accent' }, { key: 'errors', label: 'Errors', color: '--red' },
                { key: 'refused', label: 'Refused', color: '--blu' }]} /></div>
            <div><div className="small mid" style={{ marginBottom: 6 }}>Response time</div>
              <LineChart points={t.data.series} format={ms} series={[{ key: 'p95', label: '95% within', color: '--amb' }, { key: 'avg', label: 'Average', color: '--grn' }]} /></div>
          </div>
        : <Empty>No requests in this period. Traffic appears once an agent reads this app's access log; set one up in the Setup tab.</Empty>}
    </Pan>
  </>
}

const label = (m: number) => rangeText(m).replace('Last ', '')
const step = (m: number) => { const s = Math.max(1, Math.floor(m / 180)); return s === 1 ? 'minute' : `${s} minutes` }
