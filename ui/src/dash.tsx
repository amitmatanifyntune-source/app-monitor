import { useEffect, useLayoutEffect, useRef, useState, type ReactNode } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useQueryClient } from '@tanstack/react-query'

// ------------------------------------------------------------------ time range and refresh, kept in the URL like Grafana's ?from=&refresh=

export const RANGES: [number, string][] = [[15, 'Last 15 minutes'], [60, 'Last 1 hour'], [360, 'Last 6 hours'], [720, 'Last 12 hours'],
  [1440, 'Last 24 hours'], [2880, 'Last 2 days'], [10080, 'Last 7 days']]
const REFRESH: [number, string][] = [[0, 'Off'], [10, '10s'], [30, '30s'], [60, '1m'], [300, '5m']]

/** Minutes of history and seconds between refreshes, from ?range= and ?refresh=. */
export function useRange() {
  const [params, setParams] = useSearchParams()
  const range = Number(params.get('range')) || 60
  const refresh = params.has('refresh') ? Number(params.get('refresh')) || 0 : 30
  const set = (k: string, v: number) => setParams(p => { const n = new URLSearchParams(p); n.set(k, String(v)); return n }, { replace: true })
  return { range, refresh, every: refresh ? refresh * 1000 : false as const, setRange: (v: number) => set('range', v), setRefresh: (v: number) => set('refresh', v) }
}

export const rangeText = (m: number) => RANGES.find(r => r[0] === m)?.[1] ?? `Last ${m} minutes`

function Menu({ label, open, setOpen, children }: { label: string; open: boolean; setOpen: (o: boolean) => void; children: ReactNode }) {
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!open) return
    const close = (e: MouseEvent | KeyboardEvent) => {
      if (e instanceof KeyboardEvent ? e.key === 'Escape' : !ref.current?.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', close)
    document.addEventListener('keydown', close)
    return () => { document.removeEventListener('mousedown', close); document.removeEventListener('keydown', close) }
  }, [open, setOpen])
  return <div ref={ref} style={{ position: 'relative' }}>{open && <div className="menu" role="menu" aria-label={label}>{children}</div>}</div>
}

export function TimePicker() {
  const { range, setRange } = useRange()
  const [open, setOpen] = useState(false)
  return <div className="picker">
    <button type="button" aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen(!open)}><Clock />{rangeText(range)}<Caret /></button>
    <Menu label="Time range" open={open} setOpen={setOpen}>
      <div className="mh">Quick ranges</div>
      {RANGES.map(([m, l]) => <button key={m} role="menuitem" className={m === range ? 'on' : ''} onClick={() => { setRange(m); setOpen(false) }}>{l}</button>)}
    </Menu>
  </div>
}

export function RefreshPicker() {
  const { refresh, setRefresh } = useRange()
  const qc = useQueryClient()
  const [open, setOpen] = useState(false)
  return <div className="picker joined">
    <button type="button" title="Refresh now" aria-label="Refresh now" onClick={() => qc.invalidateQueries()}><Sync /></button>
    <button type="button" aria-haspopup="menu" aria-expanded={open} aria-label={`Auto refresh: ${refresh ? `every ${refresh} s` : 'off'}`}
      onClick={() => setOpen(!open)}>{refresh ? REFRESH.find(r => r[0] === refresh)?.[1] ?? `${refresh}s` : ''}<Caret /></button>
    <Menu label="Auto refresh" open={open} setOpen={setOpen}>
      <div className="mh">Auto refresh</div>
      {REFRESH.map(([s, l]) => <button key={s} role="menuitem" className={s === refresh ? 'on' : ''} onClick={() => { setRefresh(s); setOpen(false) }}>{l}</button>)}
    </Menu>
  </div>
}

const Clock = () => <svg width="16" height="16" viewBox="0 0 24 24" aria-hidden><circle cx="12" cy="12" r="9" fill="none" stroke="currentColor" strokeWidth="2" /><path d="M12 7v5l3 2" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" /></svg>
const Sync = () => <svg width="16" height="16" viewBox="0 0 24 24" aria-hidden><path d="M20 11a8 8 0 0 0-14.3-4.9L4 8M4 4v4h4M4 13a8 8 0 0 0 14.3 4.9L20 16m0 4v-4h-4" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" /></svg>
const Caret = () => <svg width="12" height="12" viewBox="0 0 24 24" aria-hidden><path d="M6 9l6 6 6-6" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" /></svg>

// ------------------------------------------------------------------ layout: rows of panels on a 24-column grid, 30 px height units

export function Row({ title, note, open, onToggle }: { title: string; note?: ReactNode; open: boolean; onToggle: () => void }) {
  return <h3 className="drow"><button type="button" aria-expanded={open} className={open ? '' : 'collapsed'} onClick={onToggle}>
    <span className="chev" aria-hidden>▾</span>{title}</button>{note && <small>{note}</small>}</h3>
}

export function Panel({ title, info, w = 12, h = 8, flush, right, children }: {
  title: ReactNode; info?: string; w?: number; h?: number; flush?: boolean; right?: ReactNode; children: ReactNode
}) {
  return <section className="gp" style={{ gridColumn: `span ${w}`, height: h * 30 + (h - 1) * 8 }}>
    <div className="gp-h"><span className="t">{title}</span>{info && <span className="info" title={info} aria-label={info}>i</span>}<span className="spacer" />{right}</div>
    <div className={`gp-b${flush ? ' flush' : ''}`}>{children}</div>
  </section>
}

function useSize<T extends HTMLElement>() {
  const ref = useRef<T>(null)
  const [size, setSize] = useState({ w: 0, h: 0 })
  useLayoutEffect(() => {
    const el = ref.current
    if (!el) return
    const ro = new ResizeObserver(([e]) => setSize({ w: Math.round(e.contentRect.width), h: Math.round(e.contentRect.height) }))
    ro.observe(el)
    return () => ro.disconnect()
  }, [])
  return [ref, size] as const
}

// ------------------------------------------------------------------ thresholds: a colour for a value, as in Grafana's "base, then steps"

export type Steps = { base: string; steps?: [number, string][] }
export const colorOf = (v: number | null | undefined, t?: Steps) => {
  if (!t) return 'var(--strong)'
  let c = t.base
  for (const [at, col] of t.steps ?? []) if (v != null && v >= at) c = col
  return c
}

// ------------------------------------------------------------------ time series

export interface Series { key: string; label: string; color: string; fill?: number }
type Pt = { at: number } & Record<string, number>

function nice(max: number) {
  if (max <= 0) return { top: 1, step: 0.25 }
  const raw = max / 4, mag = 10 ** Math.floor(Math.log10(raw)), n = raw / mag
  const step = (n <= 1 ? 1 : n <= 2 ? 2 : n <= 2.5 ? 2.5 : n <= 5 ? 5 : 10) * mag
  return { top: Math.ceil(max / step) * step, step }
}

const clock = (t: number, long: boolean) => new Date(t).toLocaleString('en-IN', long
  ? { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false }
  : { hour: '2-digit', minute: '2-digit', hour12: false })

/**
 * Lines with a light fill, a crosshair tooltip listing every series at the hovered time, an optional dashed threshold,
 * and a legend table with mean, max and last, like Grafana's time series panel.
 */
export function TimeSeries({ points, series, format, threshold, legend = 'table', yMax }: {
  points: Pt[]; series: Series[]; format: (v: number) => string; threshold?: { value: number; color: string; label: string }; legend?: 'table' | 'list' | 'none'; yMax?: number
}) {
  const [ref, { w, h }] = useSize<HTMLDivElement>()
  const [hi, setHi] = useState<number | null>(null)
  const L = 52, R = 8, T = 8, B = 20
  const val = (p: Pt, k: string) => Number(p[k]) || 0
  const dataMax = Math.max(0, ...series.flatMap(s => points.map(p => val(p, s.key))))
  // a threshold far above the data would flatten it; it joins the scale only when the data comes within half of it
  const max = yMax ?? (threshold && threshold.value <= dataMax * 2 ? Math.max(dataMax, threshold.value) : dataMax)
  const { top, step } = nice(max)
  const t0 = points[0]?.at ?? 0, t1 = points[points.length - 1]?.at ?? 1
  const long = t1 - t0 > 36 * 3600_000
  const x = (t: number) => L + (t1 === t0 ? 0 : ((t - t0) * (w - L - R)) / (t1 - t0))
  const y = (v: number) => T + (h - T - B) * (1 - Math.min(v, top) / top)
  const yTicks = Array.from({ length: Math.round(top / step) + 1 }, (_, i) => i * step)
  const nx = Math.max(2, Math.floor((w - L - R) / (long ? 110 : 80)))
  const xTicks = points.length > 1 ? Array.from({ length: nx + 1 }, (_, i) => t0 + ((t1 - t0) * i) / nx) : []
  const move = (e: React.MouseEvent) => {
    if (points.length < 2) return
    const px = e.clientX - (e.currentTarget as HTMLElement).getBoundingClientRect().left
    const t = t0 + ((px - L) * (t1 - t0)) / (w - L - R)
    let best = 0
    for (let i = 1; i < points.length; i++) if (Math.abs(points[i].at - t) < Math.abs(points[best].at - t)) best = i
    setHi(best)
  }
  const calc = (k: string) => {
    const v = points.map(p => val(p, k))
    return { mean: v.length ? v.reduce((a, b) => a + b, 0) / v.length : 0, max: v.length ? Math.max(...v) : 0, last: v.length ? v[v.length - 1] : 0 }
  }

  return <div className="ts">
    <div className="ts-plot" ref={ref} onMouseMove={move} onMouseLeave={() => setHi(null)}>
      {w > 0 && h > 0 && <svg role="img" aria-label={series.map(s => s.label).join(', ')}>
        {yTicks.map(v => <g key={v}><line className="gl" x1={L} x2={w - R} y1={y(v)} y2={y(v)} />
          <text className="ax" x={L - 6} y={y(v) + 4} textAnchor="end">{format(v)}</text></g>)}
        {xTicks.map((t, i) => <text key={i} className="ax" x={x(t)} y={h - 4} textAnchor={i === 0 ? 'start' : i === xTicks.length - 1 ? 'end' : 'middle'}>{clock(t, long)}</text>)}
        {threshold && threshold.value <= top && <line className="th" x1={L} x2={w - R} y1={y(threshold.value)} y2={y(threshold.value)} stroke={threshold.color}>
          <title>{threshold.label}</title></line>}
        {points.length > 1 && series.map(s => {
          const line = points.map((p, i) => `${i ? 'L' : 'M'}${x(p.at).toFixed(1)},${y(val(p, s.key)).toFixed(1)}`).join('')
          return <g key={s.key}>
            {(s.fill ?? 0.1) > 0 && <path d={`${line}L${x(t1)},${y(0)}L${x(t0)},${y(0)}Z`} fill={s.color} opacity={s.fill ?? 0.1} />}
            <path d={line} fill="none" stroke={s.color} strokeWidth={1.5} strokeLinejoin="round" />
          </g>
        })}
        {hi != null && <g>
          <line className="xh" x1={x(points[hi].at)} x2={x(points[hi].at)} y1={T} y2={h - B} />
          {series.map(s => <circle key={s.key} cx={x(points[hi].at)} cy={y(val(points[hi], s.key))} r={3.5} fill={s.color} stroke="var(--panel)" strokeWidth={1.5} />)}
        </g>}
      </svg>}
      {hi != null && <div className="tip" style={x(points[hi].at) > w / 2 ? { right: w - x(points[hi].at) + 12, top: 4 } : { left: x(points[hi].at) + 12, top: 4 }}>
        <div className="tt">{new Date(points[hi].at).toLocaleString('en-IN', { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit', hour12: false })}</div>
        {series.map(s => <div key={s.key} className="tr"><i style={{ background: s.color }} />{s.label}<b>{format(val(points[hi], s.key))}</b></div>)}
      </div>}
    </div>
    {legend === 'table' && <div className="lg"><table><thead><tr><th /><th className="num">Mean</th><th className="num">Max</th><th className="num">Last</th></tr></thead>
      <tbody>{series.map(s => { const c = calc(s.key); return <tr key={s.key}><td><i style={{ background: s.color }} />{s.label}</td>
        <td className="num">{format(c.mean)}</td><td className="num">{format(c.max)}</td><td className="num">{format(c.last)}</td></tr> })}</tbody></table></div>}
    {legend === 'list' && <div className="lg lg-list">{series.map(s => <span key={s.key}><i style={{ background: s.color }} />{s.label}</span>)}</div>}
  </div>
}

// ------------------------------------------------------------------ stat: a big value in its threshold colour, a sparkline behind

export function StatValue({ value, unit, color, sub, spark }: { value: ReactNode; unit?: string; color: string; sub?: ReactNode; spark?: number[] }) {
  // scaled from the lowest to the highest value, as Grafana does; a flat series draws as a flat line low in the panel
  const lo = Math.min(...(spark ?? [0])), hi = Math.max(...(spark ?? [0]))
  const yv = (v: number) => hi === lo ? 70 : 96 - ((v - lo) / (hi - lo)) * 88
  const n = spark?.length ?? 0
  return <div className="gstat">
    <div className="gv" style={{ color }}>{value}{unit && <small>{unit}</small>}</div>
    {sub != null && <div className="gs">{sub}</div>}
    {n > 1 && <svg viewBox={`0 0 ${n - 1} 100`} preserveAspectRatio="none" aria-hidden>
      <path d={`M0,100 ${spark!.map((v, i) => `L${i},${yv(v)}`).join(' ')} L${n - 1},100Z`} fill={color} opacity={0.12} />
      <path d={spark!.map((v, i) => `${i ? 'L' : 'M'}${i},${yv(v)}`).join(' ')} fill="none" stroke={color} strokeWidth={1.5} vectorEffect="non-scaling-stroke" opacity={0.8} />
    </svg>}
  </div>
}

// ------------------------------------------------------------------ bar gauge

export function BarGauge({ rows }: { rows: { label: string; value: number; max: number; text: string; color: string; title?: string }[] }) {
  if (!rows.length) return <div className="empty">No data</div>
  return <div className="bg">{rows.map((r, i) => <div key={i} className="bg-r" title={r.title ?? r.label}>
    <span className="l">{r.label}</span>
    <span className="b"><i style={{ width: `${r.value > 0 ? Math.max(1, Math.min(100, (100 * r.value) / (r.max || 1))) : 0}%`, background: r.color }} /></span>
    <span className="v" style={{ color: r.value > 0 ? r.color : 'var(--faint)' }}>{r.text}</span>
  </div>)}</div>
}

// ------------------------------------------------------------------ state timeline: each run is a block from its time to the next run's

export function StateTimeline({ lanes, from, to }: { lanes: { label: string; runs: { at: number; ok: boolean }[]; note: string }[]; from: number; to: number }) {
  if (!lanes.length) return <div className="empty">No checks yet. Add them in Setup.</div>
  const span = Math.max(1, to - from)
  return <div className="st">{lanes.map(l => {
    const runs = [...l.runs].filter(r => r.at >= from).sort((a, b) => a.at - b.at)
    return <div key={l.label} className="st-r">
      <span className="l" title={l.label}>{l.label}</span>
      <svg viewBox="0 0 1000 20" preserveAspectRatio="none" role="img" aria-label={`${l.label}: ${runs.filter(r => !r.ok).length} of ${runs.length} runs failed`}>
        <rect x={0} y={0} width={1000} height={20} fill="var(--hover)" />
        {runs.map((r, i) => {
          const x0 = ((r.at - from) * 1000) / span, x1 = (((runs[i + 1]?.at ?? to) - from) * 1000) / span
          return <rect key={i} x={x0} y={0} width={Math.max(1.5, x1 - x0 - 0.5)} height={20} fill={r.ok ? 'var(--grn)' : 'var(--red)'} opacity={r.ok ? 0.75 : 1} />
        })}
      </svg>
      <span className="v">{l.note}</span>
    </div>
  })}</div>
}
