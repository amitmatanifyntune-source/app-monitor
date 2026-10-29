import { useQuery } from '@tanstack/react-query'
import { get, type CheckState } from '../api'
import { Empty, Pan, ago, ms, num } from '../ui'

export default function ChecksTab({ code }: { code: string }) {
  const q = useQuery({ queryKey: ['checks', code], queryFn: () => get<CheckState[]>(`/apps/${code}/checks`), refetchInterval: 15000 })
  if (!q.data?.length) return <Pan title="Checks"><Empty>{q.isLoading ? 'Loading…' : 'No check results yet. Add checks in Setup; the agent picks them up within 5 minutes.'}</Empty></Pan>
  return <Pan title={`Checks · ${q.data.filter(c => !c.ok).length} failing`} flush>
    <div className="scroll"><table>
      <thead><tr><th>Check</th><th>Now</th><th>Result</th><th className="num">Time</th><th>Last 60 runs</th><th className="num">24 h</th><th>Last failure</th></tr></thead>
      <tbody>{q.data.map(c => <tr key={c.name}>
        <td><b>{c.name}</b> <span className="tag">{c.kind}</span>{c.health && <> <span className="tag">health</span></>}</td>
        <td><span className={`badge ${c.ok ? 'good' : c.health ? 'bad' : 'warn'}`}>{c.ok ? 'Passing' : 'Failing'}</span><div className="small muted">{ago(c.at)}</div></td>
        <td className="small">{c.message}{c.refs && c.refs.length > 0 && <div className="mono muted">{c.refs.join(', ')}</div>}</td>
        <td className="num">{c.kind === 'SQL' && c.value != null ? num(c.value) + ' rows' : ms(c.ms)}</td>
        <td><Runs runs={c.recent} /></td>
        <td className="num small">{c.okCount24h} ok · <span style={{ color: c.failCount24h ? 'var(--red)' : undefined }}>{c.failCount24h} failed</span></td>
        <td className="small muted">{c.lastFailAt ? ago(c.lastFailAt) : 'none in 24 h'}</td>
      </tr>)}</tbody>
    </table></div>
  </Pan>
}

/** Oldest to newest, one tick per run. */
function Runs({ runs }: { runs: CheckState['recent'] }) {
  const r = [...runs].reverse()
  return <svg width={Math.max(60, r.length * 4)} height={18} aria-label={`${r.filter(x => !x.ok).length} of ${r.length} failed`}>
    {r.map((x, i) => <rect key={i} x={i * 4} y={x.ok ? 4 : 0} width={3} height={x.ok ? 14 : 18} rx={1} fill={x.ok ? 'var(--grn)' : 'var(--red)'} />)}
  </svg>
}
