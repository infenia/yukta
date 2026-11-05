// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// ticketkit flow ACTION RUN [STEP] [--worktree W]: the glue of the `ticket` and `step` workflows (design 6, 8.2, 15.5), no model.
//
//   preflight   RUN                    the walking skeleton runs one step of a low or medium risk spec; anything else stops here (approval, waves: later issues)
//   retry-spec  RUN                    the test writer was blocked or the gate failed: the reason goes to spec/downstream.json for the next architect, the worktree is reset
//   commit-tests RUN                   commit the stubs and locked tests; the base of every step not done is reset to it
//   step-begin  RUN STEP               commit the scaffold and locked tests as the base, record it, write the step packet (pack-step)
//   validate-step RUN STEP --attempt N the ladder of 8.2: diff.patch from git, scope-check, regen, free-fix, then the step gate (yk check --no-heal --base B);
//                                      a failure writes steps/S/attempt-N/failure.json (the digest) and exits 1
//   commit-step RUN STEP               commit the validated step on the run branch and mark it DONE
//   full-checks RUN                    the final gate (yk check --full)
//   close       RUN [--events FILE]    print the cost by role and append the run's events to the global ticket-events.jsonl
//
// TICKET_GATE_STEP / TICKET_GATE_FINAL (a shell command, {base} replaced) override the project's commands; the offline tests use them.
import { appendFileSync, mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname } from 'node:path'
import {
  type Json,
  costByRole,
  die,
  existsSync,
  findFiles,
  fmt,
  git,
  isFile,
  join,
  kit,
  load,
  mkdirp,
  parseArgs,
  readFileSync,
  readdirSync,
  resolveAttempt,
  resolve,
  save,
  sh,
  writeFileSync,
} from './lib.ts'
import { event } from './stepkit.ts'

const TYPES: Record<string, string> = { feature: 'feat', bug: 'fix', refactor: 'refactor', docs: 'docs', chore: 'chore' }
const trim = (p: string): string => readFileSync(p, 'utf8').trim()

function commit(wt: string, msg: string): void {
  git(['add', '-A'], { cwd: wt })
  if (git(['diff', '--cached', '--quiet'], { cwd: wt, check: false }).code) git(['commit', '-q', '-m', msg], { cwd: wt })
}

function failDigest(run: string, step: string, attempt: number, stage: string, text: string, cmd: string, cls?: string, rc = 1): void {
  const inp = join(run, 'steps', step, `attempt-${attempt}`, 'gate.out')
  mkdirp(dirname(inp))
  writeFileSync(inp, text)
  const r = kit([
    'digest',
    run,
    step,
    '--attempt',
    String(attempt),
    '--stage',
    stage,
    '--input',
    inp,
    '--exit-code',
    String(rc),
    '--command',
    cmd.slice(0, 200),
    ...(cls ? ['--class', cls] : []),
  ])
  console.log(`ERROR ${step} ${stage}: ${(r.stdout || r.stderr).trim().slice(0, 400)}`)
}

/** One telemetry line per attempt outcome: what the tuning report groups by (model, effort, complexity). */
function attemptEvent(run: string, sid: string, attempt: number, status: string, cls = '-'): void {
  const routing = load(join(run, 'routing.json'))
  const spec = load(join(run, 'spec', 'spec.json'))
  const mf = join(run, 'steps', sid, 'model.json')
  const m = isFile(mf) ? load(mf) : routing.models.implementer
  const cx = spec.steps.find((x: Json) => x.id === sid)?.complexity ?? 'standard'
  event([
    run,
    'role=validate',
    `step_id=${sid}`,
    `attempt=${attempt}`,
    `status=${status}`,
    `fail_class=${cls}`,
    `model=${m.model}`,
    `effort=${m.effort}`,
    `complexity=${cx}`,
  ])
}

/** pitest on the changed classes (design 15.6), only at medium or high risk, never for :boot, :plus:* or :test-support. Survivors above the
 * threshold go to the reviewer as a finding (review/mutation.json). Never fails the run: a missing tool is a note. */
function mutation(run: string, wt: string): void {
  const p = load(join(run, 'project.json'))
  const routing = load(join(run, 'routing.json'))
  const out = join(run, 'review', 'mutation.json')
  mkdirp(join(run, 'review'))
  if (routing.risk === 'low') {
    save(out, { skipped: 'low risk' })
    console.log('SKIPPED mutation: low risk')
    return
  }
  const bases = existsSync(join(run, 'steps'))
    ? readdirSync(join(run, 'steps'))
        .sort()
        .map((s) => join(run, 'steps', s, 'base'))
        .filter(isFile)
    : []
  const first = bases.length ? trim(bases[0]!) : 'origin/main'
  const files = git(['diff', '--name-only', first, 'HEAD'], { cwd: wt })
    .stdout.split(/\s+/)
    .filter((f) => f.includes('/src/main/java/') && f.endsWith('.java'))
  const mods: Record<string, string[]> = {}
  for (const f of files) {
    const i = f.indexOf('/src/main/java/')
    const mod = f.slice(0, i)
    const rest = f.slice(i + '/src/main/java/'.length)
    if (['boot', 'plus', 'test-support'].includes(mod.split('/')[0]!) || mod.startsWith('test-support')) continue
    ;(mods[mod.replaceAll('/', ':')] ??= []).push(rest.slice(0, -5).replaceAll('/', '.'))
  }
  if (!Object.keys(mods).length) {
    save(out, { skipped: 'no mutable Java classes changed' })
    console.log('SKIPPED mutation: nothing to mutate')
    return
  }
  let survived = 0
  let total = 0
  for (const [mod, classes] of Object.entries(mods).sort()) {
    let cmd = process.env.TICKET_MUTATION_CMD ?? '-'
    cmd = cmd === '' || cmd === '-' ? p.commands.mutation : cmd
    sh(fmt(cmd, { module: mod, classes: classes.join(',') }), wt)
    for (const rep of findFiles(join(wt, mod.replaceAll(':', '/'), 'build', 'reports', 'pitest'), 'mutations.xml')) {
      for (const m of readFileSync(rep, 'utf8').matchAll(/<mutation\b[^>]*>/g)) {
        total++
        if (/status=["']SURVIVED["']/.test(m[0])) survived++
      }
    }
  }
  const thr = p.mutation?.max_survivor_rate ?? 0.25
  const rate = total ? survived / total : 0
  save(out, {
    modules: Object.keys(mods).sort(),
    classes: Object.values(mods).reduce((n, c) => n + c.length, 0),
    survivors: survived,
    total,
    rate: Math.round(rate * 100) / 100,
    threshold: thr,
    exceeds: total > 0 && rate > thr,
  })
  console.log(`MUTATION survivors=${survived}/${total} threshold=${thr}`)
}

/** A clean checkout has no changes against origin/main, so `--stage S --base origin/main` runs no stage and exits non-zero ("no stage of the plan is left"):
 * every failure was excused as a baseline failure. --full runs the stage on the whole tree (about 3 minutes for style), and only after a failure. */
export const BASELINE_CMD = 'yk check --no-heal --full --stage {stage}'

/** Does STAGE also fail on a clean origin/main? (TICKET_BASELINE_CMD, else yk check --stage, in a detached worktree) */
function baselineFails(wt: string, stage: string): boolean {
  let cmd = process.env.TICKET_BASELINE_CMD ?? '-'
  if (cmd === '-') cmd = BASELINE_CMD
  const tmp = mkdtempSync(join(tmpdir(), 'ticket-baseline-'))
  try {
    git(['worktree', 'add', '-q', '--detach', join(tmp, 'w'), 'origin/main'], { cwd: wt })
    return sh(fmt(cmd, { stage: stage.replaceAll('.', '_') }), join(tmp, 'w')).code !== 0
  } finally {
    git(['worktree', 'remove', '--force', join(tmp, 'w')], { cwd: wt, check: false })
    rmSync(tmp, { recursive: true, force: true })
  }
}

function gate(
  run: string,
  wt: string,
  step: string | undefined,
  attempt: number,
  template: string,
  override: string,
  stageDefault: string,
): number {
  const base = step ? trim(join(run, 'steps', step, 'base')) : 'origin/main'
  let cmd = process.env[override] ?? '-'
  cmd = fmt(cmd === '' || cmd === '-' ? template : cmd, { base })
  const r = sh(cmd, wt)
  const text = r.stdout + r.stderr
  if (!step) {
    const res = text.split('\n').filter((l) => l.startsWith('RESULT '))
    writeFileSync(join(run, 'final-result.txt'), (res.length ? res[res.length - 1] : '') + '\n')
  }
  if (r.code) {
    const m = /failed_node=(\S+)/.exec(text)
    const stage = m && m[1] !== '-' ? m[1]! : stageDefault
    failDigest(run, step ?? 'S0', attempt, stage, text, cmd, undefined, r.code)
    if (step && baselineFails(wt, stage)) save(join(run, 'steps', step, `attempt-${attempt}`, 'baseline.json'), { stage }) // the same stage fails on a clean origin/main: not the implementer's fault
  }
  return r.code
}

export function main(argv: string[]): number {
  const { pos, opt } = parseArgs(argv, { value: ['worktree', 'attempt', 'events'] })
  const [action = '', run = '', step] = pos
  const wt = resolve(opt.worktree ?? '.')
  const t = load(join(run, 'ticket.json'))
  const p = load(join(run, 'project.json'))
  const sd = join(run, 'steps', step ?? 'S0')
  const attemptOpt = opt.attempt ?? 'cur'
  switch (action) {
    case 'preflight': {
      const r = load(join(run, 'routing.json'))
      const spec = load(join(run, 'spec', 'spec.json'))
      if (r.risk === 'high') die('ERROR high risk: the spec needs approval (yk approve), which the walking skeleton does not have yet')
      if (spec.steps.length !== 1) die(`ERROR the walking skeleton runs one step, the spec has ${spec.steps.length}`)
      if (spec.steps[0].id !== 'S1') die(`ERROR the walking skeleton names its one step S1, not ${spec.steps[0].id}`)
      console.log('S1')
      return 0
    }
    case 'retry-spec': {
      // the test writer was blocked or its tests failed the gate: the architect gets the reason (spec/downstream.json) and the
      // worktree goes back to the run's base, so the next tests start clean (nothing is committed before commit-tests)
      const tw = join(run, 'spec', 'test-writer-result.json')
      const gf = join(run, 'spec', 'gate.json')
      const blocked = isFile(tw) ? load(tw).blocked : null
      const gateErrors: string[] = isFile(gf) ? (load(gf).errors ?? []) : []
      if (blocked || gateErrors.length)
        // nothing to tell when the test writer itself failed (API, limit): the next attempt just runs again
        save(join(run, 'spec', 'downstream.json'), blocked ? { test_writer_blocked: blocked } : { gate_errors: gateErrors.slice(0, 20) })
      rmSync(gf, { force: true })
      git(['reset', '-q', '--hard'], { cwd: wt })
      git(['clean', '-q', '-fd', '-e', 'node_modules'], { cwd: wt })
      console.log(
        blocked ? `RETRY spec: the test writer was blocked (${blocked.category})` : `RETRY spec: ${gateErrors.length} gate errors`,
      )
      return 0
    }
    case 'commit-tests': {
      // the stubs and locked tests become the base of every step not yet done (also after a revision)
      commit(wt, `${TYPES[t.kind]}: scaffold and locked tests for #${t.ticket_id} (ticket pipeline)`)
      for (const s of load(join(run, 'spec', 'spec.json')).steps)
        if (!isFile(join(run, 'steps', s.id, 'status.json'))) rmSync(join(run, 'steps', s.id, 'base'), { force: true })
      console.log('COMMITTED tests')
      return 0
    }
    case 'step-begin': {
      mkdirp(sd)
      if (!isFile(join(sd, 'base'))) {
        // a retry builds on the same base; the failed attempt's edits are reset first
        commit(wt, `${TYPES[t.kind]}: scaffold and locked tests for #${t.ticket_id} (ticket pipeline)`)
        writeFileSync(join(sd, 'base'), git(['rev-parse', 'HEAD'], { cwd: wt }).stdout.trim() + '\n')
      } else {
        git(['reset', '-q', '--hard', trim(join(sd, 'base'))], { cwd: wt })
        git(['clean', '-q', '-fd', '-e', 'node_modules'], { cwd: wt })
      }
      const att = resolveAttempt(sd, attemptOpt === 'cur' ? 'next' : attemptOpt)
      const r = kit(['pack-step', run, step!, '--worktree', wt, '--attempt', String(att)])
      console.log(r.stdout.trim() || r.stderr.trim())
      return r.code
    }
    case 'validate-step': {
      const base = trim(join(sd, 'base'))
      const attempt = resolveAttempt(sd, attemptOpt)
      const ad = join(sd, `attempt-${attempt}`)
      git(['add', '-N', '.'], { cwd: wt })
      writeFileSync(join(ad, 'diff.patch'), git(['diff', '--binary', base], { cwd: wt }).stdout) // the authoritative diff: git, not the agent's claim
      for (const sub of ['regen', 'scope-check']) {
        const r = kit([sub, run, step!, '--base', base, '--worktree', wt])
        if (r.code) {
          const sc = isFile(join(sd, 'scope.json')) ? load(join(sd, 'scope.json')) : {}
          const cls = (sc.errors?.[0] ?? {}).class ?? 'scope_violation'
          failDigest(run, step!, attempt, 'scope', r.stdout + r.stderr, sub, cls, r.code)
          attemptEvent(run, step!, attempt, 'fail', cls)
          return 1
        }
      }
      kit(['free-fix', run, '--base', base, '--worktree', wt])
      const rc = gate(run, wt, step, attempt, p.commands.gate_step, 'TICKET_GATE_STEP', 'tests')
      save(join(ad, 'validation.json'), { ok: rc === 0, base })
      attemptEvent(run, step!, attempt, rc === 0 ? 'pass' : 'fail', rc === 0 ? '-' : (load(join(ad, 'failure.json')).class ?? '-'))
      return rc ? 1 : 0
    }
    case 'commit-step': {
      const spec = load(join(run, 'spec', 'spec.json'))
      const title: string = spec.steps.find((s: Json) => s.id === step).title
      commit(wt, `${TYPES[t.kind]}: ${title.slice(0, 60).toLowerCase()} (#${t.ticket_id} ${step})`)
      const n = resolveAttempt(sd, 'cur')
      save(join(sd, 'status.json'), { status: 'DONE', baseline_failure: isFile(join(sd, `attempt-${n}`, 'baseline.json')) })
      console.log(`DONE ${step}`)
      return 0
    }
    case 'full-checks':
      return gate(run, wt, undefined, 1, p.commands.gate_final, 'TICKET_GATE_FINAL', 'full') ? 1 : 0
    case 'mutation':
      mutation(run, wt)
      return 0
    case 'close': {
      const [by, total] = costByRole(run)
      const evf = join(run, 'events.jsonl')
      const lines = isFile(evf)
        ? readFileSync(evf, 'utf8')
            .split('\n')
            .filter((l) => l.length)
        : []
      if (opt.events && lines.length) {
        mkdirp(dirname(opt.events))
        appendFileSync(opt.events, lines.join('\n') + '\n')
      }
      console.log(
        `TICKET ${run.replace(/\/+$/, '').split('/').pop()} cost=$${total.toFixed(2)} by_role=` +
          Object.entries(by)
            .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))
            .map(([k, v]) => `${k}:$${v.toFixed(2)}`)
            .join(','),
      )
      return 0
    }
    default:
      return die(`unknown action ${action}`)
  }
}
