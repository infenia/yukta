// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// ticketkit advice RUN_ID GATE: the facts about one question of a ticket run, for the dogfood supervisor (#1923, design/plans/ticket-input-routing.md 3.2).
//   RUN_ID  the run (request context run_id); it is request data, so only [A-Za-z0-9._-] is accepted and the run must exist under $YUKTA_DEV_STATE_DIR/ticket
//   GATE    the node that asked: approve_spec | ask_pause<n> | approve_merge | ask_clarify<n>
// --request ID is echoed as request_id, so the workflow keeps the id of the request across the process node.
// Prints one JSON line {gate, recommendation, confidence, advice, request_id}. No model, no tokens, read-only. The supervisor sends it as the advice of an escalation
// (a person or the session sees it), and answers nothing itself: the gates are HIGH and the supervisor's capabilities stop at LOW.
//   recommendation: approve | hold | review | ask-person     confidence: how sure the facts make it (never above 0.9)
import { type Json, costByRole, die, dumps, isFile, join, load, parseArgs } from './lib.ts'

const RUN_ID = /^[A-Za-z0-9._-]{1,120}$/
const readOr = (p: string, d: Json = {}): Json => (isFile(p) ? load(p) : d)

export function runDirOf(runId: string, state = process.env.YUKTA_DEV_STATE_DIR ?? ''): string {
  if (!RUN_ID.test(runId) || runId === '.' || runId === '..') die(`ERROR run id '${runId.slice(0, 40)}' is not valid`)
  const dir = join(state, 'ticket', runId)
  if (!state || !isFile(join(dir, 'ticket.json'))) die(`ERROR no run ${runId}`)
  return dir
}

type Advice = { recommendation: string; confidence: number; advice: string }

function spec(run: string): Advice {
  const s = readOr(join(run, 'spec', 'spec.json'), null)
  if (!s) return { recommendation: 'review', confidence: 0.3, advice: 'No spec.json in the run yet.' }
  const cases: string[] = (s.test_cases ?? []).map((c: Json) => c.id)
  const covered = new Set<string>((s.steps ?? []).flatMap((st: Json) => st.covers ?? []))
  const open = cases.filter((c) => !covered.has(c))
  const risk = readOr(join(run, 'routing.json')).risk ?? s.risk ?? '?'
  const facts = `Spec v${s.spec_version}: ${(s.steps ?? []).length} steps, ${cases.length} test cases, ${(s.acceptance_criteria ?? []).length} acceptance criteria, risk ${risk}.`
  if (open.length) return { recommendation: 'hold', confidence: 0.8, advice: `${facts} Cases no step covers: ${open.join(', ')}.` }
  return {
    recommendation: 'review',
    confidence: 0.6,
    advice: `${facts} Every case is covered by a step; read spec/spec.json before approving (high risk: a person decides).`,
  }
}

function merge(run: string): Advice {
  const r = readOr(join(run, 'review', 'result.json'), null)
  if (!r) return { recommendation: 'hold', confidence: 0.7, advice: 'There is no review/result.json: the reviewer has not signed off.' }
  const findings: Json[] = r.findings ?? []
  const bad = findings.filter((f) => f.severity === 'blocker' || f.severity === 'major')
  const survivors = readOr(join(run, 'review', 'mutation.json')).survivor_rate
  const failed = (r.criteria_results ?? []).filter((c: Json) => c.met === false).map((c: Json) => c.id)
  const [, cost] = costByRole(run)
  const facts = `Review ${r.verdict}, ${findings.length} findings (${bad.length} blocker or major)${failed.length ? `, unmet: ${failed.join(', ')}` : ''}${survivors === undefined ? '' : `, mutation survivors ${Math.round(survivors * 100)}%`}, revisions ${readOr(join(run, 'revisions.json')).count ?? 0}, cost $${cost.toFixed(2)}.`
  if (r.verdict !== 'APPROVE' || bad.length || failed.length) return { recommendation: 'hold', confidence: 0.85, advice: facts }
  return {
    recommendation: isFile(join(run, 'final-result.txt')) ? 'approve' : 'review',
    confidence: 0.7,
    advice: `${facts} Nothing blocks the PR; it is a draft, a person merges.`,
  }
}

function pause(run: string): Advice {
  const e = readOr(join(run, 'escalation.json'))
  const [, cost] = costByRole(run)
  return {
    recommendation: 'review',
    confidence: 0.5,
    advice: `Paused: ${String(e.reason ?? 'unknown').slice(0, 200)} (step ${e.step ?? '-'}), cost so far $${cost.toFixed(2)}. Approving doubles the budgets.`,
  }
}

function clarify(run: string): Advice {
  const q: Json[] = readOr(join(run, 'spec', 'needs-clarification.json')).questions ?? []
  return {
    recommendation: 'ask-person',
    confidence: 0.6,
    advice: `${q.length} question(s) of the architect: ${q.map((x) => x.id).join(', ')}. The ticket does not settle them; a requirement or preference is the person's.`,
  }
}

export function adviceFor(run: string, gate: string): Advice & { gate: string } {
  const a = /^approve_spec$/.test(gate)
    ? spec(run)
    : /^approve_merge$/.test(gate)
      ? merge(run)
      : /^ask_pause\d+$/.test(gate)
        ? pause(run)
        : /^ask_clarify\d+$/.test(gate)
          ? clarify(run)
          : { recommendation: 'review', confidence: 0.2, advice: `No facts for the gate '${gate.slice(0, 40)}'.` }
  return { gate: gate.slice(0, 40), ...a, confidence: Math.min(0.9, a.confidence), advice: a.advice.slice(0, 600) }
}

export function main(argv: string[]): number {
  const {
    pos: [runId, gate],
    opt,
  } = parseArgs(argv, { value: ['request'] })
  if (!runId || !gate) die('usage: ticketkit advice RUN_ID GATE [--request ID]', 2)
  console.log(dumps({ ...adviceFor(runDirOf(runId), gate), request_id: (opt.request ?? '').slice(0, 120) }))
  return 0
}
