// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// ticketkit github ACTION ... : the GitHub side of the pipeline (design 15.9). It reuses pickup's rules.
//
//   intake  ISSUE               open, assigned to the user, Ready on the project, acceptance criteria. SKIPPED (exit 3) if not; BLOCKED (exit 4) when an
//                               acceptance criterion asks for secrets, a push to main, other repos, changed settings or skipped checks. Text outside the
//                               criteria that asks for such things is data and ignored (counted in the output)
//   claim   ISSUE [STATUS]      move the issue on the project (default "In progress")
//   plan-comment RUN            post the spec summary (objective, steps, case table) as an issue comment
//   dead-end RUN --stage S --reason R [--worktree W]   issue back to Ready and a comment: what blocked it and what was tried; a worktree whose branch has
//                               no commits of its own is released (its diff kept in RUN/leftover.patch), so the issue can run again
//   finish  RUN --worktree W    spotlessApply, commit, push issue/<n>, open a DRAFT PR (conventional title, `Fixes #n`, the RESULT line, spec summary,
//                               cost by role); fails when the body lacks what project.json pr_body_must_contain names. Never merges, never pushes main
//
// Env: YUKTA_PICKUP_REPO (infenia/yukta), YUKTA_PICKUP_PROJECT (5), YUKTA_PICKUP_OWNER (infenia), TICKET_FORMAT (override of the format command).
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import {
  type Json,
  costByRole,
  die,
  git,
  isDir,
  isFile,
  join,
  load,
  parseArgs,
  readFileSync,
  readdirSync,
  resolve,
  run as runProc,
  sh,
  writeFileSync,
} from './lib.ts'
import { criteria } from './init.ts'
import { release, releaseFailed } from './branch.ts'
import { untag } from './gate-tests.ts'

const REPO = process.env.YUKTA_PICKUP_REPO ?? 'infenia/yukta'
const PROJECT = process.env.YUKTA_PICKUP_PROJECT ?? '5'
const OWNER = process.env.YUKTA_PICKUP_OWNER ?? 'infenia'
const TYPES: Record<string, string> = { feature: 'feat', bug: 'fix', refactor: 'refactor', docs: 'docs', chore: 'chore' }
export const CONVENTIONAL = /^(feat|fix|docs|refactor|test|chore|style|ci|build|perf|revert)(\([^)]*\))?!?: \S/
const SECRET = '(api[ _-]?keys?|secrets?|tokens?|passwords?|credentials?)'
const FORBIDDEN: [RegExp, string][] = [
  [
    new RegExp(
      `\\b(print|show|echo|send|post|reveal|leak|output|exfiltrate|dump)\\b.*\\b${SECRET}|\\b${SECRET}\\b.*\\b(print|show|echo|send|reveal|leak|exfiltrate|dump)\\b`,
      'i',
    ),
    'asks for secrets',
  ],
  [
    /\b(force[- ])?push\w*\b.*\b(to|on|into)\s+(origin\/)?(main|master)\b|\bmerge\b.*\b(the pr|pull request|into main)\b/i,
    'asks to push to main or merge',
  ],
  [/\b(other|another|different) repo(sitory)?\b|github\.com\/(?!infenia\/yukta)/i, 'names another repository'],
  [
    /\b(change|modify|edit|disable|update|weaken)\b.*\b(settings|permissions|branch protection|codeowners|coverage threshold)/i,
    'asks to change settings',
  ],
  [
    /\b(skip|bypass|disable|ignore)\w*\b.*\b(checks?|tests?|gates?|hooks?|ci|coverage)\b|--no-verify|dangerously-skip-permissions/i,
    'asks to skip checks',
  ],
]

function gh(args: string[], check = true, env?: Record<string, string>): string {
  const r = runProc(['gh', ...args], { env })
  if (check && r.code) die(`gh ${args.slice(0, 3).join(' ')}: ${r.stderr.trim().slice(0, 300)}`)
  return r.stdout
}

function intake(n: string): number {
  const v = JSON.parse(gh(['issue', 'view', n, '-R', REPO, '--json', 'number,state,assignees,title,body,labels,url,projectItems']))
  const me = gh(['api', 'user', '-q', '.login']).trim()
  // the issue's own project items: `project item-list --limit 5000` costs thousands of GraphQL points and fails when the shared budget is low
  // ponytail: matches any project's Ready status (the item names its project by title only); add a title check if the issue sits on several boards
  const ready = (v.projectItems ?? []).some((i: Json) => i.status?.name === 'Ready')
  if (String(v.state).toUpperCase() !== 'OPEN') {
    console.log('SKIPPED the issue is not open')
    return 3
  }
  if (!v.assignees.some((a: Json) => a.login === me)) {
    console.log(`SKIPPED the issue is not assigned to ${me}`)
    return 3
  }
  if (!ready) {
    console.log(`SKIPPED the issue is not Ready on project ${PROJECT}`)
    return 3
  }
  const ac = criteria(v.body)
  if (!ac.length) {
    console.log('SKIPPED no acceptance criteria')
    return 3
  }
  for (const item of ac)
    for (const [rx, why] of FORBIDDEN)
      if (rx.test(item)) {
        console.log(`BLOCKED an acceptance criterion ${why}: ${item.slice(0, 120)}`)
        return 4
      }
  const rest = (v.body ?? '').replace(ac.join('\n'), '')
  console.log(`ELIGIBLE criteria=${ac.length} ignored_body_instructions=${FORBIDDEN.filter(([rx]) => rx.test(rest)).length}`)
  return 0
}

/** Adds the issue to the project (a no-op when it is there) and sets the Status field by option name; an unknown field or option is reported and skipped. */
function projectSetStatus(url: string, status: string, token: string): void {
  const env = { GH_TOKEN: token }
  const projectId = JSON.parse(gh(['project', 'view', PROJECT, '--owner', OWNER, '--format', 'json'], true, env)).id as string
  const fields: Json[] = JSON.parse(
    gh(['project', 'field-list', PROJECT, '--owner', OWNER, '--limit', '100', '--format', 'json'], true, env),
  ).fields
  const item = JSON.parse(gh(['project', 'item-add', PROJECT, '--owner', OWNER, '--url', url, '--format', 'json'], true, env)).id as string
  const field = fields.find((f) => f.name === 'Status')
  if (!field) {
    console.error('project: no field "Status"; skipped')
    return
  }
  const option = (field.options ?? []).find((o: Json) => o.name === status)?.id
  if (!option) {
    console.error(`project: field "Status" has no option "${status}"; skipped`)
    return
  }
  gh(
    ['project', 'item-edit', '--id', item, '--project-id', projectId, '--field-id', field.id, '--single-select-option-id', option],
    true,
    env,
  )
}

function setStatus(n: string, status: string): void {
  const url = gh(['issue', 'view', n, '-R', REPO, '--json', 'url', '-q', '.url']).trim()
  projectSetStatus(url, status, gh(['auth', 'token']).trim())
  console.log(`STATUS #${n} ${status}`)
}

function comment(n: string, body: string): void {
  const dir = mkdtempSync(join(tmpdir(), 'ticket-comment-'))
  const f = join(dir, 'comment.md')
  writeFileSync(f, body)
  try {
    gh(['issue', 'comment', n, '-R', REPO, '-F', f])
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
}

function specSummary(run: string): string {
  const spec = load(join(run, 'spec', 'spec.json'))
  const cell = (x: unknown): string => String(x).replaceAll('|', '\\|').replaceAll('\n', ' ')
  const out = [`**Objective:** ${spec.objective}`, `**Risk:** ${spec.risk} · **Tests:** ${spec.tests_mode}`, '', '**Steps**']
  for (const s of spec.steps)
    out.push(
      `- ${s.id} ${s.title} (\`${(s.write_files as string[]).join('`, `')}\`; covers ${(s.covers as string[]).join(', ') || 'none'})`,
    )
  out.push('', '**Test cases**', '', '| id | title | given | when | expect |', '|---|---|---|---|---|')
  for (const c of spec.test_cases) out.push(`| ${c.id} | ${cell(c.title)} | ${cell(c.given)} | ${cell(c.when)} | ${cell(c.expect)} |`)
  return out.join('\n')
}

const num = (x: number): string => x.toLocaleString('en-US')
const usd = (x: number): string => (x > 0 && x < 0.01 ? '<$0.01' : `$${x.toFixed(2)}`)
/** Agent text in markdown: `<Tag />` would be read as HTML and vanish. */
const md = (x: unknown): string =>
  String(x ?? '')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')

/** Tokens per role from events.jsonl: one row per role (calls, input, output, cache read / write, cost) and a total. */
export function tokenTable(run: string): string {
  const f = join(run, 'events.jsonl')
  const rows: Record<string, { models: Set<string>; n: number[] }> = {}
  for (const l of isFile(f) ? readFileSync(f, 'utf8').split('\n') : []) {
    if (!l.trim()) continue
    const e = JSON.parse(l)
    if (!('tokens_in' in e)) continue
    const r = (rows[e.role] ??= { models: new Set(), n: [0, 0, 0, 0, 0, 0] })
    if (e.model) r.models.add(e.model)
    ;[1, e.tokens_in, e.tokens_out, e.cache_read_tokens, e.cache_write_tokens, e.cost_usd].forEach((v, i) => (r.n[i]! += v ?? 0))
  }
  const total = [0, 0, 0, 0, 0, 0]
  const line = (name: string, models: string, n: number[]): string =>
    `| ${name} | ${models} | ${n.slice(0, 5).map(num).join(' | ')} | ${usd(n[5]!)} |`
  const out = ['| role | model | calls | input | output | cache read | cache write | cost |', '|---|---|--:|--:|--:|--:|--:|--:|']
  for (const [role, r] of Object.entries(rows).sort(([a], [b]) => (a < b ? -1 : 1))) {
    r.n.forEach((v, i) => (total[i]! += v))
    out.push(line(role, [...r.models].sort().join(', '), r.n))
  }
  out.push(line('**total**', '', total))
  return out.join('\n')
}

/** The draft PR's description: what changed and why, how it was checked, and what the run used. */
export function prBody(run: string, wt: string, n: string, result: string, baseline: string): string {
  const spec = load(join(run, 'spec', 'spec.json'))
  const cell = (x: unknown): string => md(x).replaceAll('|', '\\|').replaceAll('\n', ' ')
  const rf = join(run, 'review', 'result.json')
  const review: Json = isFile(rf) ? load(rf) : { verdict: '-', criteria_results: [], findings: [] }
  const met: Record<string, Json> = Object.fromEntries((review.criteria_results as Json[]).map((c) => [c.id, c]))
  const tm = join(run, 'spec', 'test_map.json')
  const tests: Record<string, string[]> = isFile(tm) ? load(tm) : {}
  const stat = git(['diff', '--stat', 'origin/main...HEAD'], { cwd: wt, check: false }).stdout.trim()
  const out = [`Fixes #${n}`, '', '## Summary', '', md(spec.objective), '', `**Risk:** ${spec.risk} · **Tests:** ${spec.tests_mode}`, '']
  out.push('## Changes', '')
  for (const s of spec.steps) {
    out.push(`- **${s.title}** (${s.id}; \`${(s.write_files as string[]).join('`, `')}\`)`)
    for (const i of s.instructions ?? []) out.push(`  - ${md(i)}`)
  }
  if (stat) out.push('', '<details><summary>Files changed</summary>', '', '```', stat, '```', '', '</details>')
  out.push('', '## Acceptance criteria', '', '| | criterion | evidence |', '|---|---|---|')
  for (const a of spec.acceptance_criteria) {
    const r = met[a.id]
    out.push(`| ${r ? (r.met ? '✅' : '❌') : '–'} | ${cell(a.text)} | ${cell(r?.evidence)} |`)
  }
  out.push('', '## Review', '', `**Verdict:** ${review.verdict}`)
  const tf = met.TESTS_FAITHFUL
  if (tf) out.push(`**Tests faithful to the cases:** ${tf.met ? 'yes' : 'no'} (${md(tf.evidence)})`)
  const findings = review.findings as Json[]
  out.push(
    '',
    ...(findings.length
      ? findings.map((x) => `- **${x.severity}** \`${x.path}${x.line ? `:${x.line}` : ''}\`: ${md(x.issue)} → ${md(x.suggested_action)}`)
      : ['No findings.']),
  )
  if ((spec.assumptions ?? []).length) out.push('', '## Assumptions', '', ...(spec.assumptions as string[]).map((a) => `- ${md(a)}`))
  out.push('', '## Tests', '', '| case | title | given | when | expect | tests |', '|---|---|---|---|---|---|')
  for (const c of spec.test_cases)
    out.push(
      `| ${c.id} | ${cell(c.title)} | ${cell(c.given)} | ${cell(c.when)} | ${cell(c.expect)} | ${cell((tests[c.id] ?? []).map(untag).join('; '))} |`,
    )
  out.push('', '## Final gate', '', '```', result, '```', baseline)
  out.push('## Token usage', '', tokenTable(run), '', '🤖 Generated with [Claude Code](https://claude.com/claude-code)', '')
  return out.join('\n')
}

function costLines(run: string): string {
  const [by, total] = costByRole(run)
  return [
    ...Object.entries(by)
      .sort(([a], [b]) => (a < b ? -1 : 1))
      .map(([k, v]) => `- ${k}: $${v.toFixed(2)}`),
    `- total: $${total.toFixed(2)}`,
  ].join('\n')
}

const runName = (run: string): string => run.replace(/\/+$/, '').split('/').pop()!

function deadEnd(run: string, stage: string, reason: string, wt?: string): void {
  const t = load(join(run, 'ticket.json'))
  const tried = [costLines(run)]
  const stepsDir = join(run, 'steps')
  const digests: string[] = []
  for (const s of isDir(stepsDir) ? readdirSync(stepsDir).sort() : []) {
    for (const a of readdirSync(join(stepsDir, s))
      .filter((d) => d.startsWith('attempt-'))
      .sort())
      if (isFile(join(stepsDir, s, a, 'failure.json'))) digests.push(join(stepsDir, s, a, 'failure.json'))
  }
  if (digests.length) {
    const d = load(digests[digests.length - 1]!)
    tried.push(`Last failure: \`${d.stage}\` / \`${d.class}\`: ${(d.message ?? '').slice(0, 300)}`)
  }
  const body = `The ticket pipeline (run \`${runName(run)}\`) stopped at \`${stage}\`: ${reason}\n\nWhat was tried (cost by role):\n${tried.join('\n')}\n\nThe worktree: ${wt ? releaseFailed(wt, run) : 'left for you'}.`
  setStatus(t.ticket_id, 'Ready')
  comment(t.ticket_id, body)
  console.log(`DEAD-END #${t.ticket_id} ${stage}`)
}

function finish(run: string, wt: string): number {
  const t = load(join(run, 'ticket.json'))
  const p = load(join(run, 'project.json'))
  const n: string = t.ticket_id
  const branch = (p.git.branch as string).replaceAll('{n}', n)
  if (branch === 'main' || branch === 'master') die('ERROR finish refuses to push ' + branch)
  // the locks are done with here (no step runs after finish): drop the [T#] tags from the locked test files
  const spec = load(join(run, 'spec', 'spec.json'))
  for (const f of (spec.locked_files ?? []) as string[]) {
    const fp = join(wt, f)
    if (!isFile(fp)) continue
    const text = readFileSync(fp, 'utf8')
    if (untag(text) !== text) writeFileSync(fp, untag(text))
  }
  git(['add', '-A'], { cwd: wt })
  if (git(['diff', '--cached', '--quiet'], { cwd: wt, check: false }).code)
    git(['commit', '-q', '-m', `test: drop the spec case tags from test names for #${n}`], { cwd: wt })
  sh(process.env.TICKET_FORMAT || p.commands.format_fix, wt)
  git(['add', '-A'], { cwd: wt })
  if (git(['diff', '--cached', '--quiet'], { cwd: wt, check: false }).code)
    git(['commit', '-q', '-m', `style: apply spotless for #${n}`], { cwd: wt })
  const rf = join(run, 'final-result.txt')
  const result = isFile(rf) ? readFileSync(rf, 'utf8').trim() : ''
  const title = CONVENTIONAL.test(t.title) ? t.title : `${TYPES[t.kind]}: ${t.title}`
  const bl = join(run, 'final-baseline.json')
  const baseline = isFile(bl)
    ? `\n**Baseline failure:** \`${load(bl).stage}\` also fails on a clean \`origin/main\`, so it was not charged to this change.\n`
    : ''
  const body = prBody(run, wt, n, result, baseline)
  for (const need0 of p.git.pr_body_must_contain as string[]) {
    const need = need0.replaceAll('{n}', n)
    if (!body.includes(need) || (!result && need0 === 'RESULT'))
      die(`ERROR finish: the PR body lacks '${need}' (the final gate left no RESULT line?)`)
  }
  git(['push', '-q', '-u', 'origin', branch], { cwd: wt })
  const bf = join(run, 'pr-body.md')
  writeFileSync(bf, body)
  const url = gh(['pr', 'create', '--draft', '-R', REPO, '--base', 'main', '--head', branch, '--title', title, '--body-file', bf]).trim()
  writeFileSync(join(run, 'pr.txt'), url + '\n')
  console.log(`PR ${url}`)
  const kept = release(wt) // the branch is pushed: the worktree is free for the next ticket (`yk ticket worktree <n>` brings it back for review fixes)
  console.log(kept ? `WORKTREE kept: ${kept}` : `WORKTREE released: ${wt}`)
  return 0
}

export function main(argv: string[]): number {
  const { pos, opt } = parseArgs(argv, { value: ['stage', 'reason', 'worktree'] })
  const [action = '', target = '', status] = pos
  if (action === 'intake') return intake(target)
  if (action === 'claim') {
    setStatus(target, status ?? 'In progress')
    return 0
  }
  if (action === 'plan-comment') {
    comment(
      load(join(target, 'ticket.json')).ticket_id,
      `Plan of the ticket pipeline (run \`${runName(target)}\`)\n\n${specSummary(target)}\n`,
    )
    console.log('COMMENTED plan')
    return 0
  }
  if (action === 'dead-end') {
    deadEnd(target, opt.stage ?? '?', opt.reason ?? '', opt.worktree ? resolve(opt.worktree) : undefined)
    return 0
  }
  if (action === 'finish') return finish(target, resolve(opt.worktree ?? '.'))
  return die(`unknown action ${action}`)
}
