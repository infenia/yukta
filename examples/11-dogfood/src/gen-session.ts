// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// Writes the generated workflows of session.json: tool, claude, attempt, heal, check and pickup (the hand-written rest stays as it is).
//
//   tool       run one shell command in one project (src/tool.ts)
//   check      one SUB_WORKFLOW call of `tool` per stage, in STAGES order (cheap first). `yk check` puts each stage's command in the start payload
//              (cmd.<stage>, from src/plan.ts); a stage without a command passes at once. Before the first Gradle stage a `fast` node runs all the
//              Gradle tasks of the plan as one invocation (start.fast, empty = off, yk check --no-fast); when it passes, the Gradle stages run a
//              no-op (start.chain.<stage>), when it fails they run as usual. A failed stage goes to the `heal` workflow; a healed stage goes on
//              with the next one; a failed heal ends the run with outcome FAILED, reason = the stage (failed_node). licenses and security are never
//              healed. After the last stage, when anything was healed, `verify` runs the Gradle tasks of the stages again.
//   claude     L0  one headless Claude Code session (src/claude.ts)
//   attempt    L1  one repair attempt: prepare -> claude -> claimed -> format -> judge (the guard) -> recheck -> report
//   heal       L1  free automatic fix, then the System One triage picks a level; each level of heal.json has a ladder of rungs (model/effort, one
//                  attempt each): a failed rung hands over to the next, the end of the ladder gives up. The efforts (budget, time box), the triage
//                  levels and the triage model come from heal.json.
//   pickup     a SCHEDULE_TRIGGER every INTERVAL_MS that runs `pickup tick`, which hands the next assigned, Ready issue to the yukta-pickup channel.
//
//   bun src/gen-session.ts           write session.json       bun src/gen-session.ts --check   exit 1 when the committed session.json is stale
// HEAL_SESSION and HEAL_POLICY point the generator at another session file and policy (the tests do). Edit this file or heal.json, run it, commit session.json.
import { HERE, type Json, existsSync, join, readFileSync, writeFileSync } from './lib.ts'

const PATH = process.env.HEAL_SESSION || join(HERE, 'session.json')
const BUN = 'bun'
const HEAL = ['__DOGFOOD__/src/heal.ts']
const S = (k: string): string => `\${start.${k}}`
type Edge = { source: string; target: string; sourcePort?: string }
const edges = (src: string, dst: string, ...ports: string[]): Edge[] => (ports.length ? ports.map(p => ({ source: src, target: dst, sourcePort: p })) : [{ source: src, target: dst }])
const cmd = (file: string, ...args: string[]): string[] => [BUN, file, ...args]

// ---------------------------------------------------------------- tool
export function toolWorkflow(wf: Json): Json {
  const run = wf.nodes.find((n: Json) => n.nodeId === 'run')
  run.config.command = cmd('__DOGFOOD__/src/tool.ts')
  return wf
}

// ---------------------------------------------------------------- check
const STAGES = ['reuse', 'actionlint', 'docs', 'spotless', 'cli_fmt_check', 'cli_mod_verify', 'ui_prettier', 'compile', 'cli_vet', 'ui_tsc', 'ui_eslint', 'ui_headers', 'style', 'spotbugs', 'cli_lint',
  'cli_sec', 'tests', 'ui_vitest', 'coverage', 'cli_cover_check', 'cli_vuln', 'licenses', 'security', 'ui_build']
const FAST_BEFORE = 'spotless' // the fast path runs in front of the first Gradle stage
const NO_HEAL = new Set(['licenses', 'security']) // a model may not fix these
const PASS = ['project', 'healDir', 'modules', 'events', 'models', 'timeout', 'rules', 'claudeBin', 'triage', 'triageModel', 'triageUrl', 'heal', 'budgetCheck']

function toolCall(stage: string, nodeId: string, command?: string): Json {
  return {
    nodeId, type: 'SUB_WORKFLOW', config: {
      workflowId: 'tool', timeoutSeconds: 3720, routeByOutcome: true,
      // the chain runs start.chain.<stage>: the stage's command, or a no-op once the fast path has passed the Gradle stages
      payload: { project: '${start.project}', command: command ?? `\${start.chain.${stage}}`, timeout: '${start.timeout}', label: stage },
    },
  }
}
const failedNode = (stage: string): Json => ({ nodeId: stage + '.failed', type: 'OUTCOME_TERMINAL', config: { outcome: 'FAILED', reason: stage } })
const PROJECT_STDOUT = { mode: 'PROJECTION', mapping: { stdout: "payload['results'] == null ? '' : (payload['results'].size() > 0 ? payload['results'][0]['stdout'] : '')" } }

export function checkWorkflow(): Json {
  const nodes: Json[] = [{ nodeId: 'trigger', type: 'CONSTANT_SOURCE', config: {} }]
  const es: Edge[] = []
  let exits: [string, string | null][] = [['trigger', null]] // what the next stage hangs on: (node, port)
  const edge = (src: string, dst: string, port: string | null = null): void => void es.push({ source: src, target: dst, ...(port ? { sourcePort: port } : {}) })
  for (const s of STAGES) {
    if (s === FAST_BEFORE) {
      // the fast path: every Gradle task of the plan in one invocation. A pass makes the Gradle stages below no-ops; a failure is not an outcome of
      // the run, it only means the staged chain runs, so the failing stage is found, healed and reported as ever.
      nodes.push(toolCall('fast', 'fast', '${start.fast}'))
      for (const [src, port] of exits) edge(src, 'fast', port)
      exits = [['fast', 'success'], ['fast', 'failure']]
    }
    nodes.push(toolCall(s, s))
    for (const [src, port] of exits) edge(src, s, port)
    nodes.push(failedNode(s))
    if (NO_HEAL.has(s)) {
      edge(s, s + '.failed', 'failure')
      exits = [[s, 'success']]
      continue
    }
    // a payload value "${payload}" reaches a sub-workflow as text, not as the map (gap G11): the failed stage's output goes as a string
    nodes.push({ nodeId: s + '.fail', type: 'MAPPER', config: PROJECT_STDOUT })
    nodes.push({
      nodeId: s + '.heal', type: 'SUB_WORKFLOW', config: {
        workflowId: 'heal', timeoutSeconds: 21600, routeByOutcome: true,
        payload: { ...Object.fromEntries(PASS.map(k => [k, S(k)])), stdout: '${payload.stdout}', stage: s, command: `\${start.cmd.${s}}`, format: `\${start.fmt.${s}}`, auto: `\${start.auto.${s}}` },
      },
    })
    edge(s, s + '.fail', 'failure')
    edge(s + '.fail', s + '.heal')
    edge(s + '.heal', s + '.failed', 'failure')
    exits = [[s, 'success'], [s + '.heal', 'success']]
  }
  // verify: only when something was healed, the Gradle tasks of every stage once more (a fix may break a stage that ran before it)
  nodes.push({
    nodeId: 'healed?', type: 'PROCESS_EXECUTOR', config: {
      command: cmd('__DOGFOOD__/src/heal.ts', 'healed'), workingDir: '${start.project}', env: { YUKTA_HEAL_DIR: '${start.healDir}', YUKTA_STAGE: '-' },
      timeout: 60, timeoutSeconds: 60, failureMode: 'CONTINUE', routeByExitCode: true,
    },
  })
  for (const [src, port] of exits) edge(src, 'healed?', port)
  nodes.push(toolCall('verify', 'verify', '${start.verifyCommand}'))
  nodes.push(failedNode('verify'))
  // a failed verify goes back to heal (stage "verify", the same Gradle tasks as its command), like any stage
  nodes.push({ nodeId: 'verify.fail', type: 'MAPPER', config: PROJECT_STDOUT })
  nodes.push({
    nodeId: 'verify.heal', type: 'SUB_WORKFLOW', config: {
      workflowId: 'heal', timeoutSeconds: 21600, routeByOutcome: true,
      payload: { ...Object.fromEntries(PASS.map(k => [k, S(k)])), stdout: '${payload.stdout}', stage: 'verify', command: '${start.verifyCommand}', format: '${start.fmt.spotless}', auto: '-' },
    },
  })
  nodes.push({ nodeId: 'done', type: 'CONSOLE_TERMINAL', config: { displayOutput: true } })
  edge('healed?', 'verify', 'success')
  edge('healed?', 'done', 'failure')
  edge('verify', 'done', 'success')
  edge('verify', 'verify.fail', 'failure')
  edge('verify.fail', 'verify.heal')
  edge('verify.heal', 'done', 'success')
  edge('verify.heal', 'verify.failed', 'failure')
  return { workflowId: 'check', description: 'The scoped, self-healing quality gate: one tool call per planned stage, a failed stage goes to heal (yk check)', nodes, edges: es }
}

// ---------------------------------------------------------------- pickup
const INTERVAL_MS = 30 * 60 * 1000
export function pickupWorkflow(): Json {
  return {
    workflowId: 'pickup', description: 'Scheduled pickup: every 30 minutes hand the next assigned, Ready issue to the yukta-pickup channel (yk pickup on)',
    nodes: [
      { nodeId: 'every', type: 'SCHEDULE_TRIGGER', config: { intervalMs: INTERVAL_MS, timeoutSeconds: Math.floor(INTERVAL_MS / 1000) + 600 } },
      { nodeId: 'tick', type: 'PROCESS_EXECUTOR', config: { command: cmd('__DOGFOOD__/src/pickup.ts', 'tick'), workingDir: '__PROJECT_PATH__', timeout: 120, failureMode: 'CONTINUE', routeByExitCode: true } },
      { nodeId: 'ok', type: 'CONSOLE_TERMINAL', config: { displayOutput: true } },
      { nodeId: 'failed', type: 'CONSOLE_TERMINAL', config: { displayOutput: true } },
    ],
    edges: [{ source: 'every', target: 'tick' }, { source: 'tick', target: 'ok', sourcePort: 'success' }, { source: 'tick', target: 'failed', sourcePort: 'failure' }],
  }
}

// ---------------------------------------------------------------- claude, attempt, heal
export function healWorkflows(policy: Json): { claude: Json; attempt: Json; heal: Json } {
  const EFFORTS: Record<string, { budget: number; timeout: number }> = Object.fromEntries(Object.entries<Json>(policy.efforts).map(([e, c]) => [e, { budget: c.budget, timeout: c.timeoutSeconds }]))
  const LEVELS: Record<string, Json> = policy.triage.levels
  // optional labels that end the heal with no fixer (e.g. a failure caused by the machine, not the change): name -> {describe, reason}
  const STOPS: Record<string, Json> = policy.triage.stop ?? {}
  const SINGLE: string[] = policy.singleAttempt ?? []
  const FIX_SCHEMA = '{"type":"object","properties":{"fixed":{"type":"boolean"},"summary":{"type":"string"}},"required":["fixed","summary"]}'
  // what every layer passes on to the next: heal -> attempt
  const KEYS = ['project', 'stage', 'command', 'format', 'modules', 'healDir', 'events', 'models', 'timeout', 'rules', 'claudeBin', 'budgetCheck']
  const passed = (keys: string[]): Json => Object.fromEntries(keys.map(k => [k, S(k)]))
  const ROUTE = { engine: { onError: 'ROUTE' } }

  const toolCallCfg = (command: string, label: string, extra: Json = {}): Json => ({
    workflowId: 'tool', timeoutSeconds: 3720, routeByOutcome: true, payload: { project: S('project'), command, timeout: S('timeout'), label }, ...extra,
  })
  const pe = (nodeId: string, action: string, env: Json = {}, o: { stdin?: boolean; json?: boolean; timeout?: number; routed?: boolean; envIn?: boolean } = {}): Json => {
    const timeout = o.timeout ?? 120
    const cfg: Json = { command: cmd(HEAL[0]!, action), workingDir: S('project'), env, timeout, timeoutSeconds: timeout }
    if (o.stdin) cfg.inputMode = 'STDIN'
    if (o.envIn) cfg.inputMode = 'ENV' // the dead letter's failure.message arrives as YUKTA_METADATA_FAILURE_MESSAGE, the brief as YUKTA_PAYLOAD
    if (o.json) cfg.outputFormat = 'JSON'
    Object.assign(cfg, o.routed ? { failureMode: 'CONTINUE', routeByExitCode: true } : { failureMode: 'CONTINUE' })
    return { nodeId, type: 'PROCESS_EXECUTOR', config: cfg }
  }
  const healEnv = (extra: Json = {}): Json => ({ YUKTA_HEAL_DIR: S('healDir'), YUKTA_STAGE: S('stage'), YUKTA_MODULE: S('modules'), YUKTA_HEAL_EVENTS: S('events'), ...extra })
  /** An expression that reads the result of a SUB_WORKFLOW safely: results may be missing (the message came through the error port, so it is the
   * node's own input) or empty (the child execution crashed). */
  const fromResult = (path: string, onError: string, onCrash: string): string => `payload['results'] == null ? ${onError} : (payload['results'].size() > 0 ? payload['results'][0]${path} : ${onCrash})`
  const carry = (path: string): Json => ({
    mode: 'PROJECTION', mapping: {
      problems: fromResult(path + "['problems']", "payload['problems']", "'The previous attempt did not finish: ' + payload['reason']"),
      previous: fromResult(path + "['summary']", "'The previous attempt ran out of time.'", "''"),
      changed: fromResult(path + "['changed']", 'true', 'true'),
      same: fromResult(path + "['same']", 'false', 'false'),
      stage: fromResult(path + "['stage']", "''", "''"),
    },
  })

  // ---- claude (L0)
  const CLAUDE_WF: Json = {
    workflowId: 'claude', description: 'One headless Claude Code session in a project; returns its JSON answer and what it cost.',
    nodes: [
      { nodeId: 'trigger', type: 'CONSTANT_SOURCE', config: { variables: { prompt: S('prompt') } } },
      {
        nodeId: 'run', type: 'PROCESS_EXECUTOR', config: {
          command: cmd('__DOGFOOD__/src/claude.ts'), workingDir: S('project'), inputMode: 'STDIN', outputFormat: 'JSON',
          env: { YK_MODEL: S('model'), YK_EFFORT: S('effort'), YK_BUDGET: S('budget'), YK_RULES: S('rules'), YK_SCHEMA: S('schema'), YK_SESSION: S('sessionId'), YK_CLAUDE_BIN: S('claudeBin'), YK_STAGE: S('stage') },
          timeout: '${start.timeout:int}', failureMode: 'CONTINUE', routeByExitCode: true, engine: { concurrencyGroup: 'claude' },
        },
      },
      { nodeId: 'ok', type: 'CONSOLE_TERMINAL', config: {} },
      { nodeId: 'failed', type: 'OUTCOME_TERMINAL', config: { outcome: 'FAILED', reason: 'the claude session failed, ran out of budget or ran out of time' } },
    ],
    edges: [...edges('trigger', 'run'), ...edges('run', 'ok', 'success'), ...edges('run', 'failed', 'failure')],
  }

  // ---- attempt (L1)
  const claudeCall: Json = {
    workflowId: 'claude', timeoutSeconds: 7200, routeByOutcome: true, engine: { onError: 'ROUTE' },
    payload: {
      project: S('project'), prompt: '${payload.output.prompt}', model: S('model'), effort: S('effort'), budget: S('budget'), timeout: S('claudeTimeout'), rules: S('rules'), schema: FIX_SCHEMA,
      sessionId: '-', claudeBin: S('claudeBin'), stage: S('stage'),
    },
  }
  const aEnv = { YUKTA_MODEL: S('model'), YUKTA_EFFORT: S('effort'), YUKTA_COMMAND: S('command') }
  const ATTEMPT_WF: Json = {
    workflowId: 'attempt', description: 'One repair attempt: one Claude session at one model and effort, judged by re-running the failed stage.',
    nodes: [
      { nodeId: 'trigger', type: 'CONSTANT_SOURCE', config: { variables: { problems: S('problems'), previous: S('previous') } } },
      pe('prepare', 'prepare', healEnv({ ...aEnv, YUKTA_MODELS: S('models'), YUKTA_BUDGET_CHECK: S('budgetCheck'), YUKTA_HEAL_TIMEOUT: S('claudeTimeout') }), { stdin: true, json: true, routed: true }),
      { nodeId: 'claude', type: 'SUB_WORKFLOW', config: claudeCall },
      pe('claimed', 'claimed', healEnv(aEnv), { stdin: true, timeout: 60 }),
      { nodeId: 'format', type: 'SUB_WORKFLOW', config: { ...toolCallCfg(S('format'), 'format'), ...ROUTE } },
      pe('judge', 'judge', healEnv({ ...aEnv, YUKTA_PROBLEMS: S('problems') }), { routed: true }),
      { nodeId: 'recheck', type: 'SUB_WORKFLOW', config: { ...toolCallCfg(S('command'), S('stage')), ...ROUTE } },
      pe('report', 'report', healEnv({ ...aEnv, YUKTA_PROBLEMS: '${start.problems}' }), { stdin: true, json: true }),
      { nodeId: 'fixed?', type: 'BRANCH', config: { mode: 'SELECT_KEY', selector: "payload['output']['fixed']", cases: { true: 'fixed' }, defaultPort: 'notfixed' } },
      { nodeId: 'ok', type: 'CONSOLE_TERMINAL', config: {} },
      { nodeId: 'failed', type: 'OUTCOME_TERMINAL', config: { outcome: 'FAILED', reason: 'the attempt did not fix the stage' } },
    ],
    edges: [
      ...edges('trigger', 'prepare'), ...edges('prepare', 'claude', 'success'), ...edges('prepare', 'report', 'failure'),
      ...edges('claude', 'claimed', 'success', 'failure', 'error'), ...edges('claimed', 'format'),
      ...edges('format', 'judge', 'success', 'failure', 'error'), ...edges('judge', 'recheck', 'success'), ...edges('judge', 'report', 'failure'),
      ...edges('recheck', 'report', 'success', 'failure', 'error'), ...edges('report', 'fixed?'), ...edges('fixed?', 'ok', 'fixed'), ...edges('fixed?', 'failed', 'notfixed'),
    ],
  }
  const attemptCall = (m: string, e: string): Json => ({
    workflowId: 'attempt', timeoutSeconds: 7200, routeByOutcome: true, engine: { onError: 'ROUTE' },
    payload: { ...passed(KEYS), model: m, effort: e, budget: EFFORTS[e]!.budget, claudeTimeout: EFFORTS[e]!.timeout, problems: '${payload.problems}', previous: '${payload.previous}' },
  })

  // ---- heal (L1)
  const rid = (m: string, e: string): string => `fix-${m}-${e}`
  // Each level has a ladder of rungs "model/effort", one attempt each (no effort climbing): the first is where the triage starts, a failed rung hands over
  // to the next one, and the end of the ladder gives up. Rungs shared by levels share a node (and must share the rest of the ladder).
  const rungs = new Map<string, Json>()
  const nxt = new Map<string, [string, string] | null>()
  for (const c of Object.values(LEVELS)) {
    const steps = (c.ladder as string[]).map(x => x.split('/') as [string, string])
    steps.forEach((st, k) => {
      const key = st.join('/')
      if (!rungs.has(key)) rungs.set(key, { nodeId: rid(...st), type: 'SUB_WORKFLOW', config: attemptCall(...st) })
      const after = k + 1 < steps.length ? steps[k + 1]! : null
      if (!nxt.has(key)) nxt.set(key, after)
      else if ((nxt.get(key)?.join('/') ?? null) !== (after?.join('/') ?? null)) throw new Error(`rung ${key} has two different successors`)
    })
  }
  const starts: Record<string, string> = Object.fromEntries(Object.entries(LEVELS).map(([lvl, c]) => [lvl, rid(...((c.ladder[0] as string).split('/') as [string, string]))]))
  const avg = starts[policy.triage.default]!
  const nodes: Json[] = [
    { nodeId: 'trigger', type: 'CONSTANT_SOURCE', config: { variables: { stdout: S('stdout'), heal: S('heal'), auto: S('auto') } } },
    { nodeId: 'heal?', type: 'BRANCH', config: { mode: 'SELECT_KEY', selector: "payload['heal']", cases: { off: 'off' }, defaultPort: 'on' } },
    { nodeId: 'disabled', type: 'OUTCOME_TERMINAL', config: { outcome: 'FAILED', reason: 'healing is off (yk check --no-heal)' } },
    { nodeId: 'auto?', type: 'BRANCH', config: { mode: 'EXPRESSION', cases: { "payload['auto'] != null and payload['auto'] != '' and payload['auto'] != '-'": 'auto' }, defaultPort: 'model' } },
    { nodeId: 'auto-fix', type: 'SUB_WORKFLOW', config: { ...toolCallCfg(S('auto'), 'auto-fix'), ...ROUTE } },
    { nodeId: 'auto-check', type: 'SUB_WORKFLOW', config: { ...toolCallCfg(S('command'), S('stage')), ...ROUTE } },
    pe('auto-record', 'record-auto', healEnv(), { timeout: 60 }),
    // the brief also runs the stage once on a clean origin/main (cached by sha), so it can take as long as a stage
    pe('brief', 'brief', healEnv({ YUKTA_COMMAND: S('command'), YUKTA_TRIAGE: S('triage') }), { stdin: true, json: true, timeout: 3720 }),
    {
      nodeId: 'first', type: 'MAPPER', config: {
        mode: 'PROJECTION', mapping: { brief: "payload['output']['brief']", problems: "payload['output']['problems']", previous: "''", triage: "payload['output']['triage']", known: "payload['output']['known']" },
      },
    },
    { nodeId: 'known?', type: 'BRANCH', config: { mode: 'SELECT_KEY', selector: "payload['known']", cases: { true: 'known' }, defaultPort: 'unknown' } },
    { nodeId: 'baseline-known', type: 'OUTCOME_TERMINAL', config: { outcome: 'FAILED', reason: 'the stage already fails the same way on origin/main: a known baseline failure, no fixer was started' } },
    { nodeId: 'triage?', type: 'BRANCH', config: { mode: 'SELECT_KEY', selector: "payload['triage']", cases: { off: 'off' }, defaultPort: 'on' } },
    {
      nodeId: 'triage', type: 'CLASSIFY', config: {
        model: S('triageModel'), baseUrl: S('triageUrl'), state: "payload['brief']", question: policy.triage.question ?? 'How hard is it to fix this failed check?',
        labels: Object.fromEntries(Object.entries({ ...LEVELS, ...STOPS }).map(([k, v]) => [k, v.describe])), minProbability: policy.triage.minProbability, routeByLabel: true,
        timeout: policy.triage.timeoutSeconds || 55, engine: { onError: 'ROUTE' },
      },
    },
  ]
  const e: Edge[] = []
  e.push(...edges('trigger', 'heal?'), ...edges('heal?', 'disabled', 'off'), ...edges('heal?', 'auto?', 'on'))
  e.push(...edges('auto?', 'auto-fix', 'auto'), ...edges('auto?', 'brief', 'model'))
  e.push(...edges('auto-fix', 'auto-check', 'success', 'failure', 'error'), ...edges('auto-check', 'auto-record', 'success'))
  e.push(...edges('auto-check', 'brief', 'failure', 'error'), ...edges('auto-record', 'done'))
  e.push(...edges('brief', 'first'), ...edges('first', 'known?'), ...edges('known?', 'baseline-known', 'known'), ...edges('known?', 'triage?', 'unknown'))
  e.push(...edges('triage?', 'triage', 'on'), ...edges('triage?', avg, 'off'))
  // every port of the triage also feeds a log node (a dead end): its event is what heal-report reads to judge the triage
  const TRIAGE_PORTS = [...Object.keys(LEVELS), ...Object.keys(STOPS), 'uncertain', 'error']
  for (const port of TRIAGE_PORTS) {
    const startRung = port in LEVELS ? starts[port]! : avg
    nodes.push(pe('triage-log.' + port, 'triage-log', healEnv({
      YUKTA_TRIAGE_ROUTE: port, YUKTA_TRIAGE_START: port in STOPS ? '-' : startRung.replace('fix-', '').replaceAll('-', '/'), YUKTA_TRIAGE_MODEL: S('triageModel'),
    }), { stdin: port !== 'error', envIn: port === 'error', timeout: 60 }))
    e.push(...edges('triage', 'triage-log.' + port, port), ...edges('triage-log.' + port, 'logged'))
  }
  for (const [port, c] of Object.entries(STOPS)) {
    nodes.push({ nodeId: 'stop.' + port, type: 'OUTCOME_TERMINAL', config: { outcome: 'FAILED', reason: `ESCALATE (${port}): ${c.reason}` } })
    e.push(...edges('triage', 'stop.' + port, port))
  }
  e.push(...edges('triage', starts.easy!, 'easy'), ...edges('triage', avg, 'average', 'uncertain', 'error'), ...edges('triage', starts.hard!, 'hard'))
  for (const [key, after] of nxt) {
    const st = key.split('/') as [string, string]
    e.push(...edges(rid(...st), 'done', 'success'))
    if (after) {
      const c = 'carry.' + rid(...after)
      if (!nodes.some(n => n.nodeId === c)) {
        nodes.push({ nodeId: c, type: 'MAPPER', config: carry("['output']") })
        // an attempt that changed nothing gains nothing from a stronger model asking the same question: give up. So does a second attempt on a stage in
        // heal.json singleAttempt, which an edit to the code often cannot fix (a licence header, a docs link) nor does one that failed exactly as the
        // attempt before it did (no progress, `same` of the report step)
        const stop = "payload['changed'] == false or payload['same'] == true" + (SINGLE.length ? ` or {${SINGLE.map(x => `'${x}'`).join(',')}}.contains(payload['stage'])` : '')
        nodes.push({ nodeId: c + '?', type: 'BRANCH', config: { mode: 'EXPRESSION', cases: { [stop]: 'stop' }, defaultPort: 'go' } })
        e.push(...edges(c, c + '?'), ...edges(c + '?', rid(...after), 'go'), ...edges(c + '?', 'giveup', 'stop'))
      }
      e.push(...edges(rid(...st), c, 'failure', 'error'))
    } else e.push(...edges(rid(...st), 'giveup', 'failure', 'error'))
  }
  nodes.push(...rungs.values())
  nodes.push(
    { nodeId: 'done', type: 'CONSOLE_TERMINAL', config: {} },
    { nodeId: 'logged', type: 'CONSOLE_TERMINAL', config: {} }, // the end of the log branches; they have no result to give
    { nodeId: 'giveup', type: 'OUTCOME_TERMINAL', config: { outcome: 'FAILED', reason: 'every model failed at every effort' } },
  )
  const HEAL_WF: Json = { workflowId: 'heal', description: 'Repair a failed stage: the free automatic fix first, then triage and the model ladder, escalating on failure.', nodes, edges: e }
  return { claude: CLAUDE_WF, attempt: ATTEMPT_WF, heal: HEAL_WF }
}

/** The session with every generated workflow rewritten. */
export function generate(session: Json, policy: Json): Json {
  const d = JSON.parse(JSON.stringify(session))
  d.workflows.tool = toolWorkflow(d.workflows.tool)
  delete d.workflows['fix-from']
  const h = healWorkflows(policy)
  for (const wf of [h.claude, h.attempt, h.heal]) d.workflows[wf.workflowId] = wf
  delete d.workflows['check-plain']
  d.workflows.check = checkWorkflow()
  d.workflows.pickup = pickupWorkflow()
  return d
}

export function main(argv: string[]): number {
  const policy = JSON.parse(readFileSync(process.env.HEAL_POLICY || join(HERE, 'heal.json'), 'utf8'))
  const out = JSON.stringify(generate(JSON.parse(readFileSync(PATH, 'utf8')), policy), null, 2).replace(/[\u0080-￿]/g, c => '\\u' + c.charCodeAt(0).toString(16).padStart(4, '0')) + '\n'
  if (argv.includes('--check')) {
    const cur = existsSync(PATH) ? readFileSync(PATH, 'utf8') : ''
    if (cur !== out) { console.error('session.json is stale: run `bun examples/11-dogfood/src/gen-session.ts` and commit it'); return 1 }
    console.log('session.json is current')
    return 0
  }
  writeFileSync(PATH, out)
  const d = JSON.parse(out)
  console.log(['tool', 'claude', 'attempt', 'heal', 'check', 'pickup'].map(k => `${k} ${d.workflows[k].nodes.length}`).join(', '), 'nodes')
  return 0
}

if (import.meta.main) process.exit(main(process.argv.slice(2)))
