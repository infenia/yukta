// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// ticketkit decision NAME RUN_DIR [STEP [WORKTREE]]: one decision of the local model (design 3.2, 15.8), through the `decide` helper of 11-dogfood.
//   NAME: triage (triage_complexity and triage_risk, after route) | cause | merge_risk
// The static rule decides in shadow mode; the model's answer is only logged (decisions.tsv, `yk stats --decisions`) next to the static label as the
// outcome. In mode `on` (decisions.json "mode", or YUKTA_DECISION_<NAME>=on: a config change, nothing else) the model's label is used when it answered
// with enough confidence. No model, an error, an invalid answer or a digest over YUKTA_DECISION_MAX_INPUT: the static label, one WARN line.
// Prints one JSON line per decision {name, label, source, static}.
//
// The code side (design 3.2, 15.8): the digest the model reads (per-file facts, never a diff) and the label the static rule gives, and what is done with the
// label (triage rewrites routing.json, cause and merge_risk are written to cause.json / merge_risk.json in the run).
import { decide, decisionOutcome } from '../../11-dogfood/src/lib.ts'
import { DOGFOOD, type Json, die, existsSync, git, isFile, join, kit, load, readdirSync, readFileSync, resolve, save } from './lib.ts'
import { pathHits, staticRoute } from './route.ts'

const DEC_CFG = join(DOGFOOD, 'decisions.json')

function lastAttempt(run: string, sid: string): number {
  const dir = join(run, 'steps', sid)
  const nums = existsSync(dir) ? readdirSync(dir).filter(d => d.startsWith('attempt-')).map(d => Number(d.split('-')[1])).sort((a, b) => a - b) : []
  return nums.length ? nums[nums.length - 1]! : 1
}

/** files / added / removed since the first step base, from git numstat (counts only). */
function diffFacts(run: string, wt?: string): [number, number, number] {
  const stepsDir = join(run, 'steps')
  const bases = existsSync(stepsDir) ? readdirSync(stepsDir).sort().map(s => join(stepsDir, s, 'base')).filter(isFile) : []
  if (!bases.length || !wt) return [0, 0, 0]
  const out = git(['diff', '--numstat', readFileSync(bases[0]!, 'utf8').trim(), 'HEAD'], { cwd: wt, check: false }).stdout.split('\n')
  const rows = out.filter(l => l.trim()).map(l => l.split('\t'))
  const num = (x: string | undefined): number => (x && /^\d+$/.test(x) ? parseInt(x, 10) : 0)
  return [rows.length, rows.reduce((a, r) => a + num(r[0]), 0), rows.reduce((a, r) => a + num(r[1]), 0)]
}

function mergeStatic(risk: string, added: number, removed: number, revisions: number): string {
  const lines = added + removed
  const base: Record<string, number> = { low: 1, medium: 4, high: 7 }
  return String(Math.min(10, base[risk]! + (lines > 400 ? 2 : lines > 150 ? 1 : 0) + Math.min(1, revisions)))
}

export function inputs(name: string, run: string, sid: string | undefined, wt?: string): { state: string; static: string } {
  const t = load(join(run, 'ticket.json'))
  const p = load(join(run, 'project.json'))
  const [cx, rk] = staticRoute(t, p)
  const eco = [...new Set(t.modules.map((m: string) => p.module_labels[m]))].sort()
  if (name === 'triage_complexity') return { state: `kind=${t.kind} criteria=${t.acceptance_criteria.length} modules=${t.modules.join(',') || '-'} ecosystems=${eco.join(',') || '-'}`, static: cx }
  if (name === 'triage_risk') {
    return { state: `labels=${(t.labels as string[]).filter(l => l === 'risk' || l === 'security').join(',') || '-'} modules=${t.modules.length} sensitive_paths=${pathHits(t, p).length} kind=${t.kind}`, static: rk }
  }
  if (name === 'cause') {
    const n = lastAttempt(run, sid!)
    const f = load(join(run, 'steps', sid!, `attempt-${n}`, 'failure.json'))
    const prev = join(run, 'steps', sid!, `attempt-${n - 1}`, 'failure.json')
    const same = isFile(prev) && load(prev).message === f.message
    return { state: `stage=${f.stage} class=${f.class} attempt=${n} failing=${(f.failing ?? []).length} repeated=${same ? 'yes' : 'no'} message=${(f.message ?? '').slice(0, 200)}`, static: 'impl_bug' } // unknown -> impl_bug (15.5)
  }
  if (name === 'merge_risk') {
    const [files, added, removed] = diffFacts(run, wt)
    const rv = isFile(join(run, 'revisions.json')) ? load(join(run, 'revisions.json')).count : 0
    const risk = load(join(run, 'routing.json')).risk
    return { state: `risk=${risk} files=${files} added=${added} removed=${removed} modules=${t.modules.length} revisions=${rv}`, static: mergeStatic(risk, added, removed, rv) }
  }
  return die(`unknown decision ${name}`)
}

export function apply(name: string, run: string, label: string, sid?: string): void {
  if (name === 'triage_complexity' || name === 'triage_risk') {
    const r = load(join(run, 'routing.json'))
    const [cx, rk] = name === 'triage_complexity' ? [label, r.risk] : [r.complexity, label]
    if (cx !== r.complexity || rk !== r.risk) {
      if (kit(['route', run, '--set', `complexity=${cx}`, `risk=${rk}`]).code) die('route failed')
    }
  } else if (name === 'cause') save(join(run, 'steps', sid!, 'cause.json'), { cause: label, attempt: lastAttempt(run, sid!) })
  else save(join(run, 'merge_risk.json'), { merge_risk: parseInt(label, 10) })
}

async function one(name: string, run: string, step: string, wtArg: string): Promise<void> {
  const wt = wtArg ? resolve(wtArg) : undefined
  const { state, static: staticLabel } = inputs(name, run, step === '-' ? undefined : step, wt)
  const cfg: Json = load(DEC_CFG).decisions[name]
  const min = cfg.minProbability ?? 0.6
  let label = staticLabel
  let source = 'static'
  // decide returns an answer only in mode on; in shadow it logs the answer and returns null
  const out = await decide(name, state, cfg.question, cfg.criteria)
  if (out && Number(out[1]) >= min) { label = out[0]; source = 'model' }
  decisionOutcome(name, state, staticLabel) // what the static rule says is the reference the model is judged against
  apply(name, run, label, step === '-' ? undefined : step)
  console.log(JSON.stringify({ name, label, source, static: staticLabel }))
}

export async function main(argv: string[]): Promise<number> {
  const [sub, run, step, wt] = argv
  switch (sub) {
    case 'triage': if (!run) die('RUN'); await one('triage_complexity', run, '-', ''); await one('triage_risk', run, '-', ''); return 0
    case 'cause': if (!run || !step) die('usage: ticketkit decision cause RUN_DIR STEP'); await one('cause', run, step, ''); return 0
    case 'merge_risk': if (!run) die('RUN'); await one('merge_risk', run, '-', wt ?? ''); return 0
    default: console.error('usage: ticketkit decision triage|cause|merge_risk RUN_DIR [STEP [WORKTREE]]'); return 2
  }
}
