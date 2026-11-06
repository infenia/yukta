// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// The "Sizing" section of a ticket body (ticket-template.md): facts the agent that drafts the ticket reads off the code, one `- key: value` line each.
//   - files: ui/src/router.tsx, ui/src/features/overview/**     paths or globs the change writes or deletes (a path not in the tree is a new file)
//   - change: delete, rewire                                     kinds of change, from CHANGE_KINDS
//   - open_decisions: 0                                          choices the ticket leaves to the architect
// Code, not the agent, turns the facts into complexity and risk (route), and only ever upward: sizing can raise what the static rules decide
// from the criteria and labels, never lower it, so an optimistic draft cannot put a risky ticket on a cheaper model. The globs are checked
// against the base tree (`measure`), the same high_risk_paths rule as validate-spec applies to them, so the route and the validator agree.
import { type Json, git, globRe, matches, sectionItems } from './lib.ts'

export const CHANGE_KINDS = [
  'delete',
  'rename',
  'rewire',
  'docs',
  'config',
  'new_logic',
  'concurrency',
  'schema',
  'api_contract',
  'security',
]
/** Kinds that are never S whatever the file count: a contract, a schema or shared state has callers the criteria do not name. */
const HEAVY = new Set(['concurrency', 'schema', 'api_contract', 'security'])
const CX = ['S', 'M', 'L']
export const maxCx = (a: string, b: string): string => (CX.indexOf(b) > CX.indexOf(a) ? b : a)
/** The paths kept in ticket.json (enough to route on; a larger change is L anyway). */
const KEEP = 200

export type Sizing = { files: string[]; change: string[]; open_decisions: number }

/** One `key: value` (or `key=value`) item: [key, value], null for an item that is not a sizing key (ignored), or an error text. */
function parseItem(s: string): [string, unknown] | null | string {
  const m = /^([a-z_]+)\s*[=:]\s*(.*)$/.exec(s.trim())
  if (!m || !['files', 'change', 'open_decisions'].includes(m[1]!)) return null
  const [, k, v] = m as unknown as [string, string, string]
  if (k === 'open_decisions') return /^\d+$/.test(v.trim()) ? [k, Number(v.trim())] : `bad sizing '${s}': open_decisions is a whole number`
  const list = v
    .split(/[,\s]+/)
    .map((x) => x.replace(/^[`'"]+|[`'"]+$/g, ''))
    .filter(Boolean)
  if (k === 'files') {
    const bad = list.find((f) => f.startsWith('/') || f.split('/').includes('..'))
    return bad ? `bad sizing '${s}': ${bad} is not a path inside the repository` : [k, list]
  }
  const bad = list.find((c) => !CHANGE_KINDS.includes(c))
  return bad ? `bad sizing '${s}': change '${bad}' is not one of ${CHANGE_KINDS.join('|')}` : [k, list]
}

/** The sizing section of `body` (null when it gives no files and no change kinds) and the errors of its bad items. */
export const sizingOf = (body: string | null | undefined): { sizing: Sizing | null; errors: string[] } =>
  sizingFrom(sectionItems(body, SECTION))

/** The heading of the section. */
export const SECTION = /^\s{0,3}#{1,6}\s+sizing\b/i

/** Sizing from `key: value` items (a later `files` or `change` item adds to the list, a later `open_decisions` replaces it). */
export function sizingFrom(items: string[]): { sizing: Sizing | null; errors: string[] } {
  const s: Sizing = { files: [], change: [], open_decisions: 0 }
  const errors: string[] = []
  for (const item of items) {
    const r = parseItem(item)
    if (typeof r === 'string') errors.push(r)
    else if (r && r[0] === 'open_decisions') s.open_decisions = r[1] as number
    else if (r) s[r[0] as 'files' | 'change'].push(...(r[1] as string[]))
  }
  s.files = [...new Set(s.files)]
  s.change = [...new Set(s.change)]
  return { sizing: s.files.length || s.change.length ? s : null, errors }
}

/** `- key: value` lines for the body. */
export const sizingLines = (s: Sizing | null): string =>
  s
    ? [`- files: ${s.files.join(', ') || '-'}`, `- change: ${s.change.join(', ') || '-'}`, `- open_decisions: ${s.open_decisions}`].join(
        '\n',
      )
    : '- none given (the router uses the criteria and labels only)'

/** The paths in `base` of the repository at `root`, or null when git cannot list them (then every entry counts as one file). */
export function trackedFiles(root: string, base: string): string[] | null {
  const r = git(['ls-tree', '-r', '--name-only', base], { cwd: root, check: false })
  return r.code ? null : r.stdout.split('\n').filter(Boolean)
}

/** The sizing with what its entries stand for in the tree: the paths they match (a directory is everything under it), the new files and
 * the globs that match nothing. Without a tree (`tracked` null) every entry is taken as written. */
export function measure(s: Sizing, tracked: string[] | null): Json {
  const paths = new Set<string>()
  const created: string[] = []
  const unmatched: string[] = []
  for (const f of s.files) {
    if (!tracked) {
      paths.add(f)
      continue
    }
    const glob = /[*?]/.test(f)
    const dir = f.replace(/\/+$/, '') + '/'
    const hit = tracked.filter((p) => (glob ? globRe(f).test(p) : p === f || p.startsWith(dir)))
    if (hit.length) hit.forEach((p) => paths.add(p))
    else if (glob) unmatched.push(f)
    else created.push(f)
  }
  const all = [...[...paths].sort(), ...created]
  return {
    ...s,
    file_count: all.length,
    paths: all.slice(0, KEEP),
    new_files: created,
    unmatched,
    verified: Boolean(tracked),
  }
}

/** Complexity and risk from measured sizing facts, and why: the same scale as staticRoute, so route can take the higher of the two. */
export function sizingRoute(m: Json, p: Json): { complexity: string; risk: string; reasons: string[] } {
  const reasons: string[] = []
  const n: number = m.file_count ?? 0
  let complexity = n <= 3 ? 'S' : n <= 10 ? 'M' : 'L'
  reasons.push(`${n} file(s)`)
  const raise = (to: string, why: string): void => {
    if (CX.indexOf(to) > CX.indexOf(complexity)) {
      complexity = to
      reasons.push(why)
    }
  }
  const heavy = (m.change as string[]).filter((c) => HEAVY.has(c))
  if (heavy.length) raise('M', `change ${heavy.join(', ')}`)
  if (m.open_decisions >= 2) raise('M', `${m.open_decisions} open decisions`)
  const top = (path: string): string => path.split('/')[0]!
  const modules = [...new Set((m.paths as string[]).map(top).filter((t) => t in p.module_labels))].sort()
  const ecos = new Set(modules.map((x) => p.module_labels[x]))
  if (ecos.size > 1) raise('M', `more than one ecosystem (${[...ecos].sort().join(', ')})`)
  let risk = 'low'
  const hot = (m.paths as string[]).filter((x) => matches(x, p.high_risk_paths))
  if (hot.length) {
    risk = 'high'
    reasons.push(`high-risk path ${hot.slice(0, 3).join(', ')}${hot.length > 3 ? ` (+${hot.length - 3})` : ''}`)
  }
  if (modules.length > 1) {
    risk = 'high'
    reasons.push(`more than one module (${modules.join(', ')})`)
  }
  if ((m.change as string[]).includes('security')) {
    risk = 'high'
    reasons.push('change security')
  }
  return { complexity, risk, reasons }
}
