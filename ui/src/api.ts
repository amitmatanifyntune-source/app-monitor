import axios from 'axios'

const KEY = 'monitor.token'

export const api = axios.create({ baseURL: '/api' })

export function token(): string | null {
  try { return sessionStorage.getItem(KEY) } catch { return null }
}
export function setToken(t: string | null) {
  try { t ? sessionStorage.setItem(KEY, t) : sessionStorage.removeItem(KEY) } catch { /* private mode: signed in for this page only */ }
}

api.interceptors.request.use(c => {
  const t = token()
  if (t) c.headers.Authorization = `Bearer ${t}`
  return c
})
api.interceptors.response.use(r => r, e => {
  if (e?.response?.status === 401 && !String(e.config?.url).includes('/auth/login')) {
    setToken(null)
    if (location.pathname !== '/login') location.assign('/login')
  }
  return Promise.reject(e)
})

export function errorText(e: unknown): string {
  const r = (e as { response?: { data?: { message?: string }; status?: number } })?.response
  if (r?.data?.message) return r.data.message
  if (r?.status) return `The server answered ${r.status}`
  return 'No answer from the monitoring server'
}

export const get = <T,>(url: string) => api.get<T>(url).then(r => r.data)

// ------------------------------------------------------------------ types (server records)

export type Status = 'UP' | 'DEGRADED' | 'DOWN' | 'NO_DATA'
export interface User { id: number; username: string; displayName: string; role: 'ADMIN' | 'VIEWER' }
export interface AppCard { code: string; name: string; environment: string; description?: string; status: Status; reasons: string[];
  perMinute: number; p95: number; errors24h: number; agents: number; lastSeen?: string }
export interface Highlight { level: 'good' | 'warn' | 'bad' | 'info'; title: string; text: string; tab: string; ref?: string }
export interface Summary { code: string; name: string; environment: string; status: Status; reasons: string[]; at: string; highlights: Highlight[]; slowMs: number }
export interface Point { minute: number; at: number; requests: number; errors: number; refused: number; slow: number; p95: number; avg: number }
export interface Window { minutes: number; requests: number; perMinute: number; errors: number; refused: number; slow: number; p50: number; p95: number; max: number }
export interface Traffic { series: Point[]; last5: Window; last15: Window; last60: Window; range: Window }
export interface Endpoint { method: string; pattern: string; requests: number; errors: number; refused: number; slow: number; avg: number; p50: number; p95: number;
  max: number; totalMs: number; lastAt: string }
export interface IssueGroup { type: string; method?: string; path?: string; count: number; lastAt: string; lastMessage?: string; lastRequestId?: string; lastStatus?: number }
export interface Event { id: number; at: string; type: string; source?: string; requestId?: string; method?: string; path?: string; status?: number; ms?: number;
  actor?: string; message?: string; detail?: string; agentId?: string }
export interface Issues { hours: number; totals: Record<string, number>; groups: IssueGroup[]; recent: Event[] }
export interface CheckState { name: string; kind: string; health: boolean; ok: boolean; at: string; ms?: number; value?: number; message?: string; refs?: string[];
  okCount24h: number; failCount24h: number; lastFailAt?: string; recent: { at: string; ok: boolean; ms?: number; value?: number }[] }
export interface DbSample { agentId: string; at: string; engine: string; version: string; snapshot: DbSnapshot }
export interface DbSnapshot { up: boolean; summary: string; warn: boolean; reasons: string[]; spec?: DbSpec; pingMs?: number; sizeMb?: number; connections?: number;
  maxConnections?: number; connectionStates?: Record<string, number>; cacheHitPct?: number; deadlocks?: number; locksWaiting?: number; slowQueries?: number;
  running?: { id: number; user: string; state: string; ms: number; wait?: string; query?: string }[];
  statements?: { available: boolean; note?: string; rows: { query: string; calls: number; totalMs: number; meanMs: number; maxMs: number; rows: number; pct: number }[] };
  tables?: { table: string; rows: number; sizeKb: number; seqScans?: number; idxScans?: number; writes?: number; deadRows?: number; freeKb?: number; hint?: string }[] }
export interface ServerSpec { at?: number; hostname?: string; os?: string; kernel?: string; arch?: string; cpuModel?: string; cores?: number; memTotalMb?: number;
  swapTotalMb?: number; uptimeSeconds?: number; virtualization?: string; timezone?: string; java?: string; addresses?: string[];
  disks?: { path: string; type?: string; device?: string; totalMb: number }[] }
export interface DbSpec { host?: string; port?: number; sameServer?: boolean; product?: string; versionFull?: string; startedAt?: string; role?: string;
  database?: string; databases?: number; extensions?: string; serverHostname?: string; settings?: Record<string, string | number>; note?: string }
export interface HostView { agentId: string; agentName: string; host?: string; spec?: ServerSpec | null; specAt?: string | null; latest?: HostSample;
  series: { at: string; cpu?: number; memUsedMb?: number; memTotalMb?: number; load1?: number; agentCpu?: number; agentRssMb?: number }[] }
export interface HostSample { at: number; os?: string; cores?: number; cpu?: number; load1?: number; memUsedMb?: number; memTotalMb?: number; swapUsedMb?: number;
  disks?: { path: string; freeMb: number; totalMb: number }[]; processes?: { name: string; up: boolean; count: number }[]; ports?: { name: string; port: number; up: boolean }[];
  agent?: AgentSelf }
/** What the agent itself costs the server (agent 0.2+); OpenTelemetry counts are since the previous sample (about a minute). */
export interface AgentSelf { cpu?: number; rssMb?: number; heapUsedMb?: number; heapMaxMb?: number; threads?: number; uptimeSec?: number; spoolKb?: number;
  otlp?: boolean; spansReceived?: number; spansDropped?: number; tracesKept?: number; tracesDropped?: number; tracesPending?: number }
export interface Agent { id: string; name: string; host?: string; version?: string; lastSeen?: string; revoked: boolean; createdAt: string; online?: boolean; lastSeq?: number }
export interface CheckDef { id: number; name: string; kind: 'HTTP' | 'SQL'; config: Record<string, unknown>; intervalSec: number; health: boolean; enabled: boolean }
export interface AppAdmin { id: number; code: string; name: string; environment: string; description?: string; slowMs: number; mask: string[];
  routeRules: { match: string; replace: string }[]; agents: Agent[]; checks: CheckDef[] }
export interface AlertChannel { id: number; kind: 'SLACK' | 'WHATSAPP'; target: string; label?: string; includeDegraded: boolean; enabled: boolean }
export interface AlertSent { at: string; kind: string; status: string; ok: boolean; message: string; error?: string; channelKind?: string; channelLabel?: string }
export interface AlertSetup { whatsappReady: boolean; mutedUntil?: string | null; channels: AlertChannel[]; recent: AlertSent[] }
export interface Dependency { kind: 'db' | 'http' | 'queue' | 'other'; target: string; operation: string; calls: number; errors: number; avg: number;
  p50: number; p95: number; max: number; totalMs: number; lastAt: string }
export interface TraceRow { id: number; traceId: string; at: string; name: string; service?: string; durationMs: number; error: boolean;
  reason: 'error' | 'slow' | 'sample'; status?: number; requestId?: string; spanCount: number; agentId?: string }
export interface TraceSpan { id: string; parent?: string; name: string; kind: number; startUs: number; durUs: number; error?: boolean;
  dep?: string; target?: string; attrs?: Record<string, string | number | boolean> }
export interface TraceDetail extends TraceRow { spans: TraceSpan[] }
