import type { EngineInterface as Engine, PluginOptions, Register } from 'claude-code'

type Run = { sessionId: string; executionId: string; workflowId: string; status: string; startTime?: string }
type Insights = { queued: number; failed: number; sample: number; successPct: number; medianSec: number; top: string }
type Approval = { sessionId: string; executionId: string; workflowId: string; nodeId: string; name: string }

const PANE = 'yukta-runs'
const TITLE = ['#a78bfa', '#818cf8', '#60a5fa', '#22d3ee', '#34d399']
const SPIN = ['⠋', '⠙', '⠹', '⠸', '⠼', '⠴', '⠦', '⠧', '⠇', '⠏']
const TIPS = [
  'Define a workflow once as JSON nodes and edges, then run it from REST, the web UI or MCP.',
  'An approval step waits for a signal: approve it here, or from the band above the prompt.',
  'Swagger UI lives at /swagger-ui.html on the server.',
  'Extend Yukta with Trigger, Processor and Terminal plugins through plugin-api.',
  'Stop ends a run and every sub-workflow it started.',
  'Workflow versions can be diffed and rolled back: ask Claude for diff_workflow_versions or rollback_workflow.',
  'Claude can start, pause and inspect workflows through the Yukta MCP tools.',
]
const AMBER = ['#f59e0b', '#fcd34d']

// ponytail: the pane animates by redrawing at ~8 fps while it is on screen and work runs; a push channel would not need the timer
let frame = 0
let nowMs = 0
let lastDraw = 0

const elapsed = (startTime: string | undefined) => {
  const s = startTime ? Math.max(0, Math.round((nowMs - Date.parse(startTime)) / 1000)) : 0
  return s >= 60 ? `${Math.floor(s / 60)}m${String(s % 60).padStart(2, '0')}s` : `${s}s`
}

// A bright block sweeping across a dim track.
const bar = (width: number) => {
  const at = Math.floor(frame / 2) % (width + 6)
  return Array.from({ length: width }, (_, i) => (i >= at - 5 && i <= at ? '█' : i < at - 5 ? '▓' : '░')).join('')
}


let opts: PluginOptions = {}
let base = ''
let runs: Run[] = []
let approvals: Approval[] = []
let isDown = true
let why = ''
let insights: Insights | undefined
let ticks = 0
let skips = 0
let headers: Promise<Record<string, string>> | undefined

const resolveHeaders = async ($: Engine) => {
  const command = String(opts.headers_command ?? '')
  if (command) {
    const { exitCode, stdout } = await $.process.run(['sh', '-c', command])
    if (exitCode === 0) return JSON.parse(stdout) as Record<string, string>
  }
  const key = String(opts.api_key ?? '')
  return key ? { 'X-API-Key': key } : {}
}

const api = async ($: Engine, path: string, method = 'GET') => {
  headers ??= resolveHeaders($).catch(() => ({}))
  const res = await $.http.fetch(`${base}/api${path}`, { method, headers: await headers })
  if (res.status === 401 || res.status === 403) throw new Error(`HTTP ${res.status}: set api_key or headers_command in /config`)
  if (!res.ok) throw new Error(`${method} ${path}: HTTP ${res.status}`)
  const body = res.text ? JSON.parse(res.text) : {}
  return body.data
}

const summary = () =>
  isDown
    ? `yukta ✗ ${why || 'server unreachable'}`
    : `yukta ✓ ${runs.length} running` + (approvals.length ? ` · ${approvals.length} awaiting approval` : '')

const tick = async ($: Engine) => {
  // ponytail: while the server is down, ask every 10th tick; a push channel would end polling
  if (isDown && skips++ % 10) return
  try {
    const running: Run[] = (await api($, '/executions?status=RUNNING&limit=50')) ?? []
    const sessions = [...new Set(running.map(r => r.sessionId))]
    const pending: Approval[] = []
    for (const sessionId of sessions) {
      const list = (await api($, `/sessions/${encodeURIComponent(sessionId)}/approvals`)) ?? []
      pending.push(...list.map((a: Approval) => ({ ...a, sessionId })))
    }
    const stillRunning = new Set(running.map(r => r.executionId))
    for (const gone of runs.filter(r => !stillRunning.has(r.executionId))) {
      const [hit] = (await api($, `/executions?text=${encodeURIComponent(gone.executionId)}&limit=1`)) ?? []
      $.ui.toast(`yukta: ${gone.workflowId} ${hit?.status ?? 'ended'}`)
    }
    const knownWaits = new Set(approvals.map(a => `${a.executionId}/${a.nodeId}`))
    for (const wait of pending.filter(a => !knownWaits.has(`${a.executionId}/${a.nodeId}`))) {
      $.ui.toast(`yukta: ${wait.workflowId} waits for approval at ${wait.nodeId}`)
    }
    runs = running
    approvals = pending
    isDown = false
    why = ''
    if (ticks++ % 10 === 0) await loadInsights($).catch(() => undefined)
  } catch (err) {
    why = err instanceof Error ? err.message : 'request failed'
    runs = []
    approvals = []
    isDown = true
  }
  if (opts.show_status) $.ui.status(summary())
  $.ui.invalidate('ui.render')
}

const median = (xs: number[]) => [...xs].sort((a, b) => a - b)[Math.floor(xs.length / 2)] ?? 0

// ponytail: sampled from the newest 50 runs every ~30 s; a stats endpoint would be exact
const loadInsights = async ($: Engine) => {
  const counts = await api($, '/executions/counts')
  const hits: Run[] & { endTime?: string }[] = (await api($, '/executions?limit=50')) ?? []
  const done = hits.filter((h: any) => h.endTime && h.startTime)
  const byFlow = new Map<string, number>()
  for (const h of hits) byFlow.set(h.workflowId, (byFlow.get(h.workflowId) ?? 0) + 1)
  insights = {
    queued: counts?.queued ?? 0,
    failed: counts?.failed ?? 0,
    sample: hits.length,
    successPct: hits.length ? Math.round((100 * hits.filter(h => h.status === 'SUCCESS').length) / hits.length) : 0,
    medianSec: Math.round(median(done.map((h: any) => (Date.parse(h.endTime) - Date.parse(h.startTime)) / 1000))),
    top: [...byFlow.entries()].sort((a, b) => b[1] - a[1])[0]?.[0] ?? '-',
  }
}

const animate = async ($: Engine) => {
  nowMs = await $.clock.now()
  // only while the pane drew within the last 2 s and there is work to show
  if (nowMs - lastDraw > 2000) return
  frame += 1
  $.ui.invalidate('ui.render')
}

const decide = async ($: Engine, a: Approval, decision: 'approve' | 'reject') => {
  const q = `name=${encodeURIComponent(a.name)}&decision=${decision}`
  await api($, `/executions/${a.executionId}/nodes/${a.nodeId}/signal?${q}`, 'POST')
  await tick($)
}

const stop = async ($: Engine, r: Run) => {
  await api($, `/executions/${r.executionId}/stop?tree=true`, 'POST')
  await tick($)
}


export const register: Register = (on, options) => {
  opts = options
  base = String(options.server_url || 'http://localhost:9858').replace(/\/+$/, '')

  on('session.start', async ($, e, next) => {
    await $.command.register({ name: 'yukta', description: 'Show Yukta runs and approvals in a pane' })
    $.clock.every((Number(options.poll_seconds) || 3) * 1000, () => tick($))
    $.clock.every(120, () => animate($))
    // an entry an earlier version (or show_status) left behind stays until it is cleared
    if (!opts.show_status) $.ui.status(undefined)
    void tick($)
    return next(e)
  })

  on('command.run', { command: 'yukta' }, async $ => {
    nowMs = lastDraw = await $.clock.now()
    await tick($)
    await $.ui.open({ id: PANE, title: 'Yukta' })
    return { text: summary() }
  })

  on('ui.render', { component: 'Pane', requestId: PANE }, async ($, e) => {
    const { Box, Button, Text } = $.ui.resolve(e)
    lastDraw = nowMs
    const width = Math.max(10, Math.min(48, (e.props.bodyColumns ?? 60) - 8))
    const wait = AMBER[Math.floor(frame / 4) % 2]
    return (
      <Box flexDirection="column" paddingX={1}>
        <Box>
          <Text bold color="#a78bfa">▲ </Text>
          {[...'YUKTA'].map((c, i) => (
            <Text bold color={TITLE[(i + Math.floor(frame / 3)) % TITLE.length]}>{c}</Text>
          ))}
          <Text dimColor>  live runs</Text>
        </Box>
        <Box marginTop={1}>
          <Text color={isDown ? '#f87171' : '#34d399'}>{isDown ? '○ offline' : frame % 16 < 12 ? '● online' : '◉ online'}</Text>
          <Text dimColor>  ·  </Text>
          <Text color="#818cf8" bold>{runs.length}</Text>
          <Text dimColor> running  ·  </Text>
          <Text color={approvals.length ? wait : undefined} bold={approvals.length > 0} dimColor={!approvals.length}>
            {approvals.length} awaiting approval
          </Text>
        </Box>
        {!isDown && runs.length === 0 && approvals.length === 0 && (
          <Box marginTop={1}><Text dimColor italic>✦ all quiet — nothing is running</Text></Box>
        )}
        {!isDown && insights && (
          <Box marginTop={1} flexDirection="column" borderStyle="round" borderColor="#3b82f6" paddingX={1}>
            <Text bold color="#60a5fa">◆ Insights <Text dimColor>(last {insights.sample} runs)</Text></Text>
            <Text>
              <Text color={insights.successPct >= 90 ? '#34d399' : insights.successPct >= 60 ? '#fcd34d' : '#f87171'} bold>{insights.successPct}%</Text>
              <Text dimColor> succeeded  ·  median </Text>{insights.medianSec}s
              <Text dimColor>  ·  </Text>{insights.queued}<Text dimColor> queued</Text>
            </Text>
            <Text>
              <Text color={insights.failed ? '#f87171' : undefined} dimColor={!insights.failed}>{insights.failed} failed in 24h</Text>
              <Text dimColor>  ·  busiest </Text>{insights.top}
            </Text>
          </Box>
        )}
        <Box marginTop={1}>
          <Text color="#22d3ee">✦ </Text>
          <Text italic dimColor>{TIPS[Math.floor(frame / 100) % TIPS.length]}</Text>
        </Box>
        {isDown && <Box marginTop={1}><Text color="#f87171">✗ {base}: {why || 'unreachable'}</Text></Box>}
        {runs.map(r => (
          <Box
            key={r.executionId}
            marginTop={1}
            flexDirection="column"
            borderStyle="round"
            borderColor="#6366f1"
            paddingX={1}
            hover={{ borderColor: '#22d3ee' }}
          >
            <Box>
              <Text bold color="#a78bfa">{SPIN[frame % SPIN.length]} </Text>
              <Text bold>{r.workflowId}</Text>
              <Text dimColor>  {r.executionId.slice(0, 8)}  ⏱ {elapsed(r.startTime)}</Text>
            </Box>
            <Text color="#6366f1">{bar(width)}</Text>
            <Box>
              <Text color="#34d399">{r.status.toLowerCase()}  </Text>
              <Button key={`stop-${r.executionId}`} label="■ Stop" onPress={() => stop($, r)} />
            </Box>
          </Box>
        ))}
        {approvals.map(a => (
          <Box
            key={`${a.executionId}-${a.nodeId}`}
            marginTop={1}
            flexDirection="column"
            borderStyle="double"
            borderColor={wait}
            paddingX={1}
          >
            <Text bold color={wait}>⏸ approval needed</Text>
            <Text>
              {a.workflowId} <Text dimColor>at</Text> {a.nodeId} <Text dimColor>({a.name})</Text>
            </Text>
            <Box>
              <Button key={`ok-${a.executionId}-${a.nodeId}`} label="✓ Approve" onPress={() => decide($, a, 'approve')} />
              <Text>  </Text>
              <Button key={`no-${a.executionId}-${a.nodeId}`} label="✗ Reject" onPress={() => decide($, a, 'reject')} />
            </Box>
          </Box>
        ))}
      </Box>
    )
  })

  on('ui.render', { component: 'AbovePrompt' }, async ($, e, next) => {
    const a = approvals[0]
    if (e.props.hasSurvey || !a) return next(e)
    const { Box, Button, Text } = $.ui.resolve(e)
    return (
      <Box>
        <Text>
          Approval: {a.workflowId} at {a.nodeId}
          {approvals.length > 1 ? ` (+${approvals.length - 1})` : ''}{' '}
        </Text>
        <Button key="band-ok" label="Approve" onPress={() => decide($, a, 'approve')} />
        <Button key="band-no" label="Reject" onPress={() => decide($, a, 'reject')} />
      </Box>
    )
  })
}
