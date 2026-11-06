// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// Writes the workflows of the ticket pipeline into session.json (design 6.1 to 6.3, 9, 15.3, 15.5), the way
// dogfood/src/gen-heal.ts does. `yk ticket` adds them to the `dogfood` session, so they call its `tool` workflow.
//
//   ticket        issue -> intake -> init -> claim -> route -> architect (validate-spec, 2 retries; policy conflicts ask a person) -> test writer -> gate-tests (blocked or red-check failures retry the architect) -> [spec approval]
//                 -> plan comment -> attempt_round, recovered up to 3 times (revise: `escalate`; pause: a human packet and an ASK_INPUT) -> [merge gate]
//                 -> finish (draft PR) -> close
//   attempt_round steps_round -> finalize
//   steps_round   the steps whose depends_on are DONE, one `step` run each, in order (8 slots)
//   step          one step, up to 4 attempts: begin -> implementer -> validate-step; a failure goes to `ladder decide`: retry, tier_up (one model tier up),
//                 heal (yk check --stage <stage> heals, then everything outside write_files is reverted), baseline (the stage also fails on origin/main),
//                 or an escalation (revise, human, pause: escalation.json)
//   finalize      full checks -> reviewer -> verdict (a blocker or an unfaithful test overrides APPROVE)
//   escalate      RevisionRequest -> architect revision -> spec v+1 -> validate-spec -> test writer (changed cases) -> gate-tests
//
// Every node is a PROCESS_EXECUTOR running `ticketkit` (no Java); a Claude call is `ticketkit call ROLE`, which runs the claude command with --role ROLE. Claude calls
// share the `claude` concurrency group; Gradle runs inside `yk check`, which queues in the `tool` group.
//
//   bun src/ticket/gen-ticket.ts          write ticket-session.json        bun src/ticket/gen-ticket.ts --check   exit 1 when the committed ticket-session.json is stale (CI-style)
// Edit this file, run it, commit ticket-session.json.
import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { KIT, type Json, dumps, join } from './lib.ts'

const PATH = process.env.TICKET_SESSION || join(KIT, 'ticket-session.json')
const CMD = ['bun', '__TICKET__/src/ticket/ticketkit.ts']
const S = (k: string): string => `\${start.${k}}`
const RUN = S('run')
const W = ['--worktree', S('worktree')]
const START_KEYS = [
  'project',
  'run',
  'worktree',
  'claudeBin',
  'gateStep',
  'gateFinal',
  'stateDir',
  'healCmd',
  'baselineCmd',
  'gateWave',
  'runId',
  'events',
]

type Edge = { source: string; target: string; sourcePort?: string }
const edges = (src: string, dst: string, ...ports: string[]): Edge[] =>
  ports.length ? ports.map((p) => ({ source: src, target: dst, sourcePort: p })) : [{ source: src, target: dst }]

function pe(nodeId: string, args: string[], timeout = 300, o: { json?: boolean; claude?: boolean; routed?: boolean } = {}): Json {
  const env = {
    YK_CLAUDE_BIN: S('claudeBin'),
    TICKET_GATE_STEP: S('gateStep'),
    TICKET_GATE_FINAL: S('gateFinal'),
    YUKTA_DEV_STATE_DIR: S('stateDir'),
    TICKET_HEAL_CMD: S('healCmd'),
    TICKET_BASELINE_CMD: S('baselineCmd'),
    TICKET_GATE_WAVE: S('gateWave'),
  }
  const cfg: Json = { command: [...CMD, ...args], workingDir: S('project'), env, timeout, timeoutSeconds: timeout, failureMode: 'CONTINUE' }
  if (o.routed ?? true) cfg.routeByExitCode = true
  if (o.json) cfg.outputFormat = 'JSON'
  if (o.claude) cfg.engine = { concurrencyGroup: 'claude' }
  return { nodeId, type: 'PROCESS_EXECUTOR', config: cfg }
}

const terminal = (nodeId: string, reason: string): Json => ({ nodeId, type: 'OUTCOME_TERMINAL', config: { outcome: 'FAILED', reason } })
const ok = (nodeId = 'ok'): Json => ({ nodeId, type: 'CONSOLE_TERMINAL', config: { displayOutput: true } })
const select = (nodeId: string, key: string, cases: Json, def: string): Json => ({
  nodeId,
  type: 'BRANCH',
  config: { mode: 'SELECT_KEY', selector: `payload['output']['${key}']`, cases, defaultPort: def },
})
// every question of a run goes on the route `ticket` and says which issue and run it is about (design/plans/ticket-input-routing.md 3.5): the input bridge finds the
// Claude Code session that works the issue from context.issue
const ask = (nodeId: string, prompt: string, ttl = 86400, kind = 'APPROVAL', risk = 'HIGH'): Json => ({
  nodeId,
  type: 'ASK_INPUT',
  config: {
    prompt,
    kind,
    risk,
    ttlSeconds: ttl,
    onTimeout: 'FAIL',
    route: 'ticket',
    context: { issue: S('issue'), run_id: S('runId'), gate: nodeId },
  },
})
const rejected = (nodeId: string): Json => ({
  nodeId,
  type: 'BRANCH',
  config: { mode: 'EXPRESSION', cases: { "metadata['input.decision'] == 'reject'": 'reject' }, defaultPort: 'approve' },
})

function sub(nodeId: string, wf: string, extra: Record<string, string> = {}, concurrency?: number): Json {
  const payload: Json = {}
  for (const k of START_KEYS) if (!(k in extra)) payload[k] = S(k)
  Object.assign(payload, extra)
  const cfg: Json = { workflowId: wf, timeoutSeconds: 21600, routeByOutcome: true, payload }
  if (concurrency) cfg.concurrency = concurrency
  return { nodeId, type: 'SUB_WORKFLOW', config: cfg }
}

/** The issue goes back to Ready with a comment (design 15.9 step 7); best effort, the run ends FAILED at <node_id>.failed either way. */
const deadEnd = (nodeId: string, why: string): Json =>
  pe(nodeId + '.dead', ['github', 'dead-end', RUN, '--stage', nodeId, '--reason', why, ...W], 120, { routed: false })

/** Collects nodes and edges; fail(node, why, dead) wires a node's failure port to a terminal (through a dead-end when asked). */
class G {
  nodes: Json[] = []
  edges: Edge[] = []
  add(...nodes: Json[]): void {
    this.nodes.push(...nodes)
  }
  link(src: string, dst: string, ...ports: string[]): void {
    this.edges.push(...edges(src, dst, ...ports))
  }
  fail(nid: string, why: string, dead = false, port = 'failure'): void {
    this.nodes.push(terminal(nid + '.failed', why))
    if (dead) {
      this.nodes.push(deadEnd(nid, why))
      this.link(nid, nid + '.dead', port)
      this.link(nid + '.dead', nid + '.failed')
    } else this.link(nid, nid + '.failed', port)
  }
  /** items: [(node, failure_reason)]; each success goes to the next node, the last to `last`. */
  chain(items: [Json, string][], last: string, dead = false): void {
    items.forEach(([n, why], k) => {
      this.add(n)
      this.link(n.nodeId, k + 1 < items.length ? items[k + 1]![0].nodeId : last, 'success')
      this.fail(n.nodeId, why, dead)
    })
  }
  workflow(id: string, description: string): Json {
    return {
      workflowId: id,
      description,
      nodes: [{ nodeId: 'trigger', type: 'CONSTANT_SOURCE', config: {} }, ...this.nodes],
      edges: this.edges,
    }
  }
}

// ---------------------------------------------------------------- step: up to 4 attempts (3, +1 after a revision)
const ATTEMPTS = 4
const st = new G()
st.add(terminal('escalate', 'escalate: the step needs a revision, a human or a pause (escalation.json says which)'), ok('ok'))
st.add(pe('commit', ['flow', 'commit-step', RUN, S('step'), ...W], 120))
st.link('commit', 'ok', 'success')
st.fail('commit', 'the step could not be committed')
for (let k = 1; k <= ATTEMPTS; k++) {
  const [b, im, rs, va, de, ac] = ['begin', 'implement', 'result', 'validate', 'decide', 'action'].map((n) => `${n}${k}`) as [
    string,
    string,
    string,
    string,
    string,
    string,
  ]
  st.add(pe(b, ['flow', 'step-begin', RUN, S('step'), '--attempt', 'next', ...W], 300))
  st.add(pe(im, ['call', 'implementer', RUN, '--step', S('step'), '--attempt', 'cur', ...W], 2400, { json: true, claude: true }))
  st.add(select(rs, 'status', { DONE: 'done' }, 'blocked'))
  st.add(pe(va, ['flow', 'validate-step', RUN, S('step'), '--attempt', 'cur', ...W], 3700))
  st.add(pe(de, ['ladder', 'decide', RUN, S('step'), ...W], 120, { json: true }))
  st.add(select(ac, 'action', { retry: 'go', tier_up: 'go', heal: 'heal', baseline: 'baseline' }, 'escalate'))
  st.link(b, im, 'success')
  st.fail(b, 'the step base or packet failed')
  st.link(im, rs, 'success')
  st.fail(im, 'the implementer call failed (the ERROR line says which: schema_invalid, api_error, limit)')
  st.link(rs, va, 'done')
  st.link(rs, de, 'blocked')
  st.link(va, 'commit', 'success')
  st.link(va, de, 'failure')
  st.link(de, ac, 'success')
  st.fail(de, 'the ladder could not decide')
  st.link(ac, k < ATTEMPTS ? `begin${k + 1}` : 'escalate', 'go')
  st.link(ac, 'commit', 'baseline')
  st.link(ac, 'escalate', 'escalate')
  // heal: yk check heals the stage, anything outside write_files is reverted, the step is validated again; still failing -> escalation
  const [h, vh, dh] = [`heal${k}`, `validate_heal${k}`, `decide_heal${k}`] as [string, string, string]
  st.add(pe(h, ['ladder', 'heal', RUN, S('step'), ...W], 3700, { json: true }))
  st.add(pe(vh, ['flow', 'validate-step', RUN, S('step'), '--attempt', 'cur', ...W], 3700))
  st.add(pe(dh, ['ladder', 'decide', RUN, S('step'), '--after-heal', ...W], 120, { json: true }))
  st.link(ac, h, 'heal')
  st.link(h, vh, 'success')
  st.fail(h, 'heal could not run')
  st.link(vh, 'commit', 'success')
  st.link(vh, dh, 'failure')
  st.link(dh, 'escalate', 'success')
  st.fail(dh, 'the ladder could not decide')
}
const STEP_WF = st.workflow(
  'step',
  'One step of a ticket: up to four attempts with the retry ladder, heal for mechanical failures, escalation.',
)
STEP_WF.nodes.push(pe('warm', ['waves', 'warm-wait', RUN, S('step')], 400, { routed: false })) // the second step of a wave waits for the cache to be warm
STEP_WF.edges = [...STEP_WF.edges, ...edges('trigger', 'warm'), ...edges('warm', 'begin1')]

// ---------------------------------------------------------------- wave: two steps in parallel, each in its own worktree from the same base (design 11.2)
const wv = new G()
wv.add(
  ok('ok'),
  pe('prepare', ['waves', 'prepare', RUN, ...W], 120, { json: true }),
  { nodeId: 'split', type: 'SPLITTER', config: { itemsPath: 'payload.output.items' } },
  sub('runw', 'step', { step: '${payload.step}', worktree: '${payload.worktree}' }, 2),
  { nodeId: 'join', type: 'AGGREGATE', config: { size: 2, timeoutMs: 21600000 } },
  pe('merge', ['waves', 'merge', RUN, ...W], 300),
  pe('wave_check', ['waves', 'check', RUN, ...W], 3700),
)
wv.link('prepare', 'split', 'success')
wv.fail('prepare', 'the wave could not be prepared')
wv.link('split', 'runw')
wv.link('runw', 'join', 'success', 'failure')
wv.link('join', 'merge')
wv.link('merge', 'wave_check', 'success')
wv.fail('merge', 'the wave could not be merged (a conflict is an escalation)')
wv.link('wave_check', 'ok', 'success')
wv.fail('wave_check', 'the merged wave fails its post-wave check')
const WAVE_WF = wv.workflow('wave', 'Two independent steps in parallel worktrees, merged in step order, then a post-wave check.')
WAVE_WF.edges.push(...edges('trigger', 'prepare'))

// ---------------------------------------------------------------- steps_round: wave after wave (8 slots, the sizing lint caps a spec at 8 steps)
const SLOTS = 8
const sr = new G()
sr.add(ok('ok'))
for (let k = 1; k <= SLOTS; k++) {
  const [nx, mode, run, pair] = [`next${k}`, `mode${k}`, `run${k}`, `pair${k}`] as [string, string, string, string]
  sr.add(
    pe(nx, ['waves', 'next-wave', RUN], 60, { json: true }),
    select(mode, 'mode', { single: 'single', pair: 'pair' }, 'none'),
    sub(run, 'step', { step: '${payload.output.step}' }),
    sub(pair, 'wave'),
  )
  const follow = k < SLOTS ? `next${k + 1}` : 'ok'
  sr.link(nx, mode, 'success')
  sr.fail(nx, 'next-wave failed')
  sr.link(mode, run, 'single')
  sr.link(mode, pair, 'pair')
  sr.link(mode, 'ok', 'none')
  sr.link(run, follow, 'success')
  sr.fail(run, 'a step ended without DONE (escalation.json says what to do)')
  sr.link(pair, follow, 'success')
  sr.fail(pair, 'a wave failed (escalation.json says what to do)')
}
const STEPS_WF = sr.workflow('steps_round', 'Run the ready steps, one or two at a time, in order.')
STEPS_WF.edges.push(...edges('trigger', 'next1'))

// ---------------------------------------------------------------- finalize: full checks, reviewer, verdict
const fz = new G()
fz.add(
  ok('ok'),
  terminal('changes.failed', 'the reviewer requested changes (escalation.json)'),
  terminal('full_checks.failed', 'the final gate failed'),
)
fz.add(pe('full_checks', ['flow', 'full-checks', RUN, ...W], 3700), pe('final_fail', ['ladder', 'final-fail', RUN], 60, { routed: false }))
fz.link('full_checks', 'mutation', 'success')
fz.add(pe('mutation', ['flow', 'mutation', RUN, ...W], 3700, { routed: false }))
fz.link('mutation', 'review')
fz.link('full_checks', 'final_fail', 'failure')
fz.link('final_fail', 'full_checks.failed')
fz.add(pe('review', ['call', 'reviewer', RUN, '--step', 'S1', ...W], 2400, { json: true, claude: true }))
fz.fail('review', 'the reviewer failed')
fz.link('review', 'verdict', 'success')
fz.add(
  pe('verdict', ['ladder', 'review-verdict', RUN], 60, { json: true }),
  select('verdict?', 'verdict', { APPROVE: 'approved' }, 'changes'),
)
fz.link('verdict', 'verdict?', 'success')
fz.fail('verdict', 'the verdict could not be computed')
fz.link('verdict?', 'ok', 'approved')
fz.link('verdict?', 'changes.failed', 'changes')
const FINAL_WF = fz.workflow('finalize', 'The final gate, the fresh-context reviewer and a verdict code can override.')
FINAL_WF.edges.push(...edges('trigger', 'full_checks'))

// ---------------------------------------------------------------- attempt_round: the steps, then the finish line
const ar = new G()
ar.add(sub('steps', 'steps_round'), sub('finalize', 'finalize'), ok('ok'))
ar.link('steps', 'finalize', 'success')
ar.fail('steps', 'a step did not finish')
ar.link('finalize', 'ok', 'success')
ar.fail('finalize', 'the final gate or the review did not pass')
const ROUND_WF = ar.workflow('attempt_round', 'All steps, the final gate and the review.')
ROUND_WF.edges.push(...edges('trigger', 'steps'))

// ---------------------------------------------------------------- escalate: the architect revises the spec (design 6.3)
const es = new G()
es.add(ok('ok'))
es.chain(
  [
    [pe('build_revision', ['ladder', 'build-revision', RUN], 60), 'the revision request could not be built'],
    [
      pe('architect_revise', ['call', 'architect_revise', RUN, ...W], 2400, { json: true, claude: true }),
      "the architect's revision call failed (the ERROR line says which: schema_invalid, api_error, limit)",
    ],
    [pe('apply_revision', ['ladder', 'apply-revision', RUN], 60), 'the revision touches completed steps or is not applicable'],
    [pe('validate_spec', ['validate-spec', RUN, ...W], 300), 'the revised spec is not valid'],
    [
      pe('test_writer', ['call', 'test_writer', RUN, ...W], 2400, { json: true, claude: true }),
      'the test writer was blocked or failed on the changed cases (the ERROR line says which: blocked, schema_invalid, api_error, limit)',
    ],
    [pe('gate_tests', ['gate-tests', RUN, ...W], 3700), 'the changed tests did not pass the red check'],
    [pe('commit_tests', ['flow', 'commit-tests', RUN, ...W], 120), 'the tests could not be committed'],
  ],
  'ok',
)
const ESC_WF = es.workflow('escalate', 'Architect revision: spec v+1, changed cases through the test writer and the gate.')
ESC_WF.edges.push(...edges('trigger', 'build_revision'))

// ---------------------------------------------------------------- ticket
const t = new G()
t.add(ok('done'))
t.chain(
  [
    [
      pe('intake', ['github', 'intake', S('issue')], 120),
      'the issue is not eligible (not open, assigned to you and Ready, or no acceptance criteria) or asks for something forbidden',
    ],
  ],
  'init',
)
t.chain(
  [
    [
      pe('init', ['init', S('issue'), '--run-id', S('runId'), '--root', S('project')], 300),
      'init failed or the issue is skipped (the branch or worktree exists)',
    ],
    [pe('claim', ['github', 'claim', S('issue')], 120), 'the issue could not be moved to In progress'],
  ],
  'route',
)
t.chain([[pe('route', ['route', RUN], 120), 'route failed']], 'architect1', true) // complexity and risk come from the ticket's own criteria, labels and paths
// the architect, with validate-spec errors and the test stage's feedback fed back, twice at most
const ARCH_FAIL =
  'the architect call failed (the ERROR line says which: schema_invalid, api_error, limit) or did not ask; a spec the validator rejects is retried, not this'
for (const k of [1, 2, 3]) {
  t.add(
    pe(`architect${k}`, ['call', 'architect', RUN, ...W], 2400, { json: true, claude: true }),
    pe(`validate_spec${k}`, ['validate-spec', RUN, ...W], 300),
  )
  t.link(`architect${k}`, `validate_spec${k}`, 'success')
  if (k < 3) {
    // a NEEDS_CLARIFICATION answer (exit 2) asks one multi-question request; the answers go into the next architect attempt
    const [c, cs, ca, cr, cp] = [`clarify${k}`, `clarify_ask${k}?`, `ask_clarify${k}`, `clarify_decision${k}?`, `apply_clarify${k}`]
    t.add(
      pe(c, ['ladder', 'clarification', RUN], 60, { json: true }),
      select(cs, 'ask', { true: 'ask' }, 'fail'),
      ask(ca, '${payload.output.prompt}', 86400, 'TEXT', 'MEDIUM'),
      rejected(cr),
      terminal(`clarify_rejected${k}`, 'a person declined to answer the architect'),
      pe(cp, ['ladder', 'apply-clarification', RUN, '${metadata.input.text}'], 60),
      deadEnd(`architect${k}`, ARCH_FAIL),
      terminal(`architect${k}.failed`, ARCH_FAIL),
    )
    t.link(`architect${k}`, c, 'failure')
    t.link(c, cs, 'success')
    t.fail(c, 'the clarification could not be read', true)
    t.link(cs, ca, 'ask')
    t.link(cs, `architect${k}.dead`, 'fail')
    t.link(`architect${k}.dead`, `architect${k}.failed`)
    t.link(ca, cr)
    t.link(cr, `clarify_rejected${k}`, 'reject')
    t.link(cr, cp, 'approve')
    t.link(cp, `architect${k + 1}`, 'success')
    t.fail(cp, 'the answers could not be recorded', true)
  } else t.fail(`architect${k}`, ARCH_FAIL, true)
  // the test writer and the red check of this spec; blocked tests or a failed gate send the reason back to the next architect attempt
  const [tw, gt] = [`test_writer${k}`, `gate_tests${k}`]
  t.add(pe(tw, ['call', 'test_writer', RUN, ...W], 2400, { json: true, claude: true }), pe(gt, ['gate-tests', RUN, ...W], 3700))
  t.link(`validate_spec${k}`, tw, 'success')
  t.link(tw, gt, 'success')
  t.link(gt, 'commit_tests', 'success')
  if (k < 3) {
    // a policy conflict (validate-spec wrote needs-clarification.json) is a question to a person; any other error is the next attempt's
    const [sc, scs] = [`spec_conflict${k}`, `spec_conflict_ask${k}?`]
    t.add(pe(sc, ['ladder', 'clarification', RUN], 60, { json: true }), select(scs, 'ask', { true: 'ask' }, 'retry'))
    t.link(`validate_spec${k}`, sc, 'failure')
    t.link(sc, scs, 'success')
    t.fail(sc, 'the clarification could not be read', true)
    t.link(scs, `ask_clarify${k}`, 'ask')
    t.link(scs, `architect${k + 1}`, 'retry')
    const rs = `retry_spec${k}`
    t.add(pe(rs, ['flow', 'retry-spec', RUN, ...W], 120))
    t.link(tw, rs, 'failure')
    t.link(gt, rs, 'failure')
    t.link(rs, `architect${k + 1}`, 'success')
    t.fail(
      rs,
      'the test gate could not run for an environment reason (spec/gate.json: missing packages, no such command), or the worktree could not be reset',
      true,
    )
  } else {
    t.fail('validate_spec3', 'the spec is still invalid after two retries (spec/validation.json)', true)
    t.fail(
      tw,
      'the test writer was blocked or failed on the third spec (the ERROR line says which: blocked, schema_invalid, api_error, limit)',
      true,
    )
    t.fail(gt, 'the tests of the third spec did not pass the red check (spec/gate.json)', true)
  }
}
t.chain([[pe('commit_tests', ['flow', 'commit-tests', RUN, ...W], 120), 'the tests could not be committed']], 'need_spec', true)
// approvals: a high-risk spec waits for an answer. MEDIUM, so the orchestrator session (an agent key) may decide it for the person; the budget pause stays
// HIGH (money: a person only)
t.add(
  pe('need_spec', ['ladder', 'need-approval', RUN, 'spec'], 60, { json: true }),
  select('spec_ask?', 'ask', { true: 'ask' }, 'skip'),
  ask('approve_spec', 'Approve the spec of run ${start.runId} (high risk)? spec/spec.json in ${start.run}', 86400, 'APPROVAL', 'MEDIUM'),
  rejected('spec_decision?'),
  terminal('spec_rejected', 'the spec was rejected'),
)
t.link('need_spec', 'spec_ask?', 'success')
t.fail('need_spec', 'approval policy failed', true)
t.link('spec_ask?', 'approve_spec', 'ask')
t.link('spec_ask?', 'plan_comment', 'skip')
t.link('approve_spec', 'spec_decision?')
t.link('spec_decision?', 'spec_rejected', 'reject')
t.link('spec_decision?', 'plan_comment', 'approve')
t.chain([[pe('plan_comment', ['github', 'plan-comment', RUN], 120), 'the plan could not be posted on the issue']], 'round1', true)
// rounds: a failed round is recovered by a revision or a pause, 3 times
const ROUNDS = 4
for (let k = 1; k <= ROUNDS; k++) {
  const [r, rf] = [`round${k}`, `route_failure${k}`]
  t.add(sub(r, 'attempt_round'))
  t.link(r, 'need_merge', 'success')
  if (k === ROUNDS) {
    t.fail(r, 'the run did not finish after three recoveries', true)
    continue
  }
  t.link(r, rf, 'failure')
  const [a, e, pp, aa, ap, ack] = [
    `action${k}`,
    `escalate${k}`,
    `pause_packet${k}`,
    `ask_pause${k}`,
    `pause_decision${k}`,
    `ack_pause${k}`,
  ] as [string, string, string, string, string, string]
  t.add(
    pe(rf, ['ladder', 'route-failure', RUN], 60, { json: true }),
    select(a, 'action', { revise: 'revise', pause: 'pause' }, 'human'),
    sub(e, 'escalate'),
    pe(pp, ['ladder', 'pause-packet', RUN], 60),
    ask(aa, 'Run ${start.runId} is paused over a budget; human-packet.md is in ${start.run}. Approve to continue with doubled budgets.'),
    rejected(ap),
    pe(ack, ['ladder', 'ack-pause', RUN, '${metadata.input.text}'], 60),
    terminal(`paused_rejected${k}`, 'a person stopped the paused run'),
  )
  t.link(rf, a, 'success')
  t.fail(rf, 'the failure could not be routed', true)
  t.link(a, e, 'revise')
  t.link(a, pp, 'pause')
  t.link(a, `human${k}`, 'human')
  t.add(
    pe(`human${k}`, ['github', 'dead-end', RUN, '--stage', `round${k}`, '--reason', 'a person is needed (escalation.json)'], 120, {
      routed: false,
    }),
    terminal(`human${k}.failed`, 'a person is needed (licenses, security, a second infra error or the revision budget)'),
  )
  t.link(`human${k}`, `human${k}.failed`)
  t.link(e, `round${k + 1}`, 'success')
  t.fail(e, "the architect's revision did not produce a valid spec", true)
  t.link(pp, aa, 'success')
  t.fail(pp, 'the human packet could not be written')
  t.link(aa, ap)
  t.link(ap, `paused_rejected${k}`, 'reject')
  t.link(ap, ack, 'approve')
  t.link(ack, `round${k + 1}`, 'success')
  t.fail(ack, 'the pause could not be acknowledged')
}
// merge gate by policy, then the draft PR
t.add(
  pe('need_merge', ['ladder', 'need-approval', RUN, 'merge', ...W], 60, { json: true }),
  select('merge_ask?', 'ask', { true: 'ask' }, 'skip'),
  ask('approve_merge', 'Open the PR for run ${start.runId}? The change is on its branch, reviewed and green.', 86400, 'APPROVAL', 'MEDIUM'),
  rejected('merge_decision?'),
  terminal('merge_rejected', 'the PR was declined'),
)
t.link('need_merge', 'merge_ask?', 'success')
t.fail('need_merge', 'approval policy failed')
t.link('merge_ask?', 'approve_merge', 'ask')
t.link('merge_ask?', 'finish', 'skip')
t.link('approve_merge', 'merge_decision?')
t.link('merge_decision?', 'merge_rejected', 'reject')
t.link('merge_decision?', 'finish', 'approve')
t.chain(
  [
    [pe('finish', ['github', 'finish', RUN, ...W], 600), 'the draft PR could not be opened'],
    [pe('close', ['flow', 'close', RUN, '--events', S('events')], 60), 'close failed'],
  ],
  'done',
)
t.edges.push(...edges('trigger', 'intake'))
const TICKET_WF = t.workflow(
  'ticket',
  'Implement one Ready GitHub issue end to end: architect spec, locked failing tests, step-by-step implementation (retry ladder, heal, escalation), fresh reviewer. Ends with a draft PR (Fixes #<issue>), never merges. Start with `yk ticket run <issue>`.',
)

export const WORKFLOWS: Record<string, Json> = {
  ticket: TICKET_WF,
  attempt_round: ROUND_WF,
  steps_round: STEPS_WF,
  wave: WAVE_WF,
  step: STEP_WF,
  finalize: FINAL_WF,
  escalate: ESC_WF,
}

function check(wfs: Record<string, Json>): void {
  for (const [wid, wf] of Object.entries(wfs)) {
    const ids: string[] = wf.nodes.map((n: Json) => n.nodeId)
    const dup = ids.filter((i, k) => ids.indexOf(i) !== k)
    if (dup.length) throw new Error(`${wid}: duplicate nodes ${dup}`)
    for (const e of wf.edges)
      if (!ids.includes(e.source) || !ids.includes(e.target)) throw new Error(`${wid}: bad edge ${JSON.stringify(e)}`)
    for (const n of wf.nodes)
      if (n.type === 'SUB_WORKFLOW' && !(n.config.workflowId in wfs)) throw new Error(`${wid}: ${n.nodeId} calls an unknown workflow`)
  }
}

export function main(argv: string[]): number {
  check(WORKFLOWS)
  const out = dumps({ workflows: WORKFLOWS }, { indent: 2 }) + '\n'
  if (argv.includes('--check')) {
    const cur = existsSync(PATH) ? readFileSync(PATH, 'utf8') : ''
    if (cur !== out) {
      console.error('session.json is stale: run `bun examples/dogfood/src/ticket/gen-ticket.ts` and commit it')
      return 1
    }
    console.log('ticket-session.json is current')
  } else {
    writeFileSync(PATH, out)
    console.log(
      Object.entries(WORKFLOWS)
        .map(([k, v]) => `${k} ${v.nodes.length}`)
        .join(', '),
      'nodes',
    )
  }
  return 0
}

if (import.meta.main) process.exit(main(process.argv.slice(2)))
