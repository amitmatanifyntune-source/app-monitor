import { useQuery } from '@tanstack/react-query'
import { get, type DbSample } from '../api'
import { Bar, Empty, Pan, Stat, ago, mb, ms, num } from '../ui'
import { DbSpecTable } from '../spec'

export default function Database({ code }: { code: string }) {
  const q = useQuery({ queryKey: ['db', code], queryFn: () => get<DbSample[]>(`/apps/${code}/db`), refetchInterval: 20000 })
  if (q.isLoading) return <Pan title="Database"><Empty>Loading…</Empty></Pan>
  if (!q.data?.length) return <Pan title="Database"><Empty>No database snapshot yet. Add a <span className="mono">db</span> section with a read-only
    monitoring user to the agent's agent.json (README → Database monitoring).</Empty></Pan>
  return <>{q.data.map(d => <One key={d.agentId} d={d} />)}</>
}

function One({ d }: { d: DbSample }) {
  const s = d.snapshot
  const connPct = s.maxConnections ? (100 * (s.connections ?? 0)) / s.maxConnections : 0
  return <>
    {!s.up && <div className="err">{s.summary}</div>}
    {s.up && s.reasons?.length > 0 && <div className="pan"><div className="pan-b">{s.reasons.map((r, i) => <div key={i} style={{ color: 'var(--amb)' }}>⚠ {r}</div>)}</div></div>}
    <div className="grid2" style={{ marginBottom: 16 }}>
      <Stat k="Engine" v={<span style={{ fontSize: 16 }}>{d.engine} {d.version}</span>} s={`snapshot ${ago(d.at)} · ping ${ms(s.pingMs)}`} />
      <Stat k="Size" v={mb(s.sizeMb)} />
      <Stat k="Connections" v={`${num(s.connections)}${s.maxConnections ? ` / ${s.maxConnections}` : ''}`}
        s={<Bar pct={connPct} level={connPct >= 85 ? 'bad' : connPct >= 70 ? 'warn' : undefined} />} />
      <Stat k="Cache hit" v={s.cacheHitPct == null ? '—' : `${s.cacheHitPct}%`} s={d.engine === 'mysql' ? 'InnoDB buffer pool' : 'shared buffers'} />
      <Stat k="Locks waiting" v={num(s.locksWaiting)} s={s.deadlocks != null ? `${num(s.deadlocks)} deadlocks since start` : undefined} />
    </div>

    <Pan title="Server and settings" flush><DbSpecTable engine={d.engine} version={d.version} spec={s.spec} /></Pan>

    {s.connectionStates && <Pan title="Connections by state">
      <div className="row">{Object.entries(s.connectionStates).map(([k, v]) => <span key={k} className="tag">{k}: {v}</span>)}</div>
    </Pan>}

    <Pan title={`Running now · ${s.running?.length ?? 0}`} flush>
      {!s.running?.length ? <Empty>Nothing running at the time of the snapshot.</Empty> :
        <div className="scroll"><table><thead><tr><th>Id</th><th>User</th><th>State</th><th className="num">Running for</th><th>Query (masked)</th></tr></thead>
          <tbody>{s.running.map(r => <tr key={r.id}><td className="mono">{r.id}</td><td>{r.user}</td><td>{r.state}{r.wait ? ` · ${r.wait}` : ''}</td>
            <td className="num" style={{ color: r.ms > 30000 ? 'var(--red)' : undefined }}>{ms(r.ms)}</td><td><div className="q">{r.query}</div></td></tr>)}</tbody></table></div>}
    </Pan>

    <Pan title="Heaviest statements" flush>
      {!s.statements?.available ? <Empty>{s.statements?.note ?? 'Not available.'}</Empty> :
        <div className="scroll"><table><thead><tr><th>Statement</th><th className="num">Calls</th><th className="num">Total</th><th className="num">Mean</th>
          <th className="num">Max</th><th className="num">Rows</th><th className="num">Share</th></tr></thead>
          <tbody>{s.statements.rows.map((r, i) => <tr key={i}><td><div className="q">{r.query}</div></td><td className="num">{num(r.calls)}</td>
            <td className="num">{ms(r.totalMs)}</td><td className="num">{ms(r.meanMs)}</td><td className="num">{ms(r.maxMs)}</td><td className="num">{num(r.rows)}</td>
            <td className="num">{r.pct == null ? '—' : `${r.pct}%`}</td></tr>)}</tbody></table></div>}
    </Pan>

    <Pan title="Largest tables" flush>
      {!s.tables?.length ? <Empty>No tables.</Empty> :
        <div className="scroll"><table><thead><tr><th>Table</th><th className="num">Rows</th><th className="num">Size</th>
          {d.engine === 'postgres' && <><th className="num">Full scans</th><th className="num">Index scans</th><th className="num">Dead rows</th></>}
          <th>Note</th></tr></thead>
          <tbody>{s.tables.map(t => <tr key={t.table}><td className="mono">{t.table}</td><td className="num">{num(t.rows)}</td><td className="num">{mb(t.sizeKb / 1024)}</td>
            {d.engine === 'postgres' && <><td className="num">{num(t.seqScans)}</td><td className="num">{num(t.idxScans)}</td><td className="num">{num(t.deadRows)}</td></>}
            <td className="small" style={{ color: t.hint ? 'var(--amb)' : undefined }}>{t.hint ?? ''}</td></tr>)}</tbody></table></div>}
    </Pan>
  </>
}
