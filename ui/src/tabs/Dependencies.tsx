import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { get, type Dependency } from '../api'
import { rangeText, useRange } from '../dash'
import { Bar, Empty, Pan, Seg, Stat, ago, ms, num } from '../ui'

type Kind = 'all' | Dependency['kind']
type Sort = 'totalMs' | 'calls' | 'p95' | 'errors' | 'max'
const KIND_TEXT: Record<Dependency['kind'], string> = { db: 'Database', http: 'HTTP API', queue: 'Queue', other: 'Other' }

/** Outbound calls from OpenTelemetry: what the app waits on (databases, external APIs, queues). */
export default function Dependencies({ code }: { code: string }) {
  const { range, every } = useRange()
  const [kind, setKind] = useState<Kind>('all')
  const [sort, setSort] = useState<Sort>('totalMs')
  const [filter, setFilter] = useState('')
  const q = useQuery({ queryKey: ['dependencies', code, range], queryFn: () => get<Dependency[]>(`/apps/${code}/dependencies?minutes=${range}`), refetchInterval: every })
  const all = q.data ?? []
  const rows = useMemo(() => all
    .filter(d => kind === 'all' || d.kind === kind)
    .filter(d => !filter || `${d.target} ${d.operation}`.toLowerCase().includes(filter.toLowerCase()))
    .sort((a, b) => (b[sort] as number) - (a[sort] as number)), [all, kind, sort, filter])
  const total = rows.reduce((s, d) => s + d.totalMs, 0)
  const targets = new Set(all.map(d => d.kind + d.target)).size
  const calls = all.reduce((s, d) => s + d.calls, 0), errors = all.reduce((s, d) => s + d.errors, 0)
  const slowest = [...all].sort((a, b) => b.p95 - a.p95)[0]
  const th = (k: Sort, l: string) => <th className="num" aria-sort={sort === k ? 'descending' : undefined}>
    <button type="button" className="th-sort" onClick={() => setSort(k)}>{l}{sort === k ? ' ↓' : ''}</button></th>

  if (!q.isLoading && all.length === 0) return <Pan title="Dependencies"><Empty>
    No outbound calls in {rangeText(range).toLowerCase()}. This tab fills from OpenTelemetry: send the app's traces to the agent with
    {' '}<span className="mono">OTEL_EXPORTER_OTLP_ENDPOINT=http://127.0.0.1:4318</span> (see Help: install &amp; track, “Traces and dependencies”).
  </Empty></Pan>

  return <>
    <div className="grid2" style={{ marginBottom: 8 }}>
      <Stat k="Targets" v={num(targets)} s="databases, APIs and queues called" />
      <Stat k="Calls" v={num(calls)} s={rangeText(range)} />
      <Stat k="Failed calls" v={num(errors)} tone={errors ? 'bad' : undefined} s={calls ? `${((100 * errors) / calls).toFixed(1)}% of calls` : undefined} />
      <Stat k="Slowest (95% within)" v={slowest ? ms(slowest.p95) : '—'} tone={slowest && slowest.p95 >= 2000 ? 'warn' : undefined}
        s={slowest ? <span className="mono">{slowest.target}</span> : undefined} />
    </div>
    <Pan title={`${rows.length} dependenc${rows.length === 1 ? 'y' : 'ies'}`} flush right={<div className="row">
      <Seg label="Kind" value={kind} onChange={setKind} options={[['all', 'All'], ['db', 'Database'], ['http', 'HTTP'], ['queue', 'Queue'], ['other', 'Other']]} />
      <input placeholder="Filter, e.g. razorpay" aria-label="Filter dependencies" value={filter} onChange={e => setFilter(e.target.value)} style={{ width: 200, height: 30 }} />
    </div>}>
      {rows.length === 0 ? <Empty>{q.isLoading ? 'Loading…' : 'Nothing matches the filter.'}</Empty> :
        <div className="scroll"><table>
          <thead><tr><th>Target</th><th>Operation</th>{th('calls', 'Calls')}{th('errors', 'Errors')}<th className="num">Median</th>
            {th('p95', '95% within')}{th('max', 'Max')}{th('totalMs', 'Share of time')}<th>Last</th></tr></thead>
          <tbody>{rows.map(d => {
            const share = total ? (100 * d.totalMs) / total : 0
            return <tr key={d.kind + d.target + d.operation}>
              <td><span className="tag" title={KIND_TEXT[d.kind]}>{d.kind === 'db' ? 'DB' : d.kind === 'http' ? 'HTTP' : d.kind === 'queue' ? 'QUEUE' : 'OTHER'}</span>{' '}
                <span className="mono">{d.target}</span></td>
              <td className="mono">{d.operation || '—'}</td>
              <td className="num">{num(d.calls)}</td>
              <td className="num" style={{ color: d.errors ? 'var(--red)' : undefined }}>{num(d.errors)}</td>
              <td className="num">{ms(d.p50)}</td>
              <td className="num" style={{ color: d.p95 >= 2000 ? 'var(--amb)' : undefined }}>{ms(d.p95)}</td>
              <td className="num">{ms(d.max)}</td>
              <td className="num"><div className="row" style={{ justifyContent: 'flex-end', flexWrap: 'nowrap' }}><Bar pct={share} />{share.toFixed(1)}%</div></td>
              <td className="small muted">{ago(d.lastAt)}</td>
            </tr>
          })}</tbody>
        </table></div>}
    </Pan>
  </>
}
