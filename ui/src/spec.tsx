import type { DbSpec, ServerSpec } from './api'
import { ago, mb, time } from './ui'

const since = (seconds: number, at: number) => {
  const d = Math.floor(seconds / 86400), h = Math.floor((seconds % 86400) / 3600)
  return `${d ? `${d} d ` : ''}${h} h, since ${time(at - seconds * 1000)}`
}

/** Label and value pairs; rows without a value are left out. */
function KV({ rows }: { rows: [string, React.ReactNode, string?][] }) {
  const shown = rows.filter(([, v]) => v != null && v !== '')
  return <table className="kv"><tbody>{shown.map(([k, v, title]) => <tr key={k}><th scope="row" title={title}>{k}</th><td>{v}</td></tr>)}</tbody></table>
}

/** What an app server is: sent by its agent at start and hourly. */
export function ServerSpecTable({ spec, specAt }: { spec?: ServerSpec | null; specAt?: string | null }) {
  if (!spec) return <div className="empty">No specification yet. Agents from version 0.1.1 send it when they start.</div>
  const at = spec.at ?? (specAt ? Date.parse(specAt) : Date.now())
  return <KV rows={[
    ['Hostname', spec.hostname],
    ['Operating system', spec.os],
    ['Kernel', spec.kernel],
    ['CPU', spec.cpuModel && <>{spec.cpuModel}<span className="muted">, {spec.cores} cores ({spec.arch})</span></>],
    ['CPU cores', !spec.cpuModel ? spec.cores : null],
    ['Memory', spec.memTotalMb != null && <>{mb(spec.memTotalMb)}{spec.swapTotalMb ? <span className="muted">, swap {mb(spec.swapTotalMb)}</span> : null}</>],
    ['Disks', spec.disks?.length ? <>{spec.disks.map(d => <div key={d.path}><span className="mono">{d.path}</span> {mb(d.totalMb)}
      <span className="muted"> {d.type}{d.device ? `, ${d.device}` : ''}</span></div>)}</> : null],
    ['Runs on', spec.virtualization],
    ['IP addresses', spec.addresses?.length ? <span className="mono">{spec.addresses.join(', ')}</span> : null],
    ['Up for', spec.uptimeSeconds != null ? since(spec.uptimeSeconds, at) : null],
    ['Time zone', spec.timezone],
    ['Java (agent)', spec.java],
    ['Reported', ago(at)],
  ]} />
}

const LABEL: Record<string, string> = {
  max_connections: 'Max connections', shared_buffers: 'Shared buffers', effective_cache_size: 'Effective cache size', work_mem: 'Work memory',
  maintenance_work_mem: 'Maintenance memory', wal_level: 'WAL level', max_wal_size: 'Max WAL size', max_worker_processes: 'Worker processes',
  max_parallel_workers: 'Parallel workers', autovacuum: 'Autovacuum', ssl: 'SSL', have_ssl: 'SSL', server_encoding: 'Encoding', TimeZone: 'Time zone',
  shared_preload_libraries: 'Preloaded libraries', data_checksums: 'Data checksums', synchronous_commit: 'Synchronous commit', statement_timeout: 'Statement timeout',
  innodb_buffer_pool_size: 'InnoDB buffer pool', innodb_redo_log_capacity: 'Redo log capacity', innodb_log_file_size: 'Log file size',
  max_allowed_packet: 'Max packet', tmp_table_size: 'Temp table size', max_heap_table_size: 'Max memory table', key_buffer_size: 'Key buffer',
  table_open_cache: 'Table open cache', thread_cache_size: 'Thread cache', character_set_server: 'Character set', collation_server: 'Collation',
  time_zone: 'Time zone', system_time_zone: 'System time zone', read_only: 'Read only', super_read_only: 'Super read only', log_bin: 'Binary log',
  binlog_format: 'Binlog format', server_id: 'Server id', slow_query_log: 'Slow query log', long_query_time: 'Slow above (s)', port: 'Port',
  innodb_flush_log_at_trx_commit: 'Flush log at commit', transaction_isolation: 'Isolation level',
}

/** The database server: version, role, where it runs, and its main settings. */
export function DbSpecTable({ engine, version, spec, appServer }: { engine: string; version: string; spec?: DbSpec; appServer?: string }) {
  if (!spec) return <div className="empty">No specification yet. Agents from version 0.1.1 send it with the database snapshot.</div>
  return <>
    <KV rows={[
      ['Product', <>{spec.product ?? engine} {version}</>],
      ['Build', spec.versionFull && <span className="small">{spec.versionFull}</span>],
      ['Role', spec.role],
      ['Address', spec.host && <><span className="mono">{spec.host}{spec.port ? `:${spec.port}` : ''}</span>
        {spec.sameServer && <span className="muted"> (same server as the app{appServer ? `, ${appServer}` : ''})</span>}</>],
      ['Server hostname', spec.serverHostname],
      ['Database', spec.database && <>{spec.database}{spec.databases ? <span className="muted">, {spec.databases} on this server</span> : null}</>],
      ['Running since', spec.startedAt && <>{time(spec.startedAt)} <span className="muted">({ago(spec.startedAt).replace(' ago', '')})</span></>],
      ['Extensions', spec.extensions],
    ]} />
    {spec.settings && Object.keys(spec.settings).length > 0 && <>
      <div className="kv-h">Settings</div>
      <KV rows={Object.entries(spec.settings).map(([k, v]) => [LABEL[k] ?? k, <span className="mono">{String(v)}</span>, k])} />
    </>}
    {spec.note && <div className="hint" style={{ padding: '0 10px 8px' }}>{spec.note}</div>}
  </>
}
