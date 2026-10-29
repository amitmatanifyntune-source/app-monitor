import { useState, type ReactNode } from 'react'
import type { Status } from './api'

export const STATUS_TEXT: Record<Status, string> = { UP: 'Up', DEGRADED: 'Degraded', DOWN: 'Down', NO_DATA: 'No data yet' }

export function Badge({ status }: { status: Status }) {
  return <span className={`badge ${status}`}>{STATUS_TEXT[status] ?? status}</span>
}

export function Pan({ title, right, children, flush }: { title: ReactNode; right?: ReactNode; children: ReactNode; flush?: boolean }) {
  return <div className="pan">
    <div className="pan-h">{title}<span className="spacer" />{right}</div>
    <div className={`pan-b${flush ? ' flush' : ''}`}>{children}</div>
  </div>
}

/** `tone` colours the value when the number itself is the problem (errors above zero, a failing check). */
export function Stat({ k, v, s, tone }: { k: string; v: ReactNode; s?: ReactNode; tone?: 'warn' | 'bad' }) {
  return <div className={`stat${tone ? ` ${tone}` : ''}`}><div className="k">{k}</div><div className="v">{v}</div>{s != null && <div className="s">{s}</div>}</div>
}

/** One choice out of a few, e.g. a time range. Filters the view; it never navigates. */
export function Seg<T extends string | number>({ value, options, onChange, label }: { value: T; options: [T, string][]; onChange: (v: T) => void; label: string }) {
  return <div className="seg" role="group" aria-label={label}>
    {options.map(([v, l]) => <button key={String(v)} type="button" aria-pressed={v === value} className={v === value ? 'on' : ''} onClick={() => onChange(v)}>{l}</button>)}
  </div>
}

/** One bar per minute, oldest first: height is traffic, red where that minute had server errors, a flat tick where it had none. */
export function Activity({ points, label }: { points: { requests: number; errors: number }[]; label: string }) {
  const max = Math.max(1, ...points.map(p => p.requests)), H = 28
  return <svg className="act" viewBox={`0 0 ${Math.max(1, points.length) * 4} ${H}`} preserveAspectRatio="none" role="img" aria-label={label}>
    {points.map((p, i) => {
      const h = p.requests ? Math.max(4, ((H - 2) * p.requests) / max) : 2
      return <rect key={i} x={i * 4} y={H - h} width={3} height={h} className={p.errors ? 'e' : p.requests ? 'r' : 'z'} />
    })}
  </svg>
}

export function Empty({ children }: { children: ReactNode }) {
  return <div className="empty">{children}</div>
}

export function ms(v?: number | null): string {
  if (v == null) return '—'
  return v >= 1000 ? `${(v / 1000).toFixed(v >= 10000 ? 0 : 1)} s` : `${Math.round(v)} ms`
}

export function num(v?: number | null): string {
  if (v == null) return '—'
  return v.toLocaleString('en-IN', { maximumFractionDigits: 1 })
}

export function mb(v?: number | null): string {
  if (v == null) return '—'
  return v >= 1024 ? `${(v / 1024).toFixed(1)} GB` : `${Math.round(v)} MB`
}

export function ago(iso?: string | number | null): string {
  if (iso == null) return 'never'
  const t = typeof iso === 'number' ? iso : Date.parse(iso)
  const s = Math.round((Date.now() - t) / 1000)
  if (s < 5) return 'just now'
  if (s < 60) return `${s} s ago`
  if (s < 3600) return `${Math.floor(s / 60)} min ago`
  if (s < 86400) return `${Math.floor(s / 3600)} h ago`
  return `${Math.floor(s / 86400)} d ago`
}

export function time(iso?: string | number | null): string {
  if (iso == null) return '—'
  const d = new Date(iso)
  return d.toLocaleString('en-IN', { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false })
}

export function Bar({ pct, level }: { pct: number; level?: 'warn' | 'bad' }) {
  return <div className={`bar ${level ?? ''}`}><i style={{ width: `${Math.max(0, Math.min(100, pct))}%` }} /></div>
}

/** A small line chart in inline SVG: series share the x axis; each has its own colour token. */
export function LineChart({ points, series, height = 160, format = num }: {
  points: { at: number }[]; series: { key: string; label: string; color: string }[]; height?: number; format?: (v: number) => string
}) {
  const [hi, setHi] = useState<number | null>(null)
  const W = 760, H = height, L = 46, R = 10, T = 10, B = 22
  const val = (p: { at: number }, key: string) => Number((p as unknown as Record<string, number>)[key]) || 0
  const vals = series.flatMap(s => points.map(p => val(p, s.key)))
  const max = Math.max(1, ...vals)
  const x = (i: number) => L + (points.length <= 1 ? 0 : (i * (W - L - R)) / (points.length - 1))
  const y = (v: number) => T + (H - T - B) * (1 - v / max)
  const ticks = [0, 0.5, 1].map(f => max * f)
  const labels = points.length > 1 ? [0, Math.floor(points.length / 2), points.length - 1] : [0]
  return <div>
    <svg className="chart" viewBox={`0 0 ${W} ${H}`} role="img" aria-label={series.map(s => s.label).join(', ')} onMouseLeave={() => setHi(null)}
      onMouseMove={e => {
        if (points.length < 2) return
        const r = e.currentTarget.getBoundingClientRect()
        const i = Math.round((((e.clientX - r.left) * W) / r.width - L) / ((W - L - R) / (points.length - 1)))
        setHi(Math.max(0, Math.min(points.length - 1, i)))
      }}>
      {ticks.map((t, i) => <g key={i}><line className="grid-l" x1={L} x2={W - R} y1={y(t)} y2={y(t)} /><text x={L - 6} y={y(t) + 3} textAnchor="end">{format(t)}</text></g>)}
      {points.length > 0 && labels.map(i => <text key={i} x={x(i)} y={H - 6} textAnchor="middle">
        {new Date(points[i].at).toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit', hour12: false })}</text>)}
      {series.map(s => <polyline key={s.key} fill="none" stroke={`var(${s.color})`} strokeWidth={2} strokeLinejoin="round"
        points={points.map((p, i) => `${x(i)},${y(val(p, s.key))}`).join(' ')} />)}
      {hi != null && <g className="hover">
        <line x1={x(hi)} x2={x(hi)} y1={T} y2={H - B} />
        {series.map(s => <circle key={s.key} cx={x(hi)} cy={y(val(points[hi], s.key))} r={3.5} style={{ fill: `var(${s.color})` }} />)}
      </g>}
    </svg>
    <div className="legend">
      {hi != null && <span className="mono">{new Date(points[hi].at).toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit', hour12: false })}</span>}
      {series.map(s => <span key={s.key}><i style={{ background: `var(${s.color})` }} />{s.label}{hi != null && <b className="mono"> {format(val(points[hi], s.key))}</b>}</span>)}
    </div>
  </div>
}
