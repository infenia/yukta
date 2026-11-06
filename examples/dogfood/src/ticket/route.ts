// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// ticketkit route RUN_DIR writes RUN_DIR/routing.json (design 3.1, 3.3, 15.8 static rules).
//
// complexity from the acceptance-criteria count (<= 2 S, <= 5 M, else L; more than one ecosystem: at least M); risk high for a risk or
// security label or more than one module, medium when no module label is known, else low; kind came from init.
// A ticket's Sizing section (sizing.ts, measured by init) can raise both, never lower them; routing.json `basis` records the two answers.
import { type Json, type Risk, UP_FRONT, die, join, load, matches, maxRisk, modelTable, save, testsModeFor } from './lib.ts'
import { event } from './stepkit.ts'
import { maxCx, sizingRoute } from './sizing.ts'

/** The static rules (design 15.8): complexity from the criteria count, risk from labels, modules and sensitive paths named in the criteria. */
export function staticRoute(t: Json, p: Json): [string, string] {
  const n = t.acceptance_criteria.length
  let complexity = n <= 2 ? 'S' : n <= 5 ? 'M' : 'L'
  const ecos = new Set(t.modules.map((m: string) => p.module_labels[m]))
  if (ecos.size > 1 && complexity === 'S') complexity = 'M'
  const risky = t.labels.includes('risk') || t.labels.includes('security') || t.modules.length > 1 || pathHits(t, p).length > 0
  return [complexity, risky ? 'high' : t.modules.length ? 'low' : 'medium']
}

/** Words of the acceptance criteria that look like a path matching a high_risk_paths glob. */
export function pathHits(t: Json, p: Json): string[] {
  const words = new Set<string>()
  for (const c of t.acceptance_criteria as string[])
    for (const w of c.split(/\s+/).filter(Boolean)) words.add(w.replace(/^[`'".,;:()[\]]+|[`'".,;:()[\]]+$/g, ''))
  return [...words].filter((w) => w.includes('/') && matches(w, p.high_risk_paths)).sort()
}

const EFFORTS = ['low', 'medium', 'high']

/** Complexity and risk: the static rules, raised (never lowered) by the ticket's measured sizing; `basis` says which decided what. */
export function decide(t: Json, p: Json): { complexity: string; risk: string; basis: Json } {
  const [c0, r0] = staticRoute(t, p)
  const s = t.sizing ? sizingRoute(t.sizing, p) : null
  const complexity = s ? maxCx(c0, s.complexity) : c0
  const risk = s ? maxRisk(r0 as Risk, s.risk as Risk) : r0
  return { complexity, risk, basis: { static: { complexity: c0, risk: r0 }, sizing: s } }
}

/** The models of every role: the table for the size, the architect one effort up when the ticket leaves decisions open (sizing), no test
 * writer when the tests mode writes no tests up front (gate-tests skips them: the call would be paid for nothing), then the ticket's own
 * role choices, which win. validate-spec rebuilds them the same way, without the ticket roles, when it raises the risk. */
export function modelsFor(t: Json, p: Json, complexity: string, risk: string, testsMode: string): Json {
  const m: Json = modelTable(complexity, risk, p.tier_mode, p.implementer_override)
  if (!UP_FRONT.includes(testsMode)) m.test_writer = null
  const a = m.architect
  if ((t.sizing?.open_decisions ?? 0) > 0 && a && a.effort !== 'high')
    m.architect = { ...a, effort: EFFORTS[EFFORTS.indexOf(a.effort) + 1] }
  const own = { ...(t.roles ?? {}) }
  if (!UP_FRONT.includes(testsMode)) delete own.test_writer
  return { ...m, ...own }
}

export function build(t: Json, p: Json, complexity: string, risk: string, basis: Json = null): Json {
  const testsMode = testsModeFor(t.kind, complexity, risk)
  return {
    ticket_id: t.ticket_id,
    kind: t.kind,
    complexity,
    risk,
    tests_mode: testsMode,
    allow_architect_code: risk === 'high',
    tier_mode: p.tier_mode,
    models: modelsFor(t, p, complexity, risk, testsMode),
    budget_usd: p.budgets_usd[complexity],
    ...(basis ? { basis } : {}),
  }
}

export function main(argv: string[]): number {
  if (argv.length !== 1) die('usage: ticketkit route RUN_DIR')
  const run = argv[0]!
  const t = load(join(run, 'ticket.json'))
  const p = load(join(run, 'project.json'))
  const { complexity, risk, basis } = decide(t, p)
  const r = build(t, p, complexity, risk, basis)
  save(join(run, 'routing.json'), r)
  const s = basis.sizing
  // the tuning report counts how often sizing raised the static answer (report.ts)
  event([
    run,
    'role=route',
    `complexity=${complexity}`,
    `risk=${risk}`,
    `static_complexity=${basis.static.complexity}`,
    `static_risk=${basis.static.risk}`,
    `sizing_complexity=${s?.complexity ?? '-'}`,
    `sizing_risk=${s?.risk ?? '-'}`,
  ])
  const a = r.models.architect
  console.log(
    `ROUTE complexity=${complexity} risk=${risk} tests_mode=${r.tests_mode} architect=${a.model}/${a.effort}` +
      ` static=${basis.static.complexity}/${basis.static.risk}` +
      (s ? ` sizing=${s.complexity}/${s.risk} (${s.reasons.join('; ')})` : ' sizing=-'),
  )
  return 0
}
