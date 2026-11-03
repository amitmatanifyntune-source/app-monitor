import { Fragment, useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { errorText, get, type TraceDetail, type TraceRow, type TraceSpan } from '../api'
import { rangeText, useRange } from '../dash'
import { Empty, Pan, Seg, ago, ms, time } from '../ui'

type Filter = 'all' | 'error' | 'slow'
const KIND = ['', 'internal', 'server', 'client', 'producer', 'consumer']
const REASON: Record<TraceRow['reason'], [string, string]> = { error: ['bad', 'failed'], slow: ['warn', 'slow'], sample: ['info', 'sample'] }

/** Traces the agents kept (failed, slow, or sampled), and one trace as a waterfall. */
export default function Traces({ code, traceId, go }: { code: string; traceId: string; go: (t: string, extra?: Record<string, string>) => void }) {
  const { range, every } = useRange()
  const [filter, setFilter] = useState<Filter>('all')
  const [q, setQ] = useState('')
  const list = useQuery({ queryKey: ['traces', code, range, filter, q], queryFn: () =>
    get<TraceRow[]>(`/apps/${code}/traces?minutes=${range}&filter=${filter}${q.trim() ? `&q=${encodeURIComponent(q.trim())}` : ''}`), refetchInterval: every })
  if (traceId) return <Waterfall code={code} id={traceId} back={() => go('traces')} />
  const rows = list.data ?? []

  return <Pan title={`${rows.length} trace${rows.length === 1 ? '' : 's'}${rows.length === 200 ? ' (newest 200)' : ''}`} flush right={<div className="row">
    <Seg label="Show" value={filter} onChange={setFilter} options={[['all', 'All kept'], ['error', 'Failed'], ['slow', 'Slow']]} />
    <input placeholder="Name, trace id or request id" aria-label="Search traces" value={q} onChange={e => setQ(e.target.value)} style={{ width: 230, height: 30 }} />
  </div>}>
    {list.isError ? <div className="err">{errorText(list.error)}</div> : rows.length === 0 ? <Empty>{list.isLoading ? 'Loading…' : <>
      No traces in {rangeText(range).toLowerCase()}{filter !== 'all' || q ? ' that match' : ''}. Agents keep every failed or slow request and a small sample of the
      rest, up to a limit a minute. Traces come from OpenTelemetry: send them to <span className="mono">http://127.0.0.1:4318</span> on the app server.</>}</Empty> :
      <div className="scroll"><table>
        <thead><tr><th>When</th><th>Request</th><th className="num">Duration</th><th className="num">Spans</th><th>Status</th><th>Kept because</th><th>Request id</th></tr></thead>
        <tbody>{rows.map(t => <tr key={t.id} className="click" onClick={() => go('traces', { trace: String(t.id) })} tabIndex={0}
          onKeyDown={e => { if (e.key === 'Enter') go('traces', { trace: String(t.id) }) }}>
          <td className="small" title={time(t.at)}>{ago(t.at)}</td>
          <td><span className="mono">{t.name}</span>{t.service && <span className="small muted"> · {t.service}</span>}</td>
          <td className="num" style={{ color: t.reason === 'slow' ? 'var(--amb)' : undefined }}>{ms(t.durationMs)}</td>
          <td className="num">{t.spanCount}</td>
          <td style={{ color: t.error ? 'var(--red)' : undefined }}>{t.status ?? (t.error ? 'error' : 'ok')}</td>
          <td><span className={`badge ${REASON[t.reason][0]}`}>{REASON[t.reason][1]}</span></td>
          <td className="mono small">{t.requestId ?? '—'}</td>
        </tr>)}</tbody>
      </table></div>}
  </Pan>
}

function Waterfall({ code, id, back }: { code: string; id: string; back: () => void }) {
  const q = useQuery({ queryKey: ['trace', code, id], queryFn: () => get<TraceDetail>(`/apps/${code}/traces/${id}`) })
  const [open, setOpen] = useState<string | null>(null)
  const rows = useMemo(() => order(q.data?.spans ?? []), [q.data])
  const t = q.data
  const totalUs = Math.max(1, ...rows.map(r => r.s.startUs + r.s.durUs))
  const header = <button type="button" className="btn" onClick={back}>← All traces</button>
  if (q.isError) return <Pan title="Trace" right={header}><div className="err">{errorText(q.error)}</div></Pan>
  if (!t) return <Pan title="Trace" right={header}><Empty>Loading…</Empty></Pan>
  const dbUs = rows.filter(r => r.s.dep === 'db').reduce((s, r) => s + r.s.durUs, 0)
  const httpUs = rows.filter(r => r.s.dep === 'http').reduce((s, r) => s + r.s.durUs, 0)

  return <Pan title={<><span className="mono">{t.name}</span> · {ms(t.durationMs)}</>} right={header}>
    <p className="small muted" style={{ marginTop: 0 }}>
      {time(t.at)} · {t.spanCount} spans{t.spanCount > rows.length ? ` (first ${rows.length} shown)` : ''} · status {t.status ?? (t.error ? 'error' : 'ok')}
      {t.service ? ` · ${t.service}` : ''} · trace <span className="mono">{t.traceId}</span>{t.requestId ? <> · request <span className="mono">{t.requestId}</span></> : null}
      {' '}· database calls {ms(dbUs / 1000)}, external HTTP {ms(httpUs / 1000)} (summed, may overlap)
    </p>
    <div className="scroll"><table className="waterfall">
      <thead><tr><th style={{ width: '38%' }}>Span</th><th className="num" style={{ width: 80 }}>Duration</th><th>Timeline (0 – {ms(totalUs / 1000)})</th></tr></thead>
      <tbody>{rows.map(({ s, depth }) => {
        const left = (100 * s.startUs) / totalUs, width = Math.max(0.4, (100 * s.durUs) / totalUs)
        const color = s.error ? 'var(--red)' : s.dep === 'db' ? 'var(--blu)' : s.dep === 'http' ? 'var(--amb)' : 'var(--accent)'
        return <Fragment key={s.id}>
          <tr className="click" onClick={() => setOpen(open === s.id ? null : s.id)} tabIndex={0} aria-expanded={open === s.id}
            onKeyDown={e => { if (e.key === 'Enter') setOpen(open === s.id ? null : s.id) }}>
            <td style={{ paddingLeft: 8 + depth * 14 }}>
              <span className="mono" style={{ color: s.error ? 'var(--red)' : undefined }}>{s.name}</span>
              <span className="small muted"> {s.dep ? `${s.dep} · ${s.target}` : KIND[s.kind] ?? ''}</span>
            </td>
            <td className="num">{ms(s.durUs / 1000)}</td>
            <td><div style={{ position: 'relative', height: 12, background: 'var(--sunk)', borderRadius: 2 }}>
              <div title={`starts at ${ms(s.startUs / 1000)}`} style={{ position: 'absolute', left: `${left}%`, width: `${width}%`, top: 0, bottom: 0, background: color, borderRadius: 2 }} />
            </div></td>
          </tr>
          {open === s.id && <tr><td colSpan={3} style={{ background: 'var(--sunk)' }}>
            <div className="small">starts at {ms(s.startUs / 1000)} · {KIND[s.kind] || 'span'} · id <span className="mono">{s.id}</span></div>
            {s.attrs && Object.keys(s.attrs).length > 0 ? <table className="kv" style={{ marginTop: 6 }}><tbody>
              {Object.entries(s.attrs).map(([k, v]) => <tr key={k}><td className="small muted" style={{ width: 220 }}>{k}</td>
                <td className="mono small" style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{String(v)}</td></tr>)}
            </tbody></table> : <div className="small muted">No attributes kept for this span.</div>}
          </td></tr>}
        </Fragment>
      })}</tbody>
    </table></div>
    <p className="small muted">Bars: <span style={{ color: 'var(--blu)' }}>database</span>, <span style={{ color: 'var(--amb)' }}>external HTTP</span>,
      {' '}<span style={{ color: 'var(--accent-ink)' }}>app</span>, <span style={{ color: 'var(--red)' }}>failed</span>. SQL is shown without its values; query strings are removed.</p>
  </Pan>
}

/** Spans as a tree, each parent followed by its children in start order. */
function order(spans: TraceSpan[]): { s: TraceSpan; depth: number }[] {
  const ids = new Set(spans.map(s => s.id))
  const kids = new Map<string, TraceSpan[]>()
  const roots: TraceSpan[] = []
  for (const s of spans) {
    if (s.parent && ids.has(s.parent)) kids.set(s.parent, [...(kids.get(s.parent) ?? []), s])
    else roots.push(s)
  }
  const out: { s: TraceSpan; depth: number }[] = []
  const walk = (s: TraceSpan, depth: number) => {
    out.push({ s, depth })
    for (const c of (kids.get(s.id) ?? []).sort((a, b) => a.startUs - b.startUs)) walk(c, Math.min(depth + 1, 12))
  }
  for (const r of roots.sort((a, b) => a.startUs - b.startUs)) walk(r, 0)
  return out
}
