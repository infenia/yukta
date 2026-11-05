// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// ticketkit waves ACTION RUN ...: parallel step waves (design 11.2, 15.10). Pairs: Gradle allows two runs at once, so max_parallel is 2.
//
//   next-wave                         {"mode": none|single|pair, "step": first ready step, "steps": [ids]}. Ready = depends_on DONE. A pair needs
//                                     max_parallel >= 2, disjoint write_files and no step that changes ui/package.json, the lockfile or a shared file
//                                     (those run alone: installing in a linked worktree writes into the shared copy)
//   prepare --worktree W              the second step gets its own worktree and branch from the same base commit; writes wave.json;
//                                     prints {"items": [{step, worktree}, ...]}
//   warm-wait STEP                    the sibling waits until the first step's first implementer call is in events.jsonl (its cache prefix is written),
//                                     at most TICKET_WARM_TIMEOUT seconds (300): the prefix is paid once
//   merge --worktree W                both steps DONE -> the second branch is merged into W in step order; a conflict is aborted and is an escalation
//                                     attributed to the later step, never resolved silently
//   check --worktree W                the post-wave check on the merged result (TICKET_GATE_WAVE, else the step gate against the wave base); a failure is
//                                     an escalation attributed to the later step
import { renameSync, symlinkSync } from 'node:fs'
import { basename, dirname } from 'node:path'
import {
  type Json,
  die,
  dumps,
  existsSync,
  fmt,
  git,
  isDir,
  isFile,
  join,
  load,
  parseArgs,
  readFileSync,
  resolve,
  save,
  sh,
} from './lib.ts'
import { event } from './stepkit.ts'

const SERIAL = new Set(['ui/package.json', 'ui/pnpm-lock.yaml', 'cli/go.mod', 'cli/go.sum'])
const done = (run: string, sid: string): boolean => isFile(join(run, 'steps', sid, 'status.json'))

function pick(run: string): Json {
  const p = load(join(run, 'project.json'))
  const spec = load(join(run, 'spec', 'spec.json'))
  const ready: Json[] = spec.steps.filter((s: Json) => !done(run, s.id) && (s.depends_on as string[]).every((d) => done(run, d)))
  if (!ready.length) return { mode: 'none', step: null, steps: [] }
  const serial = new Set([...SERIAL, ...(p.shared_files ?? [])])
  const alone = (s: Json): boolean => (s.write_files as string[]).some((f) => serial.has(f))
  const first = ready[0]!
  if ((p.max_parallel ?? 2) >= 2 && !alone(first)) {
    for (const other of ready.slice(1)) {
      if (!alone(other) && !(first.write_files as string[]).some((f) => (other.write_files as string[]).includes(f)))
        return { mode: 'pair', step: first.id, steps: [first.id, other.id] }
    }
  }
  return { mode: 'single', step: first.id, steps: [first.id] }
}

function escalate(run: string, sid: string, why: string): void {
  save(join(run, 'escalation.json'), { action: 'revise', trigger: 'step_failed', step: sid, reason: why, model: '-', effort: '-' })
  event([run, 'role=wave', `step_id=${sid}`, 'escalated=1'])
  console.log(`ERROR ${why}`)
}

function prepare(run: string, wt: string): number {
  const w = pick(run)
  if (w.mode !== 'pair') die('ERROR no pair is ready')
  const [a, b] = w.steps as [string, string]
  const t = load(join(run, 'ticket.json'))
  const base = git(['rev-parse', 'HEAD'], { cwd: wt }).stdout.trim()
  const wt2 = join(dirname(wt), `${basename(wt)}-${b}`)
  const branch = `issue/${t.ticket_id}-${b}`
  git(['worktree', 'add', '-q', '-b', branch, wt2, base], { cwd: wt })
  const nm = join(wt, 'ui', 'node_modules')
  if (isDir(nm) && !existsSync(join(wt2, 'ui', 'node_modules'))) symlinkSync(nm, join(wt2, 'ui', 'node_modules'))
  save(join(run, 'wave.json'), { base, steps: [a, b], worktrees: { [a]: wt, [b]: wt2 }, branch })
  console.log(
    dumps({
      items: [
        { step: a, worktree: wt },
        { step: b, worktree: wt2 },
      ],
    }),
  )
  return 0
}

function warmWait(run: string, sid: string): number {
  const f = join(run, 'wave.json')
  if (!isFile(f) || load(f).steps[1] !== sid) return 0
  const first: string = load(f).steps[0]
  const end = Date.now() + parseFloat(process.env.TICKET_WARM_TIMEOUT ?? '300') * 1000
  while (Date.now() < end) {
    const ev = join(run, 'events.jsonl')
    if (
      isFile(ev) &&
      readFileSync(ev, 'utf8')
        .split('\n')
        .some(
          (l) =>
            l.length &&
            (() => {
              const e = JSON.parse(l)
              return e.step_id === first && e.role === 'implementer'
            })(),
        )
    )
      return 0
    Bun.sleepSync(500)
  }
  console.log(`WARN ${first} never warmed the cache within the time box; going on`)
  return 0
}

function merge(run: string, wt: string): number {
  const w = load(join(run, 'wave.json'))
  const [a, b] = w.steps as [string, string]
  for (const sid of w.steps) if (!done(run, sid)) die(`ERROR ${sid} did not finish; the wave is not merged`)
  const r = git(['merge', '--no-ff', '-q', '-m', `chore: merge ${b} into the run branch`, w.branch], { cwd: wt, check: false })
  if (r.code) {
    git(['merge', '--abort'], { cwd: wt, check: false })
    const last = (r.stdout + r.stderr).trim().split('\n').pop() ?? ''
    escalate(run, b, `merge conflict between ${a} and ${b}: ${last.slice(0, 200)}`)
    return 1
  }
  git(['worktree', 'remove', '--force', w.worktrees[b]], { cwd: wt, check: false })
  git(['branch', '-q', '-D', w.branch], { cwd: wt, check: false })
  console.log(`MERGED ${b} into the run branch`)
  return 0
}

function check(run: string, wt: string): number {
  const w = load(join(run, 'wave.json'))
  const p = load(join(run, 'project.json'))
  let cmd = process.env.TICKET_GATE_WAVE ?? '-'
  cmd = cmd === '' || cmd === '-' ? p.commands.gate_step : cmd
  const r = sh(fmt(cmd, { base: w.base }), wt)
  if (r.code) {
    escalate(
      run,
      w.steps[1],
      `the merged wave [${(w.steps as string[]).map((s) => `'${s}'`).join(', ')}] fails its post-wave check: ${(r.stdout + r.stderr).trim().slice(-200)}`,
    )
    return 1
  }
  renameSync(join(run, 'wave.json'), join(run, `wave.${Date.now()}.done.json`))
  console.log('WAVE ok')
  return 0
}

export function main(argv: string[]): number {
  const { pos, opt } = parseArgs(argv, { value: ['worktree'] })
  const [action = '', run = '', arg] = pos
  const wt = resolve(opt.worktree ?? '.')
  switch (action) {
    case 'next-wave':
      console.log(dumps(pick(run)))
      return 0
    case 'prepare':
      return prepare(run, wt)
    case 'warm-wait':
      return warmWait(run, arg!)
    case 'merge':
      return merge(run, wt)
    case 'check':
      return check(run, wt)
    default:
      return die(`unknown action ${action}`)
  }
}
