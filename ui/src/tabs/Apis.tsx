import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { get, type Endpoint } from '../api'
import { rangeText, useRange } from '../dash'
import { Bar, Empty, Pan, ago, ms, num } from '../ui'

type Sort = 'totalMs' | 'requests' | 'p95' | 'errors' | 'refused' | 'max'

export default function Apis({ code }: { code: string }) {
  const { range, every } = useRange()
  const [sort, setSort] = useState<Sort>('totalMs')
  const [filter, setFilter] = useState('')
  const q = useQuery({ queryKey: ['endpoints', code, range], queryFn: () => get<Endpoint[]>(`/apps/${code}/endpoints?minutes=${range}`), refetchInterval: every })
  const rows = useMemo(() => (q.data ?? []).filter(e => !filter || (e.method + ' ' + e.pattern).toLowerCase().includes(filter.toLowerCase()))
    .sort((a, b) => (b[sort] as number) - (a[sort] as number)), [q.data, sort, filter])
  const total = rows.reduce((s, e) => s + e.totalMs, 0)
  const th = (k: Sort, l: string) => <th className="num" aria-sort={sort === k ? 'descending' : undefined}>
    <button type="button" className="th-sort" onClick={() => setSort(k)}>{l}{sort === k ? ' ↓' : ''}</button></th>

  return <Pan title={`${rows.length} endpoint${rows.length === 1 ? '' : 's'}`} flush right={<div className="row">
    <input placeholder="Filter, e.g. /api/orders" aria-label="Filter endpoints" value={filter} onChange={e => setFilter(e.target.value)} style={{ width: 220, height: 30 }} />
  </div>}>
    {rows.length === 0 ? <Empty>{q.isLoading ? 'Loading…' : filter ? `No endpoint matches “${filter}”.` : `No API requests in ${rangeText(range).toLowerCase()}.`}</Empty> :
      <div className="scroll"><table>
        <thead><tr><th>Endpoint</th>{th('requests', 'Requests')}{th('errors', 'Errors')}{th('refused', 'Refused')}<th className="num">Median</th>
          {th('p95', '95% within')}{th('max', 'Max')}{th('totalMs', 'Share of time')}<th>Last</th></tr></thead>
        <tbody>{rows.map(e => {
          const share = total ? (100 * e.totalMs) / total : 0
          return <tr key={e.method + e.pattern}>
            <td><span className="tag">{e.method}</span> <span className="mono">{e.pattern}</span></td>
            <td className="num">{num(e.requests)}</td>
            <td className="num" style={{ color: e.errors ? 'var(--red)' : undefined }}>{num(e.errors)}</td>
            <td className="num" style={{ color: e.refused ? 'var(--blu)' : undefined }}>{num(e.refused)}</td>
            <td className="num">{ms(e.p50)}</td>
            <td className="num" style={{ color: e.p95 >= 2000 ? 'var(--amb)' : undefined }}>{ms(e.p95)}</td>
            <td className="num">{ms(e.max)}</td>
            <td className="num"><div className="row" style={{ justifyContent: 'flex-end', flexWrap: 'nowrap' }}><Bar pct={share} />{share.toFixed(1)}%</div></td>
            <td className="small muted">{ago(e.lastAt)}</td>
          </tr>
        })}</tbody>
      </table></div>}
  </Pan>
}
