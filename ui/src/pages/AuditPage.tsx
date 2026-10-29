import { useQuery } from '@tanstack/react-query'
import { get } from '../api'
import { Empty, Pan, time } from '../ui'

interface Row { at: string; username?: string; action: string; target?: string; detail?: string }

export default function AuditPage() {
  const q = useQuery({ queryKey: ['audit'], queryFn: () => get<Row[]>('/admin/audit?limit=300'), refetchInterval: 30000 })
  return <>
    <div className="top"><div><h2>Audit log</h2><div className="sub">Sign-ins, who looked at which app, and every change to apps, agents, checks and users.</div></div></div>
    <div className="work">
      <Pan title="Latest 300" flush>
        {!q.data?.length ? <Empty>Nothing yet.</Empty> :
          <div className="scroll"><table><thead><tr><th>When</th><th>Who</th><th>Action</th><th>On</th><th>Detail</th></tr></thead>
            <tbody>{q.data.map((r, i) => <tr key={i}><td className="mono small">{time(r.at)}</td><td>{r.username ?? '—'}</td>
              <td><span className={`tag ${r.action === 'LOGIN_FAILED' ? 'ERROR' : ''}`}>{r.action}</span></td><td className="mono small">{r.target ?? ''}</td>
              <td className="small">{r.detail ?? ''}</td></tr>)}</tbody></table></div>}
      </Pan>
    </div>
  </>
}
