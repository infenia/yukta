// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// yk ticket: the commands of the ticket pipeline (Epic #1872). Called by dogfood/src/yk.ts; examples/dogfood/yk is the same entry for people who stand in
// this folder.
//
//   yk ticket run <issue> [--local] [--detach [--notify]]   run the `ticket` workflow on an issue: a tested change on branch issue/<n> in ../yukta-issue-<n>;
//                                                 --local runs this checkout's ticketkit (session ticket-local) instead of the dev server's main checkout
//   yk ticket new <title> --criterion C [--criterion C..] [--goal G] [SIZING] [--role architect=opus/high..] [--label L..] [--ready] [--dry-run]
//                                                 file a ticket in the shape the pipeline reads (ticket-template.md); --dry-run prints the body and the route
//   yk ticket edit <issue> [--title T] [--goal G] [--criterion C..] [SIZING] [--role r=model/effort|r=auto..] [--label L..] [--ready] [--force] [--dry-run]
//                                                 change what is passed, keep the rest of the body; r=auto (or all=auto) hands a role back to the router
//   SIZING: --file PATH|GLOB.. --change KIND.. --open-decisions N   the facts the router sizes the ticket by (sizing.ts); they can only raise its size and risk
//   yk ticket worktree <issue> [--branch B] [--base REF] [--in DIR]   a worktree or branch for the issue, reusing what exists (branch.ts)
//   yk ticket pr <issue> [--worktree W] [--title T] [--result R]      push the branch and open a DRAFT PR with `Fixes #n` (branch.ts)
//   yk ticket status <issue|execution>            the run's tree (which node runs or failed) and its last events
//   yk ticket supervisor                          install the dogfood supervisor of the ticket questions (supervisor/*.json: internal session, workflow, profile, binding)
//   yk ticket report [--json]                      first-try pass rate, cost share and cache hit ratio by role, prefix mismatches, BLOCKED rate (ticket-events.jsonl)
//   yk ticket resume|stop <issue|execution>       continue a paused run / end it (a drain, like yk stop --tree)
// Approvals use `yk approve|reject <execution>` as the other gates do. The run dir is .yukta-dev/ticket/<run_id>/ (see examples/dogfood/README.md, "Ticket pipeline").
import { mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname } from 'node:path'
import {
  DEV_HOME,
  DEV_URL,
  STATE_DIR,
  cliJson,
  die,
  ensureServer,
  findCli,
  git,
  http,
  isDir,
  isFile,
  join,
  keyHeader,
  mkdirp,
  readJson,
  renderSession,
  resolve,
  rmSync,
  run,
  text,
  writeFileSync,
} from '../lib.ts'
import { cmdRunControl, cmdTree, startNotifier, watchOwned } from '../runs.ts'
import { main as report } from './report.ts'
import { KIT } from './lib.ts'
import { main as github } from './github.ts'
import { pr as prCmd, releaseCmd, worktree as worktreeCmd } from './branch.ts'
import { criteria as criteriaOf } from './init.ts'
import { ROLE_NAMES, parseRole, renderBody, replaceSection, roleLines, rolesOf, routed } from './roles.ts'
import { type Sizing, SECTION, measure, sizingFrom, sizingLines, sizingOf, trackedFiles } from './sizing.ts'

/** The `dogfood` session with the workflows `ticket` and `step`...: renderSession (dogfood) already holds them, so every yk command applies the same set. */
export function renderTicketSession(out: string): void {
  renderSession(out)
}

/** The session of `yk ticket run --local`: only the ticket workflows, from this checkout's ticketkit (not the dev server's main checkout), so a pipeline
 * change runs before it is merged; a session of its own, so no other yk command's apply of `dogfood` swaps the workflows during the run. */
export const LOCAL_SESSION = 'ticket-local'
export function renderLocalTicketSession(out: string): void {
  const sub = (v: unknown): unknown =>
    typeof v === 'string'
      ? v.split('__TICKET__').join(KIT)
      : Array.isArray(v)
        ? v.map(sub)
        : v && typeof v === 'object'
          ? Object.fromEntries(Object.entries(v).map(([k, x]) => [k, sub(x)]))
          : v
  renderSession(out)
  const s = readJson(out)
  s.sessionId = s.name = LOCAL_SESSION
  s.workflows = sub(readJson(join(KIT, 'ticket-session.json')).workflows)
  writeFileSync(out, JSON.stringify(s, null, 2) + '\n')
}

/** "<run_id> <execution>" of the latest run of an issue. */
function ticketLast(issue: string): [string, string] {
  const f = join(STATE_DIR, `ticket-last-${issue}`)
  if (!isFile(f)) die(`no ticket run of issue ${issue} started from this checkout`)
  const [runId, execId] = text(f).trim().split(/\s+/)
  return [runId!, execId!]
}

type TicketArgs = {
  title: string
  goal: string
  criteria: string[]
  labels: string[]
  roles: Record<string, any>
  /** roles handed back to the router (`--role r=auto`) */
  auto: string[]
  /** sizing items (`files: ..`, `change: ..`, `open_decisions: ..`), one per flag */
  sizing: string[]
  ready: boolean
  force: boolean
  dryRun: boolean
  pos: string[]
}

export function ticketArgs(argv: string[]): TicketArgs {
  const t: TicketArgs = {
    title: '',
    goal: '',
    criteria: [],
    labels: [],
    roles: {},
    auto: [],
    sizing: [],
    ready: false,
    force: false,
    dryRun: false,
    pos: [],
  }
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i]!
    const val = (): string => argv[++i] ?? die(`${a} needs a value`)
    const line = (): string => val().replace(/\s+/g, ' ').trim() // one line: a newline could add a heading or list item of its own
    if (a === '--criterion') t.criteria.push(line())
    else if (a === '--title') t.title = line()
    else if (a === '--goal') t.goal = line()
    else if (a === '--label') t.labels.push(val())
    else if (a === '--ready') t.ready = true
    else if (a === '--force') t.force = true
    else if (a === '--dry-run') t.dryRun = true
    else if (a === '--file') t.sizing.push(`files: ${line()}`)
    else if (a === '--change') t.sizing.push(`change: ${line()}`)
    else if (a === '--open-decisions') t.sizing.push(`open_decisions: ${line()}`)
    else if (a === '--role' && /^\s*[a-z_]+\s*=\s*auto\s*$/.test(argv[i + 1] ?? '')) {
      const r = val().split('=')[0]!.trim()
      if (r !== 'all' && !ROLE_NAMES.includes(r)) die(`bad role '${r}=auto': expected <${ROLE_NAMES.join('|')}|all>=auto`)
      t.auto.push(...(r === 'all' ? ROLE_NAMES : [r]))
    } else if (a === '--role') {
      const r = parseRole(val())
      if (typeof r === 'string') die(r)
      t.roles[r[0]] = r[1]
    } else if (a.startsWith('-')) die(`unknown option '${a}'`)
    else t.pos.push(a)
  }
  return t
}

const REPO = (): string => process.env.YUKTA_PICKUP_REPO ?? 'infenia/yukta'

/** The sizing flags as Sizing (an error in one ends the command: the ticket would be routed on less than you meant), null when none was given. */
function flagSizing(t: TicketArgs): Sizing | null {
  if (!t.sizing.length) return null
  const { sizing, errors } = sizingFrom(t.sizing)
  if (errors.length) die(errors.join('\n'))
  return sizing ?? { files: [], change: [], open_decisions: 0 }
}

/** The tree the run will start from, for checking the sizing files (origin/main, as `yk ticket run` uses). */
const treeOf = (root: string, s: Sizing | null): string[] | null => (s ? trackedFiles(root, 'origin/main') : null)

/** What the router makes of the ticket, and the sizing entries that look wrong, on stderr: the drafter sees the effect before filing. */
function routeNote(title: string, criteria: string[], labels: string[], s: Sizing | null, tracked: string[] | null): void {
  const r = routed(criteria, labels, s, tracked, title)
  const sz = r.basis.sizing
  console.error(
    `ROUTE kind=${r.kind} complexity=${r.complexity} risk=${r.risk} tests_mode=${r.tests_mode}` +
      ` static=${r.basis.static.complexity}/${r.basis.static.risk}` +
      (sz ? ` sizing=${sz.complexity}/${sz.risk} (${sz.reasons.join('; ')})` : ' sizing=-'),
  )
  if (s && !tracked) console.error('NOTE cannot list origin/main: the sizing files are counted as written')
  if (s && tracked) {
    const m = measure(s, tracked)
    if (m.unmatched.length) console.error(`NOTE sizing globs match nothing in origin/main: ${m.unmatched.join(', ')}`)
    if (m.new_files.length) console.error(`NOTE not in origin/main, counted as new files: ${m.new_files.join(', ')}`)
  }
}

/** Files an issue from ticket-template.md, assigned to you; --ready also sets Status Ready on the project, so `yk ticket run` can take it. */
function ticketNew(argv: string[]): number {
  const t = ticketArgs(argv)
  const title = t.title || t.pos[0] || ''
  if (!title || !t.criteria.length || t.pos.length > 1)
    die(
      'usage: yk ticket new <title> --criterion <text> [--criterion ..] [--goal <text>] [--file P ..] [--change K ..] [--open-decisions N] [--role architect=opus/high ..] [--label <l> ..] [--ready] [--dry-run]',
    )
  const sizing = flagSizing(t)
  const root = git(['rev-parse', '--show-toplevel']).stdout.trim()
  const tracked = treeOf(root, sizing)
  const body = renderBody(t.goal || title, t.criteria, t.roles, t.labels, sizing, tracked, title)
  routeNote(title, t.criteria, t.labels, sizing, tracked)
  if (t.dryRun) {
    console.log(body)
    return 0
  }
  const dir = mkdtempSync(join(tmpdir(), 'yk-ticket-new-'))
  try {
    const f = join(dir, 'body.md')
    writeFileSync(f, body)
    const r = run([
      'gh',
      'issue',
      'create',
      '-R',
      REPO(),
      '--title',
      title,
      '--body-file',
      f,
      '--assignee',
      '@me',
      ...t.labels.flatMap((l) => ['--label', l]),
    ])
    if (r.code) die(`gh issue create: ${r.stderr.trim().slice(0, 300)}`)
    const url = r.stdout.trim().split('\n').pop()!
    console.log(`ISSUE ${url}`)
    return t.ready ? github(['claim', url.split('/').pop()!, 'Ready']) : 0
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
}

/** Rewrites what is passed in an existing issue and keeps the rest of its body: --criterion replaces the whole list, --role changes only that role (the others stay, missing ones get
 * the defaults), --label adds. Refused once a run has made the issue's branch (the run has read the issue), unless --force. */
function ticketEdit(argv: string[]): number {
  const t = ticketArgs(argv)
  const n = t.pos[0] ?? ''
  if (!/^[0-9]+$/.test(n) || t.pos.length > 1)
    die(
      'usage: yk ticket edit <issue> [--title T] [--goal G] [--criterion C ..] [--file P ..] [--change K ..] [--open-decisions N] [--role r=model/effort|r=auto ..] [--label L ..] [--ready] [--force] [--dry-run]',
    )
  const root = git(['rev-parse', '--show-toplevel']).stdout.trim()
  const branch: string = readJson(join(KIT, 'project.json')).git.branch.replaceAll('{n}', n)
  const worktree = resolve(root, readJson(join(KIT, 'project.json')).git.worktree.replaceAll('{n}', n))
  const ran =
    git(['rev-parse', '--verify', '-q', branch], root).code === 0 ||
    isDir(worktree) ||
    git(['ls-remote', '--exit-code', '--heads', 'origin', branch], root).code === 0
  if (!t.force && ran && !t.dryRun)
    die(`branch ${branch}, its worktree or its remote branch exists: a run has read this issue (--force to edit anyway)`)
  const g = run(['gh', 'issue', 'view', n, '-R', REPO(), '--json', 'title,body,labels'])
  if (g.code) die(`gh issue view: ${g.stderr.trim().slice(0, 300)}`)
  const v = JSON.parse(g.stdout)
  let body: string = v.body ?? ''
  const labels = [...new Set([...v.labels.map((l: { name: string }) => l.name), ...t.labels])]
  const criteria = t.criteria.length ? t.criteria : criteriaOf(body)
  if (t.goal) body = replaceSection(body, /^\s*#+\s+goal\b/i, 'Goal', t.goal)
  if (t.criteria.length)
    body = replaceSection(body, /acceptance criteria/i, 'Acceptance criteria', t.criteria.map((c) => `- [ ] ${c}`).join('\n'))
  // the sizing flags replace what they name (files, change kinds, open decisions) and keep the rest of the section
  let sizing = sizingOf(body).sizing
  const given = flagSizing(t)
  if (given) {
    const keys = new Set(t.sizing.map((x) => x.split(':')[0]))
    const old = sizing ?? { files: [], change: [], open_decisions: 0 }
    sizing = {
      files: keys.has('files') ? given.files : old.files,
      change: keys.has('change') ? given.change : old.change,
      open_decisions: keys.has('open_decisions') ? given.open_decisions : old.open_decisions,
    }
    if (!sizing.files.length && !sizing.change.length) sizing = null
    body = replaceSection(body, SECTION, 'Sizing', sizingLines(sizing))
  }
  const tracked = treeOf(root, sizing)
  const explicit = { ...rolesOf(body), ...t.roles }
  for (const r of t.auto) delete explicit[r]
  const title = t.title || v.title
  body = replaceSection(
    body,
    /pipeline roles/i,
    'Pipeline roles',
    roleLines(explicit, routed(criteria, labels, sizing, tracked, title).models),
  )
  routeNote(title, criteria, labels, sizing, tracked)
  if (t.dryRun) {
    console.log(body)
    return 0
  }
  const dir = mkdtempSync(join(tmpdir(), 'yk-ticket-edit-'))
  try {
    const f = join(dir, 'body.md')
    writeFileSync(f, body)
    const r = run([
      'gh',
      'issue',
      'edit',
      n,
      '-R',
      REPO(),
      '--body-file',
      f,
      ...(t.title ? ['--title', t.title] : []),
      ...t.labels.flatMap((l) => ['--add-label', l]),
    ])
    if (r.code) die(`gh issue edit: ${r.stderr.trim().slice(0, 300)}`)
    console.log(`EDITED #${n}`)
    return t.ready ? github(['claim', n, 'Ready']) : 0
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
}

async function ticketRun(argv: string[], ykPath: string): Promise<number> {
  let mode: 'watch' | 'detach' = 'watch'
  let notify = false
  let local = false
  let issue = ''
  for (const a of argv) {
    if (a === '--detach') mode = 'detach'
    else if (a === '--local') local = true
    else if (a === '--notify') notify = true
    else if (a.startsWith('-')) die(`unknown option '${a}' (yk ticket run <issue> [--local] [--detach [--notify]])`)
    else {
      if (issue) die('one issue at a time')
      issue = a
    }
  }
  if (!/^[0-9]+$/.test(issue)) die('usage: yk ticket run <issue number> [--detach [--notify]]')
  if (notify && mode !== 'detach') die('--notify needs --detach: a run you wait for reports to you anyway')
  const root = git(['rev-parse', '--show-toplevel']).stdout.trim()
  const stamp = new Date()
    .toISOString()
    .replace(/[-:]/g, '')
    .replace(/\.\d+Z$/, 'Z')
  const runId = `${issue}-${stamp}`
  const wtPattern: string = readJson(join(KIT, 'project.json')).git.worktree.replaceAll('{n}', issue)
  const wt = join(run(['sh', '-c', 'cd "$1" && cd "$2" && pwd', '_', root, dirname(wtPattern)]).stdout.trim(), `yukta-issue-${issue}`)
  await ensureServer()
  const cli = findCli()
  mkdirp(STATE_DIR)
  const tmp = mkdtempSync(join(tmpdir(), 'yk-ticket-run-'))
  try {
    const session = local ? LOCAL_SESSION : 'dogfood'
    if (local) renderLocalTicketSession(join(tmp, 'session.json'))
    else renderTicketSession(join(tmp, 'session.json'))
    const a = run([cli, 'session', 'apply', join(tmp, 'session.json')])
    if (a.code) {
      console.error(a.stdout + a.stderr)
      die('session apply failed')
    }
    const e = process.env
    const payload = {
      project: root,
      issue,
      runId,
      stateDir: STATE_DIR,
      run: `${STATE_DIR}/ticket/${runId}`,
      worktree: wt,
      claudeBin: e.YUKTA_CLAUDE_BIN || '-',
      gateStep: e.TICKET_GATE_STEP || '-',
      gateFinal: e.TICKET_GATE_FINAL || '-',
      healCmd: e.TICKET_HEAL_CMD || '-',
      baselineCmd: e.TICKET_BASELINE_CMD || '-',
      gateWave: e.TICKET_GATE_WAVE || '-',
      events: e.YUKTA_TICKET_EVENTS || join(DEV_HOME, 'ticket-events.jsonl'),
    }
    const execId = cliJson(['workflow', 'start', session, 'ticket', '--payload', JSON.stringify(payload)]).data?.executionId
    if (!execId) die('could not start the ticket workflow')
    writeFileSync(join(STATE_DIR, `ticket-last-${issue}`), `${runId} ${execId}\n`)
    console.log(`STARTED execution=${execId} session=${session} run=${STATE_DIR}/ticket/${runId} worktree=${wt}`)
    process.env.YUKTA_PROJECT = root
    process.env.YUKTA_SCOPE = `ticket:${issue}`
    if (notify) startNotifier(session, execId, ykPath)
    if (mode === 'detach') return 0
    return await watchOwned(session, execId)
  } finally {
    rmSync(tmp, { recursive: true, force: true })
  }
}

/** The dogfood supervisor of the ticket questions (supervisor/*.json, #1923): the internal session with its workflow, the supervisor and its attachment, posted to the dev server
 * with the dev key (ADMIN). __TICKET__ and __STATE__ are replaced; an object that exists already (409) is left as it is. */
export async function installSupervisor(base = DEV_URL, dir = join(KIT, 'supervisor'), state = STATE_DIR): Promise<string[]> {
  const sub = (s: string): string => s.split('__TICKET__').join(KIT).split('__STATE__').join(state)
  const steps: [string, string][] = [
    ['session.json', '/api/sessions'],
    ['supervisor.json', '/api/supervisors'],
    ['attachment.json', '/api/supervisors/attachments'],
  ]
  const out: string[] = []
  for (const [file, path] of steps) {
    const r = await http('POST', base + path, {
      headers: { ...keyHeader(), 'Content-Type': 'application/json' },
      body: sub(text(join(dir, file))),
      timeoutMs: 20000,
    })
    if (![200, 201, 409].includes(r.code)) die(`${file}: HTTP ${r.code} ${r.body.slice(0, 200)}`)
    out.push(`${file}: ${r.code === 409 ? 'exists' : 'installed'}`)
  }
  return out
}

export async function cmdTicket(argv: string[], o: { ykPath: string }): Promise<number> {
  const sub = argv[0] ?? ''
  const rest = argv.slice(1)
  switch (sub) {
    case 'new':
      return ticketNew(rest)
    case 'edit':
      return ticketEdit(rest)
    case 'run':
      return ticketRun(rest, o.ykPath)
    case 'worktree':
      return worktreeCmd(rest)
    case 'release':
      return releaseCmd(rest)
    case 'pr':
      return prCmd(rest)
    case 'supervisor':
      await ensureServer()
      console.log((await installSupervisor()).join('\n'))
      return 0
    case 'report':
      process.env.YUKTA_TICKET_EVENTS ||= join(DEV_HOME, 'ticket-events.jsonl')
      return report(rest)
    case 'status':
    case 'resume':
    case 'stop': {
      const target = rest[0] ?? die(`usage: yk ticket ${sub} <issue|execution>`)
      let execId = target
      let runId = ''
      if (/^[0-9]{1,8}$/.test(target)) [runId, execId] = ticketLast(target)
      if (sub === 'status') {
        await cmdTree(execId)
        if (runId) {
          const ev = join(STATE_DIR, 'ticket', runId, 'events.jsonl')
          for (const l of text(ev).split('\n').filter(Boolean).slice(-5)) {
            try {
              const e = JSON.parse(l)
              console.log(
                JSON.stringify({
                  role: e.role,
                  model: e.model,
                  step_id: e.step_id,
                  attempt: e.attempt,
                  cost_usd: e.cost_usd,
                  cache_read_tokens: e.cache_read_tokens,
                }),
              )
            } catch {
              /* skip */
            }
          }
        }
        return 0
      }
      return cmdRunControl(sub, [execId, '--tree'])
    }
    default:
      return die(
        'usage: yk ticket new <title> --criterion C.. | edit <issue> [--criterion C..] | run <issue> [--detach [--notify]] | worktree <issue> [--branch B] [--in DIR] | pr <issue> [--title T] | release <issue> | supervisor | report [--json] | status|resume|stop <issue|execution>',
      )
  }
}
