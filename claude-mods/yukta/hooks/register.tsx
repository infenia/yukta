import type { EngineInterface as Engine, PluginOptions, Register } from 'claude-code'

type Run = { sessionId: string; executionId: string; workflowId: string; status: string }
type Approval = { sessionId: string; executionId: string; workflowId: string; nodeId: string; name: string }

const PANE = 'yukta-runs'


let opts: PluginOptions = {}
let base = ''
let runs: Run[] = []
let approvals: Approval[] = []
let isDown = true
let skips = 0
let headers: Promise<Record<string, string>> | undefined

const resolveHeaders = async ($: Engine) => {
  const command = String(opts.headers_command)
  if (command) {
    const { exitCode, stdout } = await $.process.run(['sh', '-c', command])
    if (exitCode === 0) return JSON.parse(stdout) as Record<string, string>
  }
  const key = String(opts.api_key)
  return key ? { 'X-API-Key': key } : {}
}

const api = async ($: Engine, path: string, method = 'GET') => {
  headers ??= resolveHeaders($).catch(() => ({}))
  const res = await $.http.fetch(`${base}/api${path}`, { method, headers: await headers })
  if (!res.ok) throw new Error(`${method} ${path}: ${res.status}`)
  const body = res.text ? JSON.parse(res.text) : {}
  return body.data
}

const summary = () =>
  isDown
    ? 'yukta ✗ server unreachable'
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
  } catch {
    runs = []
    approvals = []
    isDown = true
  }
  $.ui.status(summary())
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
  base = String(options.server_url).replace(/\/+$/, '')

  on('session.start', async ($, e, next) => {
    await $.command.register({ name: 'yukta', description: 'Show Yukta runs and approvals in a pane' })
    $.clock.every(Number(options.poll_seconds) * 1000, () => tick($))
    void tick($)
    return next(e)
  })

  on('command.run', { command: 'yukta' }, async $ => {
    await tick($)
    await $.ui.open({ id: PANE, title: 'Yukta' })
    return { text: summary() }
  })

  on('ui.render', { component: 'Pane', requestId: PANE }, async ($, e) => {
    const { Box, Button, Text } = $.ui.resolve(e)
    return (
      <Box flexDirection="column">
        <Text>{summary()}</Text>
        {runs.length === 0 && !isDown && <Text dimColor>No running executions.</Text>}
        {runs.map(r => (
          <Box key={r.executionId}>
            <Text>
              {r.workflowId} {r.executionId.slice(0, 8)} {r.status}{' '}
            </Text>
            <Button key={`stop-${r.executionId}`} label="Stop" onPress={() => stop($, r)} />
          </Box>
        ))}
        {approvals.map(a => (
          <Box key={`${a.executionId}-${a.nodeId}`}>
            <Text>
              {a.workflowId} waits at {a.nodeId}{' '}
            </Text>
            <Button key={`ok-${a.executionId}-${a.nodeId}`} label="Approve" onPress={() => decide($, a, 'approve')} />
            <Button key={`no-${a.executionId}-${a.nodeId}`} label="Reject" onPress={() => decide($, a, 'reject')} />
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
