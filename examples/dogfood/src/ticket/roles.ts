// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// The "Pipeline roles" section of a ticket body (ticket-template.md): one `- role: model/effort` line per role, read by init and applied by route over the static table.
// Anything else in the section is ignored; an unknown role, model or effort is an error (`yk ticket new`) or skipped (init: the static table stays).
import { type Json, fmt, join, KIT, modelTable, readText } from './lib.ts'
import { readFileSync } from 'node:fs'
import { staticRoute } from './route.ts'

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

/** The valid role lines under a 'Pipeline roles' heading, up to the next heading. */
export function rolesOf(body: string | null | undefined): Json {
  const out: Json = {}
  let on = false
  for (const line of (body ?? '').split(/\r?\n/)) {
    if (/^\s{0,3}#{1,6}\s/.test(line)) {
      on = /pipeline roles/i.test(line)
      continue
    }
    const m = /^\s*[-*]\s+(.+)/.exec(line)
    const r = on && m ? parseRole(m[1]!) : null
    if (Array.isArray(r)) out[r[0]] = r[1]
  }
  return out
}

/** Every role, the ones you gave plus the router's own choice for the rest (same staticRoute and modelTable as route.ts, so modules and high-risk paths count too). */
export function withDefaults(criteria: string[], labels: string[], roles: Json): Json {
  const p = JSON.parse(readFileSync(join(KIT, 'project.json'), 'utf8'))
  const t = { acceptance_criteria: criteria, labels, modules: labels.filter((l) => l in p.module_labels) }
  const [complexity, risk] = staticRoute(t, p)
  const d = modelTable(complexity, risk, p.tier_mode, p.implementer_override)
  return Object.fromEntries(ROLE_NAMES.map((r) => [r, roles[r] ?? d[r]]))
}

export function renderBody(goal: string, criteria: string[], roles: Json, labels: string[] = []): string {
  const all = withDefaults(criteria, labels, roles)
  // a null role (no test writer for a small low-risk ticket) is written as a line the parser ignores, so the router keeps it unused
  return fmt(readText(join(KIT, 'ticket-template.md')), {
    goal,
    criteria: criteria.map((c) => `- [ ] ${c}`).join('\n'),
    roles: roleLines(all),
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

export const roleLines = (roles: Json): string =>
  Object.entries(roles)
    .map(([k, v]: [string, Json]) => (v ? `- ${k}: ${v.model}/${v.effort}` : `- ${k}: none (not used at this size)`))
    .join('\n')
