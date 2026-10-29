import { Fragment, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { errorText, get, type Event, type Issues } from '../api'
import { useRange } from '../dash'
import { Empty, Pan, ago, ms, num, time } from '../ui'

const TYPES = ['', 'ERROR', 'CHECK_FAIL', 'LOG_ERROR', 'SLOW', 'REFUSED', 'APP']
const TYPE_TEXT: Record<string, string> = { '': 'All', ERROR: 'Server errors', CHECK_FAIL: 'Failed checks', LOG_ERROR: 'Log errors', SLOW: 'Slow', REFUSED: 'Refused', APP: 'Other' }

export default function IssuesTab({ code, requestId, go }: { code: string; requestId: string; go: (t: string, e?: Record<string, string>) => void }) {
  const { range, every } = useRange()
  const hours = Math.max(1, Math.ceil(range / 60))
  const [type, setType] = useState('')
  const [open, setOpen] = useState<number | null>(null)
  const [rid, setRid] = useState(requestId)
  const q = useQuery({ queryKey: ['issues', code, hours, type], queryFn: () => get<Issues>(`/apps/${code}/issues?hours=${hours}${type ? `&type=${type}` : ''}`), refetchInterval: every })

  return <>
    <Pan title="Trace a request" right={null}>
      <div className="row">
        <input className="mono" placeholder="Request id (X-Request-Id)" value={rid} onChange={e => setRid(e.target.value)} style={{ maxWidth: 320 }}
          onKeyDown={e => e.key === 'Enter' && go('issues', { rid })} />
        <button className="btn" onClick={() => go('issues', { rid })}>Trace</button>
        {requestId && <button className="btn sm" onClick={() => { setRid(''); go('issues') }}>Clear</button>}
      </div>
      {requestId && <Trace code={code} rid={requestId} />}
    </Pan>

    <div className="tabs">
      {TYPES.map(t => <button key={t} className={`chip${type === t ? ' on' : ''}`} aria-pressed={type === t} onClick={() => setType(t)}>
        {TYPE_TEXT[t]}{t && q.data?.totals[t] ? ` · ${num(q.data.totals[t])}` : ''}</button>)}
      <span className="spacer" />
      <span className="small muted">Last {hours} h</span>
    </div>

    <Pan title="Grouped by type and endpoint" flush>
      {!q.data?.groups.length ? <Empty>{q.isLoading ? 'Loading…' : 'No issues in this period.'}</Empty> :
        <div className="scroll"><table><thead><tr><th>Type</th><th>Where</th><th className="num">Count</th><th>Last</th><th>Latest message</th></tr></thead>
          <tbody>{q.data.groups.map((g, i) => <tr key={i} className={g.lastRequestId ? 'click' : ''} tabIndex={g.lastRequestId ? 0 : undefined}
            onClick={() => g.lastRequestId && go('issues', { rid: g.lastRequestId })}
            onKeyDown={e => e.key === 'Enter' && g.lastRequestId && go('issues', { rid: g.lastRequestId })}>
            <td><span className={`tag ${g.type}`}>{g.type}</span></td>
            <td className="mono small">{g.method ? `${g.method} ` : ''}{g.path ?? '—'}</td>
            <td className="num">{num(g.count)}</td><td className="small muted">{ago(g.lastAt)}</td>
            <td className="small">{g.lastMessage}</td></tr>)}</tbody></table></div>}
    </Pan>

    <Pan title="Most recent" flush>
      {!q.data?.recent.length ? <Empty>No issues in this period.</Empty> :
        <div className="scroll"><table><thead><tr><th>When</th><th>Type</th><th>Where</th><th className="num">Status</th><th className="num">Time</th><th>Message</th><th>Request id</th></tr></thead>
          <tbody>{q.data.recent.map(e => <Fragment key={e.id}>
            <tr className="click" tabIndex={0} aria-expanded={open === e.id} onClick={() => setOpen(open === e.id ? null : e.id)}
              onKeyDown={k => k.key === 'Enter' && setOpen(open === e.id ? null : e.id)}>
              <td className="small mono">{time(e.at)}</td><td><span className={`tag ${e.type}`}>{e.type}</span></td>
              <td className="mono small">{e.method ? `${e.method} ` : ''}{e.path ?? e.source}</td>
              <td className="num">{e.status ?? ''}</td><td className="num">{e.ms != null ? ms(e.ms) : ''}</td>
              <td className="small">{e.message}</td><td className="mono small">{e.requestId}</td></tr>
            {open === e.id && <tr><td colSpan={7}><EventDetail code={code} id={e.id} /></td></tr>}
          </Fragment>)}</tbody></table></div>}
    </Pan>
  </>
}

function EventDetail({ code, id }: { code: string; id: number }) {
  const q = useQuery({ queryKey: ['event', code, id], queryFn: () => get<Event>(`/apps/${code}/events/${id}`) })
  if (q.isError) return <div className="err">{errorText(q.error)}</div>
  if (!q.data) return <span className="muted small">Loading…</span>
  return <div className="small">
    <div className="row" style={{ marginBottom: 6 }}><span className="muted">Source</span> {q.data.source ?? '—'} <span className="muted">· agent</span> {q.data.agentId}
      {q.data.actor && <><span className="muted">· by</span> {q.data.actor}</>}</div>
    {q.data.detail ? <pre className="code">{q.data.detail}</pre> : <span className="muted">No further detail was reported.</span>}
  </div>
}

function Trace({ code, rid }: { code: string; rid: string }) {
  const q = useQuery({ queryKey: ['trace', code, rid], queryFn: () => get<Event[]>(`/apps/${code}/trace?requestId=${encodeURIComponent(rid)}`) })
  if (!q.data) return null
  if (!q.data.length) return <div className="muted small" style={{ marginTop: 10 }}>No events with request id <span className="mono">{rid}</span>. Only
    errors, refusals, slow requests and app-reported events are kept one by one.</div>
  return <div style={{ marginTop: 12 }}>{q.data.map(e => <div key={e.id} style={{ borderLeft: '2px solid var(--line2)', padding: '4px 0 10px 12px' }}>
    <div className="small"><span className="mono muted">{time(e.at)}</span> <span className={`tag ${e.type}`}>{e.type}</span> <span className="muted">{e.source}</span></div>
    <div>{e.method} <span className="mono">{e.path}</span> {e.status && `→ ${e.status}`} {e.ms != null && `in ${ms(e.ms)}`}</div>
    {e.message && <div className="small mid">{e.message}</div>}
    {e.detail && <pre className="code" style={{ marginTop: 6 }}>{e.detail}</pre>}
  </div>)}</div>
}
