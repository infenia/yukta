// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// ticketkit ladder ACTION RUN ...: failure routing, budgets, revision and approvals of the pipeline (design 6.3, 9, 15.5). No model.
//
//   decide STEP [--after-heal] --worktree W     what to do after a failed attempt (the highest steps/STEP/attempt-N): prints JSON {action, model, effort, reason}
//                                 action: retry | tier_up | heal | baseline | revise | human | pause. A terminal action (revise, human, pause) is also
//                                 written to escalation.json. Rules: BLOCKED with a spec category -> revise; licenses/security -> human; infra_error and
//                                 timeout -> rerun once, then human; a stage that also fails on clean origin/main -> baseline; style, spotbugs, ui_*,
//                                 cli_* -> heal (once), then revise; scope-type classes -> retry once; compile, tests, coverage -> attempt 2 same model,
//                                 attempt 3 one tier up, then revise (3 attempts, +1 after a revision); a budget over its limit -> pause
//   heal STEP --worktree W         yk check --stage <failed stage> (its heal ladder), then revert everything outside write_files and any locked test
//                                  (recorded in steps/STEP/scope-violations.jsonl)
//   next-step                      prints {"step": "S2", "more": true} for the first step not DONE whose depends_on are DONE, else {"step": null, "more": false}
//   route-failure                  prints {"action": ..., "trigger": ...} from escalation.json
//   build-revision                 spec/revision-request.json (RevisionRequest) from escalation.json
//   apply-revision                 spec/revision-result.json -> spec v+1 (completed steps are immutable); writes spec/changed_cases.json
//   final-fail                     the final gate failed: escalation.json asks for a revision
//   review-verdict                 a blocker finding overrides APPROVE; TESTS_FAITHFUL not met is a major finding; both -> CHANGES_REQUESTED
//   need-approval KIND             {"ask": bool} for KIND spec (high risk) or merge (project.json merge_gate: high_risk | always | never)
//   pause-packet                   human-packet.md: ticket, spec, last failure digests, diff stat
//   ack-pause                      a human approved the pause: budgets double for the rest of the run
import { rmSync, statSync } from 'node:fs'
import {
  type Json, costByRole, die, dumps, existsSync, fmt, git, isFile, join, kit, load, matches, parseArgs, readFileSync, readdirSync, resolve, save, sh, writeFileSync,
} from './lib.ts'
import { event } from './stepkit.ts'
import { validateSchema } from './validate-schema.ts'

const SPEC_BLOCKS = new Set(['spec_conflict', 'contract_unclear', 'missing_context', 'needs_out_of_scope_change'])
const MECH_CLASSES = new Set(['scope_violation', 'locked_file_modified', 'patch_apply_fail', 'format_fail'])
const NEVER_MODEL = new Set(['licenses', 'security'])
const TIERS: [string, string][] = [['haiku', 'medium'], ['sonnet', 'medium'], ['sonnet', 'high']]
const trim = (p: string): string => readFileSync(p, 'utf8').trim()
const readOr = (p: string, d: Json = {}): Json => (isFile(p) ? load(p) : d)
const runName = (run: string): string => run.replace(/\/+$/, '').split('/').pop()!

function mechanical(stage: string): boolean {
  const s = stage.replaceAll('.', '_')
  return s === 'style' || s === 'spotbugs' || ((s.startsWith('ui_') || s.startsWith('cli_')) && s !== 'ui_vitest' && s !== 'cli_cover_check')
}

const ctx = (run: string): [Json, Json] => [load(join(run, 'project.json')), load(join(run, 'routing.json'))]
const limits = (p: Json): Json => ({ attempts_per_step: 3, revisions_per_run: 2, spec_retries: 2, ...(p.limits ?? {}) })
const attemptNumbers = (sd: string): number[] => readdirSync(sd).map(d => /^attempt-(\d+)$/.exec(d)?.[1]).filter((x): x is string => !!x).map(Number).sort((a, b) => a - b)
const revisions = (run: string): number => (isFile(join(run, 'revisions.json')) ? load(join(run, 'revisions.json')).count : 0)
const factor = (run: string): number => (isFile(join(run, 'extension.json')) ? load(join(run, 'extension.json')).factor : 1)

function budgetBreach(run: string, p: Json, routing: Json): string | null {
  const cost = costByRole(run)[1]
  const budget = routing.budget_usd * factor(run)
  if (cost > budget) return `cost $${cost.toFixed(2)} is over the $${budget.toFixed(2)} budget`
  const ev = join(run, 'events.jsonl')
  if (isFile(ev) && statSync(ev).size) {
    const first = JSON.parse(readFileSync(ev, 'utf8').split('\n')[0]!).ts as string
    const mins = (Date.now() - Date.parse(first)) / 60000
    const cap = ((p.budgets?.wall_clock_minutes ?? { S: 60, M: 120, L: 240 })[routing.complexity] as number) * factor(run)
    if (mins > cap) return `wall clock ${mins.toFixed(0)} min is over the ${cap.toFixed(0)} min budget`
  }
  return null
}

function tierUp(m: Json): [string, string] | null {
  const i = TIERS.findIndex(([a, b]) => a === m.model && b === m.effort)
  const at = i < 0 ? TIERS.length - 1 : i
  return at + 1 < TIERS.length ? TIERS[at + 1]! : null
}

/** One decision of the local model through `ticketkit decision` (stderr, with its WARN line, passes through). The last JSON line, or null. */
function askDecision(...args: string[]): Json | null {
  const r = kit(['decision', ...args])
  if (r.stderr) process.stderr.write(r.stderr)
  const lines = r.stdout.split('\n').filter(l => l.startsWith('{'))
  return r.code === 0 && lines.length ? JSON.parse(lines[lines.length - 1]!) : null
}

function decide(run: string, sid: string, afterHeal: boolean, wt: string): number {
  const [p, routing] = ctx(run)
  const sd = join(run, 'steps', sid)
  const n = attemptNumbers(sd).pop()!
  const ad = join(sd, `attempt-${n}`)
  const state: Json = { healed: [], infra: 0, mech: 0, flaky: 0, ...readOr(join(sd, 'ladder.json')) }
  const mf = join(sd, 'model.json')
  const cur = isFile(mf) ? load(mf) : routing.models.implementer
  const res = readOr(join(ad, 'result.json'))
  const fail = readOr(join(ad, 'failure.json'))
  const cls: string = res.status === 'BLOCKED' ? 'blocked_reported' : fail.class ?? 'infra_error'
  const stage: string = fail.stage ?? ''
  const allowed = limits(p).attempts_per_step + (revisions(run) ? 1 : 0)
  const complexity = (): string => load(join(run, 'spec', 'spec.json')).steps.find((x: Json) => x.id === sid)?.complexity ?? 'standard'

  const out = (action: string, reason: string, model?: string, effort?: string, trigger = 'step_failed'): number => {
    const d: Json = { action, reason, step: sid, attempt: n, model: model ?? cur.model, effort: effort ?? cur.effort, trigger }
    if (['revise', 'human', 'pause'].includes(action)) {
      // the failed attempt's edits must not leak into the next base (a revision commits the new tests on top of the worktree)
      git(['reset', '-q', '--hard', trim(join(sd, 'base'))], { cwd: wt })
      git(['clean', '-q', '-fd', '-e', 'node_modules'], { cwd: wt })
      if (action === 'revise' && revisions(run) >= limits(p).revisions_per_run) Object.assign(d, { action: 'human', reason: `${reason}; the ${limits(p).revisions_per_run} revisions of the run are used` })
      save(join(run, 'escalation.json'), d)
    }
    save(join(sd, 'ladder.json'), state)
    event([run, 'role=decide', `step_id=${sid}`, `attempt=${n}`, `action=${d.action}`, `fail_class=${cls}`, `model=${cur.model}`, `effort=${cur.effort}`, `complexity=${complexity()}`])
    console.log(dumps(d))
    return 0
  }

  const breach = budgetBreach(run, p, routing)
  if (breach) return out('pause', breach)
  if (cls === 'blocked_reported') {
    const cat = res.blocked?.category
    return SPEC_BLOCKS.has(cat) ? out('revise', `BLOCKED (${cat})`, undefined, undefined, 'step_blocked') : out('retry', `BLOCKED (${cat}) is not a spec problem`)
  }
  if (NEVER_MODEL.has(stage)) return out('human', `${stage} is never healed`)
  if (cls === 'infra_error' || cls === 'timeout') {
    state.infra += 1
    return state.infra === 1 ? out('retry', `${cls}: rerun unchanged`) : out('human', `${cls} twice`)
  }
  if (isFile(join(ad, 'baseline.json'))) return out('baseline', `${stage} also fails on clean origin/main: baseline_failure, not charged to the implementer`)
  if (mechanical(stage)) {
    if (afterHeal || state.healed.includes(stage)) return out('revise', `heal could not fix ${stage}`)
    state.healed.push(stage)
    return out('heal', `${stage} is mechanical: heal first`)
  }
  if (afterHeal) return out('revise', 'heal left the step failing')
  if (MECH_CLASSES.has(cls) && state.mech === 0) {
    state.mech += 1
    return out('retry', `${cls}: retry once with the digest`)
  }
  if (n >= 2 && (cls === 'test_fail' || cls === 'typecheck_fail') && (stage === 'tests' || stage === 'compile')) {
    const d = askDecision('cause', run, sid) // D2: only for tests and compile failures from attempt 2
    const cause = d ? d.label : 'impl_bug'
    if (cause === 'spec_gap' || cause === 'spec_conflict') return out('revise', `cause: ${cause}`)
    if (cause === 'flaky' && state.flaky === 0) {
      state.flaky += 1
      event([run, 'role=cause', `step_id=${sid}`, 'flaky=1'])
      return out('retry', 'cause: flaky, rerun once unchanged')
    }
  }
  if (n >= allowed) return out('revise', `${n} attempts failed`)
  if (n === 2) {
    const up = tierUp(cur)
    if (up) {
      save(mf, { model: up[0], effort: up[1] })
      return out('tier_up', 'attempt 3 goes one tier up', up[0], up[1])
    }
  }
  return out('retry', `attempt ${n + 1} on the same model with the digest`)
}

function changedFiles(wt: string, base: string): Set<string> {
  const names = new Set([...git(['diff', '--name-only', base], { cwd: wt }).stdout.split(/\s+/), ...git(['ls-files', '--others', '--exclude-standard'], { cwd: wt }).stdout.split(/\s+/)])
  return new Set([...names].filter(f => f && !/(^|\/)(build|node_modules|\.gradle|\.yukta-dev)\//.test(f)))
}

function heal(run: string, sid: string, wt: string): number {
  const [p] = ctx(run)
  const sd = join(run, 'steps', sid)
  const spec = load(join(run, 'spec', 'spec.json'))
  const step = spec.steps.find((s: Json) => s.id === sid)
  const base = trim(join(sd, 'base'))
  const stage = (load(join(sd, `attempt-${attemptNumbers(sd).pop()}`, 'failure.json')).stage as string).replaceAll('.', '_')
  let cmd = process.env.TICKET_HEAL_CMD ?? '-'
  cmd = cmd === '' || cmd === '-' ? p.commands.heal_stage : cmd
  const r = sh(fmt(cmd, { stage, base }), wt)
  const locked = readOr(join(run, 'spec', 'locked.json'))
  const gen: string[] = p.generated.map((g: Json) => g.glob)
  const reverted: Json[] = []
  for (const f of [...changedFiles(wt, base)].sort()) {
    if ((step.write_files.includes(f) && !(f in locked)) || matches(f, gen)) continue
    if (git(['cat-file', '-e', `${base}:${f}`], { cwd: wt, check: false }).code === 0) git(['checkout', base, '--', f], { cwd: wt })
    else rmSync(join(wt, f), { force: true })
    reverted.push({ file: f, why: f in locked ? 'locked test' : 'outside write_files' })
  }
  if (reverted.length) {
    writeFileSync(join(sd, 'scope-violations.jsonl'), dumps({ stage, reverted }) + '\n', { flag: 'a' })
    event([run, 'role=heal', `step_id=${sid}`, 'scope_violation=1', `files=${reverted.length}`])
  }
  console.log(dumps({ healed_exit: r.code, reverted }))
  return 0
}

function nextStep(run: string): number {
  const spec = load(join(run, 'spec', 'spec.json'))
  const done = new Set<string>(spec.steps.filter((s: Json) => isFile(join(run, 'steps', s.id, 'status.json'))).map((s: Json) => s.id))
  for (const s of spec.steps) {
    if (!done.has(s.id) && (s.depends_on as string[]).every(d => done.has(d))) { console.log(dumps({ step: s.id, more: true })); return 0 }
  }
  console.log(dumps({ step: null, more: false }))
  return 0
}

function buildRevision(run: string): number {
  const esc = load(join(run, 'escalation.json'))
  const sid: string = esc.step
  const spec = load(join(run, 'spec', 'spec.json'))
  const events = isFile(join(run, 'events.jsonl')) ? readFileSync(join(run, 'events.jsonl'), 'utf8').split('\n').filter(l => l.length).map(l => JSON.parse(l)) : []
  const attempts: Json[] = []
  for (const name of readdirSync(join(run, 'steps', sid)).filter(d => d.startsWith('attempt-')).sort()) {
    const ev = events.filter(e => e.step_id === sid && e.attempt === Number(name.split('-')[1]) && e.role === 'implementer')
    const m = ev.length ? ev[ev.length - 1] : { model: 'haiku', effort: 'medium' }
    attempts.push({ model: m.model, effort: m.effort, failure_digest_ref: `steps/${sid}/${name}/failure.json` })
  }
  const done = spec.steps.filter((s: Json) => isFile(join(run, 'steps', s.id, 'status.json'))).map((s: Json) => s.id)
  const req = { run_id: runName(run), spec_version: spec.spec_version, trigger: esc.trigger, step_id: sid, attempts, completed_steps: done, question: `Why did ${sid} fail and how should the spec change?`.slice(0, 300) }
  save(join(run, 'spec', 'revision-request.json'), req)
  if (validateSchema('revision-request', join(run, 'spec', 'revision-request.json')).code) die('ERROR the revision request is not valid')
  console.log(dumps({ trigger: req.trigger, step_id: sid }))
  return 0
}

function applyRevision(run: string): number {
  const spec = load(join(run, 'spec', 'spec.json'))
  const rev = load(join(run, 'spec', 'revision-result.json'))
  const done = new Set<string>(spec.steps.filter((s: Json) => isFile(join(run, 'steps', s.id, 'status.json'))).map((s: Json) => s.id))
  const bad = rev.replace_steps.filter((s: Json) => done.has(s.id)).map((s: Json) => s.id)
  if (bad.length) die(`ERROR the revision replaces completed steps [${bad.map((b: string) => `'${b}'`).join(', ')}]; completed steps are immutable`)
  const by: Record<string, Json> = Object.fromEntries(spec.steps.map((s: Json) => [s.id, s]))
  for (const s of rev.replace_steps) by[s.id] = s
  const cases: Record<string, Json> = Object.fromEntries(spec.test_cases.map((c: Json) => [c.id, c]))
  for (const c of rev.replace_test_cases) cases[c.id] = c
  for (const cid of rev.remove_test_case_ids) delete cases[cid]
  const num = (x: Json): number => parseInt(x.id.slice(1), 10)
  const next = {
    ...spec, spec_version: spec.spec_version + 1, steps: Object.values(by).sort((a, b) => num(a) - num(b)), test_cases: Object.values(cases).sort((a, b) => num(a) - num(b)),
    assumptions: [...spec.assumptions, ...rev.new_assumptions],
  }
  save(join(run, 'spec', 'spec.json'), next)
  save(join(run, 'spec', `spec.v${next.spec_version}.json`), next)
  save(join(run, 'spec', 'changed_cases.json'), [...new Set<string>([...rev.replace_test_cases.map((c: Json) => c.id), ...rev.remove_test_case_ids])].sort())
  rmSync(join(run, 'prefix.hash'), { force: true }) // the cache prefix legitimately changes with the spec
  rmSync(join(run, 'escalation.json'), { force: true })
  save(join(run, 'revisions.json'), { count: revisions(run) + 1 })
  console.log(`REVISED spec_version=${next.spec_version} replaced=[${rev.replace_steps.map((s: Json) => s.id).sort().map((i: string) => `'${i}'`).join(', ')}]`)
  return 0
}

function reviewVerdict(run: string): number {
  const r = load(join(run, 'review', 'result.json'))
  const notes: string[] = []
  if (r.findings.some((f: Json) => f.severity === 'blocker')) notes.push('a blocker finding overrides APPROVE')
  for (const c of r.criteria_results) {
    if (c.id === 'TESTS_FAITHFUL' && !c.met) {
      r.findings.push({ severity: 'major', path: '-', issue: 'the tests do not faithfully check the cases', suggested_action: 'revise the tests' })
      notes.push('TESTS_FAITHFUL not met is a major finding')
    }
  }
  if (notes.length && r.verdict === 'APPROVE') r.verdict = 'CHANGES_REQUESTED'
  save(join(run, 'review', 'result.json'), r)
  if (r.verdict !== 'APPROVE') {
    const steps = load(join(run, 'spec', 'spec.json')).steps
    save(join(run, 'escalation.json'), { action: 'revise', trigger: 'review_findings', step: steps[steps.length - 1].id, reason: notes.join('; ') || 'the reviewer requested changes' })
  }
  console.log(dumps({ verdict: r.verdict, notes }))
  return 0
}

function finalFail(run: string): number {
  const steps = load(join(run, 'spec', 'spec.json')).steps
  save(join(run, 'escalation.json'), { action: 'revise', trigger: 'step_failed', step: steps[steps.length - 1].id, reason: 'the final gate failed' })
  console.log('ESCALATED final gate')
  return 0
}

function needApproval(run: string, kind: string, wt: string): number {
  const [p, routing] = ctx(run)
  const risk: string = routing.risk
  const policy: string = p.merge_gate ?? 'high_risk'
  let ask = kind === 'spec' ? risk === 'high' : policy === 'always' || (policy === 'high_risk' && risk === 'high')
  if (kind === 'merge') {
    const d = askDecision('merge_risk', run, '-', wt) // shadow: logged only; in mode on a high score also asks
    if (d && d.source === 'model' && parseInt(d.label, 10) >= (p.merge_risk_ask ?? 7)) ask = true
  }
  console.log(dumps({ ask, kind, risk }))
  return 0
}

function pausePacket(run: string): number {
  const t = load(join(run, 'ticket.json'))
  const esc = readOr(join(run, 'escalation.json'))
  const lines = [`# Run ${runName(run)} is paused`, `Ticket #${t.ticket_id}: ${t.title}`, `Why: ${esc.reason ?? '?'} (step ${esc.step ?? '-'})`, '', 'Last failure digests:']
  const fails: string[] = []
  const stepsDir = join(run, 'steps')
  if (existsSync(stepsDir)) {
    for (const s of readdirSync(stepsDir).sort()) {
      for (const a of readdirSync(join(stepsDir, s)).filter(d => d.startsWith('attempt-')).sort()) if (isFile(join(stepsDir, s, a, 'failure.json'))) fails.push(join(stepsDir, s, a, 'failure.json'))
    }
  }
  for (const f of fails.slice(-3)) {
    const d = load(f)
    const parts = f.split('/')
    lines.push(`- ${parts[parts.length - 3]}/${parts[parts.length - 2]}: ${d.stage} / ${d.class}: ${(d.message ?? '').slice(0, 200)}`)
  }
  const [by, total] = costByRole(run)
  lines.push('', `Cost so far: $${total.toFixed(2)} ${dumps(by)}`, 'Approve to continue with doubled budgets; reject to stop. Spec: spec/spec.json')
  writeFileSync(join(run, 'human-packet.md'), lines.join('\n') + '\n')
  console.log(lines.join('\n').slice(0, 1500))
  return 0
}

function ackPause(run: string): number {
  save(join(run, 'extension.json'), { factor: factor(run) * 2 })
  rmSync(join(run, 'escalation.json'), { force: true })
  console.log('budgets doubled')
  return 0
}

export function main(argv: string[]): number {
  const { pos, opt, flag } = parseArgs(argv, { value: ['worktree'], bool: ['after-heal'] })
  const [action = '', run = '', arg] = pos
  const wt = resolve(opt.worktree ?? '.')
  switch (action) {
    case 'decide': return decide(run, arg!, flag.has('after-heal'), wt)
    case 'heal': return heal(run, arg!, wt)
    case 'next-step': return nextStep(run)
    case 'route-failure': console.log(dumps(readOr(join(run, 'escalation.json'), { action: 'human', trigger: 'step_failed', reason: 'no escalation recorded' }))); return 0
    case 'build-revision': return buildRevision(run)
    case 'apply-revision': return applyRevision(run)
    case 'review-verdict': return reviewVerdict(run)
    case 'final-fail': return finalFail(run)
    case 'need-approval': return needApproval(run, arg!, wt)
    case 'pause-packet': return pausePacket(run)
    case 'ack-pause': return ackPause(run)
    default: return die(`unknown action ${action}`)
  }
}
