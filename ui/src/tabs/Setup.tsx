import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { api, errorText, get, type Agent, type AlertChannel, type AlertSetup, type AppAdmin, type CheckDef } from '../api'
import { Empty, Pan, ago, time } from '../ui'

export default function Setup({ code, admin }: { code: string; admin: boolean }) {
  if (!admin) return <AgentsReadOnly code={code} />
  return <AdminSetup code={code} />
}

function AgentsReadOnly({ code }: { code: string }) {
  const q = useQuery({ queryKey: ['agents', code], queryFn: () => get<Agent[]>(`/apps/${code}/agents`) })
  return <Pan title="Agents" flush><AgentTable agents={q.data ?? []} /></Pan>
}

function AgentTable({ agents, onRevoke }: { agents: Agent[]; onRevoke?: (a: Agent) => void }) {
  if (!agents.length) return <Empty>No agents registered yet.</Empty>
  return <div className="scroll"><table><thead><tr><th>Agent</th><th>Id</th><th>Host</th><th>Version</th><th>Last report</th><th /></tr></thead>
    <tbody>{agents.map(a => {
      const online = a.lastSeen && Date.now() - Date.parse(a.lastSeen) < 3 * 60_000
      return <tr key={a.id}>
        <td><b>{a.name}</b> {a.revoked ? <span className="tag">revoked</span> : <span className={`badge ${online ? 'good' : a.lastSeen ? 'bad' : 'info'}`}>{online ? 'reporting' : a.lastSeen ? 'silent' : 'never reported'}</span>}</td>
        <td className="mono small">{a.id}</td><td>{a.host ?? '—'}</td><td className="mono small">{a.version ?? '—'}</td><td className="small">{ago(a.lastSeen)}</td>
        <td>{onRevoke && !a.revoked && <button className="btn sm danger" onClick={() => onRevoke(a)}>Revoke</button>}</td>
      </tr>
    })}</tbody></table></div>
}

function AdminSetup({ code }: { code: string }) {
  const qc = useQueryClient()
  const nav = useNavigate()
  const q = useQuery({ queryKey: ['admin-app', code], queryFn: () => get<AppAdmin>(`/admin/apps/${code}`) })
  const [newAgent, setNewAgent] = useState<{ agentId: string; agentKey: string; agentJson: unknown } | null>(null)
  const [agentName, setAgentName] = useState('')
  const [serverUrl, setServerUrl] = useState(location.origin.includes(':5180') ? 'http://localhost:8095' : location.origin)
  const [editing, setEditing] = useState<Partial<CheckDef> | null>(null)
  const [error, setError] = useState<string | null>(null)
  const refresh = () => { qc.invalidateQueries({ queryKey: ['admin-app', code] }); qc.invalidateQueries({ queryKey: ['summary', code] }) }

  async function register() {
    setError(null)
    try {
      const r = await api.post(`/admin/apps/${code}/agents`, { name: agentName, serverUrl })
      setNewAgent(r.data)
      setAgentName('')
      refresh()
    } catch (e) { setError(errorText(e)) }
  }

  async function revoke(a: Agent) {
    if (!window.confirm(`Revoke agent "${a.name}"? It will be refused from now on. This cannot be undone.`)) return
    try { await api.post(`/admin/apps/${code}/agents/${a.id}/revoke`); refresh() } catch (e) { setError(errorText(e)) }
  }

  async function removeCheck(c: CheckDef) {
    if (!window.confirm(`Delete check "${c.name}"?`)) return
    try { await api.delete(`/admin/apps/${code}/checks/${c.id}`); refresh() } catch (e) { setError(errorText(e)) }
  }

  async function removeApp() {
    if (window.prompt(`This deletes "${q.data?.name}" and all its monitoring data. Type the app code (${code}) to confirm.`) !== code) return
    try { await api.delete(`/admin/apps/${code}`); await qc.invalidateQueries({ queryKey: ['apps'] }); nav('/') } catch (e) { setError(errorText(e)) }
  }

  const download = () => {
    if (!newAgent) return
    const blob = new Blob([JSON.stringify(newAgent.agentJson, null, 2) + '\n'], { type: 'application/json' })
    const a = document.createElement('a')
    a.href = URL.createObjectURL(blob)
    a.download = 'agent.json'
    a.click()
    URL.revokeObjectURL(a.href)
  }

  if (!q.data) return <Pan title="Setup"><Empty>{q.isError ? errorText(q.error) : 'Loading…'}</Empty></Pan>
  return <>
    {error && <div className="err">{error}</div>}

    <Pan title="1 · Agents">
      <p className="small mid" style={{ marginTop: 0 }}>Install one agent on each server the app runs on. It needs Java 21 and outbound HTTPS to this monitoring server; nothing is opened
        inbound. Then run <span className="mono">java -jar fyntune-agent.jar agent.json --test</span> to check the settings, and start it as a service.{' '}
        <Link to="/help#install">Step-by-step guide for your stack →</Link></p>
      {newAgent && <div className="secret">
        <b>Agent {newAgent.agentId} registered.</b> Its key is shown only now: download agent.json and keep it safe (it is the agent's password).
        <div className="row" style={{ marginTop: 8 }}><button className="btn pri sm" onClick={download}>Download agent.json</button>
          <button className="btn sm" onClick={() => navigator.clipboard?.writeText(newAgent.agentKey)}>Copy key</button>
          <button className="btn sm" onClick={() => setNewAgent(null)}>Done</button></div>
        <pre className="code" style={{ marginTop: 10 }}>{JSON.stringify(newAgent.agentJson, null, 2)}</pre>
        <div className="small" style={{ marginTop: 6 }}>Edit <span className="mono">accessLogs</span>, <span className="mono">appLogs</span>, <span className="mono">db</span> and
          <span className="mono"> host</span> for this server before starting the agent. See <span className="mono">samples/</span> in the repository.</div>
      </div>}
      <div className="row" style={{ marginBottom: 14, alignItems: 'flex-end' }}>
        <div style={{ flex: 1, minWidth: 180 }}><label>New agent name</label><input value={agentName} onChange={e => setAgentName(e.target.value)} placeholder="e.g. web-1 (the server's name)" /></div>
        <div style={{ flex: 1, minWidth: 220 }}><label>Monitoring server address, as the agent reaches it</label><input className="mono" value={serverUrl} onChange={e => setServerUrl(e.target.value)} /></div>
        <button className="btn pri" disabled={!agentName} onClick={register}>Register agent</button>
      </div>
      <div className="pan" style={{ margin: 0 }}><AgentTable agents={q.data.agents} onRevoke={revoke} /></div>
    </Pan>

    <Pan title="2 · Checks" right={<div className="row">
      <button className="btn sm" onClick={() => setEditing({ kind: 'HTTP', health: true, intervalSec: 60, config: { url: '', expectStatus: 200, maxMs: 3000 } })}>Add HTTP check</button>
      <button className="btn sm" onClick={() => setEditing({ kind: 'SQL', health: false, intervalSec: 300, config: { sql: '', refColumn: 'ref', warnAbove: 0 } })}>Add SQL check</button></div>}>
      <p className="small mid" style={{ marginTop: 0 }}>Agents run these from inside the app's network. A failing <b>health</b> check marks the app Down. SQL checks run only on agents whose
        agent.json sets <span className="mono">"allowServerSqlChecks": true</span>, on a read-only connection; only the row count and up to 10 reference values come back.</p>
      {q.data.checks.length === 0 ? <Empty>No checks yet. Start with an HTTP check on the app's health or home page.</Empty> :
        <div className="pan" style={{ margin: 0 }}><div className="scroll"><table><thead><tr><th>Name</th><th>Kind</th><th>What</th><th className="num">Every</th><th /></tr></thead>
          <tbody>{q.data.checks.map(c => <tr key={c.id}>
            <td><b>{c.name}</b>{c.health && <> <span className="tag">health</span></>}{!c.enabled && <> <span className="tag">off</span></>}</td><td>{c.kind}</td>
            <td><div className="q">{c.kind === 'HTTP' ? `${c.config.url} → ${c.config.expectStatus ?? 200} within ${c.config.maxMs ?? 3000} ms` : String(c.config.sql)}</div></td>
            <td className="num">{c.intervalSec} s</td>
            <td><div className="row" style={{ flexWrap: 'nowrap' }}><button className="btn sm" onClick={() => setEditing(c)}>Edit</button>
              <button className="btn sm danger" onClick={() => removeCheck(c)}>Delete</button></div></td></tr>)}</tbody></table></div></div>}
    </Pan>

    <Alerts code={code} />

    <AppSettings app={q.data} onSaved={refresh} onError={setError} />

    <Pan title="Danger zone"><button className="btn danger" onClick={removeApp}>Delete this app and all its data</button></Pan>

    {editing && <CheckEditor code={code} check={editing} onClose={() => setEditing(null)} onSaved={() => { setEditing(null); refresh() }} />}
  </>
}

const KIND_TEXT = { SLACK: 'Slack', WHATSAPP: 'WhatsApp' }
const SENT_TEXT: Record<string, string> = { CHANGE: 'Status change', REMINDER: 'Still down', RECOVERED: 'Recovered', TEST: 'Test' }

function Alerts({ code }: { code: string }) {
  const qc = useQueryClient()
  const q = useQuery({ queryKey: ['alerts', code], queryFn: () => get<AlertSetup>(`/admin/apps/${code}/alerts`), refetchInterval: 30000 })
  const [kind, setKind] = useState<'SLACK' | 'WHATSAPP'>('SLACK')
  const [target, setTarget] = useState('')
  const [label, setLabel] = useState('')
  const [degraded, setDegraded] = useState(true)
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null)
  const refresh = () => qc.invalidateQueries({ queryKey: ['alerts', code] })
  const run = async (f: () => Promise<unknown>, done?: string) => {
    setMsg(null)
    try { await f(); if (done) setMsg({ ok: true, text: done }); refresh() } catch (e) { setMsg({ ok: false, text: errorText(e) }) }
  }
  const add = () => run(async () => {
    await api.post(`/admin/apps/${code}/alerts`, { kind, target, label, includeDegraded: degraded })
    setTarget(''); setLabel('')
  }, `${KIND_TEXT[kind]} channel added. Send a test to check it.`)
  const test = (c: AlertChannel) => run(() => api.post(`/admin/apps/${code}/alerts/${c.id}/test`), `Test sent to ${c.label ?? c.target}.`)
  const remove = (c: AlertChannel) => window.confirm(`Stop sending alerts to ${c.label ?? c.target}?`) && run(() => api.delete(`/admin/apps/${code}/alerts/${c.id}`))
  const toggle = (c: AlertChannel, patch: Partial<AlertChannel>) => run(() => api.patch(`/admin/apps/${code}/alerts/${c.id}`, patch))
  const mute = (minutes: number) => run(() => api.put(`/admin/apps/${code}/alerts/mute`, { minutes }), minutes ? undefined : 'Alerts are on again.')

  const d = q.data
  const muted = d?.mutedUntil
  return <Pan title="3 · Alerts" right={d && <div className="row">
    {muted ? <><span className="badge warn">Paused until {time(muted)}</span><button className="btn sm" onClick={() => mute(0)}>Resume alerts</button></>
      : <><span className="small muted">Pause for planned work:</span>
        {[[60, '1 h'], [240, '4 h'], [1440, '1 day']].map(([m, l]) => <button key={m} className="btn sm" onClick={() => mute(Number(m))}>{l}</button>)}</>}
  </div>}>
    <p className="small mid" style={{ marginTop: 0 }}>A message goes out when this app becomes Down or Degraded and has stayed that way for about a minute, again every
      30 minutes while it stays Down, and once when it is Up again.</p>
    {msg && <div className={msg.ok ? 'ok' : 'err'}>{msg.text}</div>}
    {!d ? <Empty>{q.isError ? errorText(q.error) : 'Loading…'}</Empty> : <>
      {d.channels.length > 0 && <div className="pan" style={{ margin: '0 0 14px' }}><div className="scroll"><table>
        <thead><tr><th>Sends to</th><th>When</th><th>On</th><th /></tr></thead>
        <tbody>{d.channels.map(c => <tr key={c.id}>
          <td><b>{c.label ?? KIND_TEXT[c.kind]}</b> <span className="tag">{KIND_TEXT[c.kind]}</span><div className="mono small muted">{c.target}</div></td>
          <td><select value={c.includeDegraded ? 'all' : 'down'} onChange={e => toggle(c, { includeDegraded: e.target.value === 'all' })} style={{ height: 30, maxWidth: 210 }}
            aria-label={`When to alert ${c.label ?? c.target}`}>
            <option value="all">Down and Degraded</option><option value="down">Down only</option></select></td>
          <td><input type="checkbox" checked={c.enabled} onChange={e => toggle(c, { enabled: e.target.checked })} aria-label={`Send to ${c.label ?? c.target}`} /></td>
          <td><div className="row" style={{ flexWrap: 'nowrap', justifyContent: 'flex-end' }}>
            <button className="btn sm" onClick={() => test(c)}>Send test</button><button className="btn sm danger" onClick={() => remove(c)}>Remove</button></div></td>
        </tr>)}</tbody></table></div></div>}
      {d.channels.length === 0 && <Empty>No one is alerted yet. Add a Slack channel or a WhatsApp number below.</Empty>}

      <div className="row" style={{ alignItems: 'flex-end', marginTop: 4 }}>
        <div style={{ width: 150 }}><label>Send to</label><select value={kind} onChange={e => setKind(e.target.value as 'SLACK' | 'WHATSAPP')}>
          <option value="SLACK">Slack</option><option value="WHATSAPP" disabled={!d.whatsappReady}>WhatsApp{d.whatsappReady ? '' : ' (not set up)'}</option></select></div>
        <div style={{ flex: 2, minWidth: 240 }}><label>{kind === 'SLACK' ? 'Slack incoming-webhook URL' : 'Mobile number, with country code'}</label>
          <input className="mono" value={target} onChange={e => setTarget(e.target.value)}
            placeholder={kind === 'SLACK' ? 'https://hooks.slack.com/services/…' : '+91 98765 43210'} /></div>
        <div style={{ flex: 1, minWidth: 150 }}><label>Name (optional)</label><input value={label} onChange={e => setLabel(e.target.value)}
          placeholder={kind === 'SLACK' ? '#ops-alerts' : 'On-call phone'} /></div>
        <label style={{ marginBottom: 9 }}><input type="checkbox" checked={degraded} onChange={e => setDegraded(e.target.checked)} /> Also when Degraded</label>
        <button className="btn pri" disabled={!target} onClick={add}>Add</button>
      </div>
      <div className="hint">{kind === 'SLACK'
        ? <>In Slack: Apps → Incoming Webhooks → Add to a channel, then paste its URL. The URL is a password, so only its end is shown here afterwards.</>
        : <>Messages come from your WhatsApp Business number, using the approved alert template. See README: Alerts.</>}
        {!d.whatsappReady && kind === 'SLACK' && <> WhatsApp needs <span className="mono">WHATSAPP_TOKEN</span> and <span className="mono">WHATSAPP_PHONE_NUMBER_ID</span> set on the server first.</>}</div>

      {d.recent.length > 0 && <>
        <h4 style={{ margin: '18px 0 8px', fontSize: 14 }}>Recent messages</h4>
        <div className="pan" style={{ margin: 0 }}><div className="scroll"><table>
          <thead><tr><th>When</th><th>What</th><th>To</th><th>Result</th></tr></thead>
          <tbody>{d.recent.map((r, i) => <tr key={i}>
            <td className="small mono">{time(r.at)}</td>
            <td><span className="tag">{SENT_TEXT[r.kind] ?? r.kind}</span> <span className="small">{r.message}</span></td>
            <td className="small">{r.channelLabel ?? (r.channelKind ? KIND_TEXT[r.channelKind as 'SLACK'] : 'Removed channel')}</td>
            <td className="small">{r.ok ? <span className="badge good">Delivered</span> : <><span className="badge bad">Failed</span><div className="muted">{r.error}</div></>}</td>
          </tr>)}</tbody></table></div></div>
      </>}
    </>}
  </Pan>
}

function AppSettings({ app, onSaved, onError }: { app: AppAdmin; onSaved: () => void; onError: (s: string) => void }) {
  const [name, setName] = useState(app.name)
  const [slowMs, setSlowMs] = useState(app.slowMs)
  const [mask, setMask] = useState(app.mask.join('\n'))
  const [rules, setRules] = useState(app.routeRules.map(r => `${r.match} => ${r.replace}`).join('\n'))
  const [saved, setSaved] = useState(false)
  async function save() {
    setSaved(false)
    try {
      await api.patch(`/admin/apps/${app.code}`, {
        name, slowMs: Number(slowMs),
        mask: mask.split('\n').map(s => s.trim()).filter(Boolean),
        routeRules: rules.split('\n').map(s => s.trim()).filter(Boolean).map(l => { const [match, replace] = l.split('=>').map(x => x.trim()); return { match, replace: replace ?? '{x}' } }),
      })
      setSaved(true)
      onSaved()
    } catch (e) { onError(errorText(e)) }
  }
  return <Pan title="4 · App settings (sent to its agents)">
    {saved && <div className="ok">Saved. Agents pick up changes within 5 minutes.</div>}
    <div className="grid2">
      <div className="field"><label>Name</label><input value={name} onChange={e => setName(e.target.value)} /></div>
      <div className="field"><label>Slow request above (ms)</label><input type="number" value={slowMs} onChange={e => setSlowMs(Number(e.target.value))} /></div>
    </div>
    <div className="field"><label>Extra masking patterns (one regex per line)</label><textarea rows={3} value={mask} onChange={e => setMask(e.target.value)} placeholder={'POL-\\d+\n(?i)policy_no=\\w+'} />
      <div className="hint">Emails, mobile numbers, PAN, Aadhaar, card numbers, passwords and tokens are always masked by the agent.</div></div>
    <div className="field"><label>Path rules (one per line: regex =&gt; replacement)</label><textarea rows={3} value={rules} onChange={e => setRules(e.target.value)} placeholder={'^/files/.+$ => /files/{path}'} />
      <div className="hint">Numbers, UUIDs, long hex, references like APP-123 and tokens are already replaced by {'{id}'}, {'{ref}'} or {'{token}'}.</div></div>
    <button className="btn pri" onClick={save}>Save settings</button>
  </Pan>
}

function CheckEditor({ code, check, onClose, onSaved }: { code: string; check: Partial<CheckDef>; onClose: () => void; onSaved: () => void }) {
  const [c, setC] = useState<Partial<CheckDef>>(check)
  const [error, setError] = useState<string | null>(null)
  const cfg = (c.config ?? {}) as Record<string, unknown>
  const setCfg = (k: string, v: unknown) => setC(x => ({ ...x, config: { ...(x.config ?? {}), [k]: v } }))
  async function save() {
    try {
      const body = { name: c.name, kind: c.kind, config: c.config, intervalSec: Number(c.intervalSec), health: c.health, enabled: c.enabled !== false }
      if (c.id) await api.put(`/admin/apps/${code}/checks/${c.id}`, body)
      else await api.post(`/admin/apps/${code}/checks`, body)
      onSaved()
    } catch (e) { setError(errorText(e)) }
  }
  return <div className="modal-bg" onClick={onClose}><div className="modal" onClick={e => e.stopPropagation()}>
    <h3>{c.id ? 'Edit' : 'New'} {c.kind} check</h3>
    {error && <div className="err">{error}</div>}
    <div className="grid2">
      <div className="field"><label>Name</label><input autoFocus value={c.name ?? ''} onChange={e => setC({ ...c, name: e.target.value })}
        placeholder={c.kind === 'HTTP' ? 'Health' : 'Payments pending over 1 hour'} /></div>
      <div className="field"><label>Every (seconds)</label><input type="number" value={c.intervalSec ?? 60} onChange={e => setC({ ...c, intervalSec: Number(e.target.value) })} /></div>
    </div>
    {c.kind === 'HTTP' ? <>
      <div className="field"><label>URL</label><input className="mono" value={String(cfg.url ?? '')} onChange={e => setCfg('url', e.target.value)} placeholder="https://app.example.com/health" /></div>
      <div className="grid2">
        <div className="field"><label>Expected status</label><input type="number" value={Number(cfg.expectStatus ?? 200)} onChange={e => setCfg('expectStatus', Number(e.target.value))} /></div>
        <div className="field"><label>Within (ms)</label><input type="number" value={Number(cfg.maxMs ?? 3000)} onChange={e => setCfg('maxMs', Number(e.target.value))} /></div>
      </div>
      <div className="field"><label>Response must contain (optional)</label><input value={String(cfg.contains ?? '')} onChange={e => setCfg('contains', e.target.value || null)} /></div>
    </> : <>
      <div className="field"><label>Query (one SELECT; each row is one problem)</label>
        <textarea rows={5} value={String(cfg.sql ?? '')} onChange={e => setCfg('sql', e.target.value)}
          placeholder={"SELECT order_no AS ref FROM payments\nWHERE status = 'PENDING' AND created_at < now() - interval '1 hour'"} /></div>
      <div className="grid2">
        <div className="field"><label>Reference column (sent back)</label><input className="mono" value={String(cfg.refColumn ?? '')} onChange={e => setCfg('refColumn', e.target.value)} /></div>
        <div className="field"><label>Fail when more rows than</label><input type="number" value={Number(cfg.warnAbove ?? 0)} onChange={e => setCfg('warnAbove', Number(e.target.value))} /></div>
      </div>
    </>}
    <div className="field"><label><input type="checkbox" checked={!!c.health} onChange={e => setC({ ...c, health: e.target.checked })} /> Health check: when it fails, the app is Down</label></div>
    <div className="field"><label><input type="checkbox" checked={c.enabled !== false} onChange={e => setC({ ...c, enabled: e.target.checked })} /> Enabled</label></div>
    <div className="row"><span className="spacer" /><button className="btn" onClick={onClose}>Cancel</button><button className="btn pri" disabled={!c.name} onClick={save}>Save</button></div>
  </div></div>
}
