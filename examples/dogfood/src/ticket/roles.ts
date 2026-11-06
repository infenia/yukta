// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// The "Pipeline roles" section of a ticket body (ticket-template.md): one `- role: model/effort` line per role, read by init and applied by route over the static table.
// Only a bare line binds. The router's own choice is written with a trailing ` (router)`, which the parser ignores, so the run decides those
// roles itself (from the criteria, labels and Sizing at run time) and a later change to the table or the sizing still reaches them.
// Anything else in the section is ignored; an unknown role, model or effort is an error (`yk ticket new`) or skipped (init: the static table stays).
import { type Json, fmt, join, KIT, kindOf, readText, sectionItems, testsModeFor } from './lib.ts'
import { readFileSync } from 'node:fs'
import { decide, modelsFor } from './route.ts'
import { type Sizing, measure, sizingLines } from './sizing.ts'

export const ROLE_NAMES = ['architect', 'test_writer', 'implementer', 'implementer_tricky', 'reviewer']
const MODELS = ['opus', 'sonnet', 'haiku']
const EFFORTS = ['low', 'medium', 'high']

/** `role=model/effort` (or `role: model/effort`) to [role, {model, effort}], or an error text. */
export function parseRole(s: string): [string, Json] | string {
  const m = /^\s*([a-z_]+)\s*[=:]\s*([a-z]+)\s*\/\s*([a-z]+)\s*$/.exec(s)
  if (!m || !ROLE_NAMES.includes(m[1]!) || !MODELS.includes(m[2]!) || !EFFORTS.includes(m[3]!))
    return `bad role '${s}': expected <${ROLE_NAMES.join('|')}>=<${MODELS.join('|')}>/<${EFFORTS.join('|')}>`
  return [m[1]!, { model: m[2]!, effort: m[3]! }]
}

/** The valid (binding) role lines under a 'Pipeline roles' heading, up to the next heading. */
export function rolesOf(body: string | null | undefined): Json {
  const out: Json = {}
  for (const item of sectionItems(body, /pipeline roles/i)) {
    const r = parseRole(item)
    if (Array.isArray(r)) out[r[0]] = r[1]
  }
  return out
}

/** What the router would decide for a ticket with these criteria, labels, sizing and title (the same decide and modelsFor as route.ts). */
export function routed(
  criteria: string[],
  labels: string[],
  sizing: Sizing | null = null,
  tracked: string[] | null = null,
  title = '',
): Json {
  const p = JSON.parse(readFileSync(join(KIT, 'project.json'), 'utf8'))
  const t = {
    acceptance_criteria: criteria,
    labels,
    modules: labels.filter((l) => l in p.module_labels),
    sizing: sizing ? measure(sizing, tracked) : null,
  }
  const d = decide(t, p)
  const kind = kindOf(title, labels)
  const testsMode = testsModeFor(kind, d.complexity, d.risk)
  return { ...d, kind, tests_mode: testsMode, models: modelsFor(t, p, d.complexity, d.risk, testsMode) }
}

export function renderBody(
  goal: string,
  criteria: string[],
  roles: Json,
  labels: string[] = [],
  sizing: Sizing | null = null,
  tracked: string[] | null = null,
  title = '',
): string {
  return fmt(readText(join(KIT, 'ticket-template.md')), {
    goal,
    criteria: criteria.map((c) => `- [ ] ${c}`).join('\n'),
    sizing: sizingLines(sizing),
    roles: roleLines(roles, routed(criteria, labels, sizing, tracked, title).models),
  })
}

/** `body` with the section under the heading matching `rx` replaced by `content` (appended with `title` as its heading when absent); the rest of the body is kept. */
export function replaceSection(body: string, rx: RegExp, title: string, content: string): string {
  const lines = body.split(/\r?\n/)
  const at = lines.findIndex((l) => /^\s{0,3}#{1,6}\s/.test(l) && rx.test(l))
  if (at < 0) return `${body.trimEnd()}\n\n## ${title}\n${content}\n`
  let end = lines.findIndex((l, i) => i > at && /^\s{0,3}#{1,6}\s/.test(l))
  if (end < 0) end = lines.length
  return [...lines.slice(0, at + 1), content, '', ...lines.slice(end)].join('\n').trimEnd() + '\n'
}

/** One line per role: a bare `model/effort` for the ones the ticket chose (`explicit`, binding), the router's choice marked ` (router)` for the rest. */
export const roleLines = (explicit: Json, router: Json): string =>
  ROLE_NAMES.map((k) => {
    const v = explicit[k] ?? router[k]
    const tag = explicit[k] ? '' : ' (router)'
    return v ? `- ${k}: ${v.model}/${v.effort}${tag}` : `- ${k}: none (router: not used at this size)`
  }).join('\n')
