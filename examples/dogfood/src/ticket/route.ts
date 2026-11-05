// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// ticketkit route RUN_DIR writes RUN_DIR/routing.json (design 3.1, 3.3, 15.8 static rules).
//
// complexity from the acceptance-criteria count (<= 2 S, <= 5 M, else L; more than one ecosystem: at least M); risk high for a risk or
// security label or more than one module, medium when no module label is known, else low; kind came from init.
import { type Json, die, join, load, matches, modelTable, save, testsModeFor } from './lib.ts'

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

export function build(t: Json, p: Json, complexity: string, risk: string): Json {
  return {
    ticket_id: t.ticket_id,
    kind: t.kind,
    complexity,
    risk,
    tests_mode: testsModeFor(t.kind, complexity, risk),
    allow_architect_code: risk === 'high',
    tier_mode: p.tier_mode,
    models: { ...modelTable(complexity, risk, p.tier_mode, p.implementer_override), ...t.roles }, // roles: the ticket's own choice wins
    budget_usd: p.budgets_usd[complexity],
  }
}

export function main(argv: string[]): number {
  if (argv.length !== 1) die('usage: ticketkit route RUN_DIR')
  const run = argv[0]!
  const t = load(join(run, 'ticket.json'))
  const p = load(join(run, 'project.json'))
  const [complexity, risk] = staticRoute(t, p)
  const r = build(t, p, complexity, risk)
  save(join(run, 'routing.json'), r)
  console.log(`ROUTE complexity=${complexity} risk=${risk} tests_mode=${r.tests_mode} architect=${r.models.architect.model}`)
  return 0
}
