// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// pickup tick: one round of the scheduled pickup (workflow `pickup`, `yk pickup now`). Hands issues to the yukta-pickup channels
// (channel/src/main.ts, pickup profile) of the open `yk pickup session`s, one issue per idle session:
//   1. sessions: the registry $DEV_HOME/pickup/*.json (a dead pid is dropped); each must answer GET /v1/status and be idle
//                                                           -> else "PICKUP skipped=<why>"
//   2. issues: assigned to the user AND Ready on the project, no open PR naming it, no issue/<n> branch, top Priority first (P0 first)
//   3. POST /v1/events with the token to the first idle session  -> "PICKUP handed=#<n> to=<name>" (one line per issue handed)
// Exit 0 for every skip; non-zero only for a real error (gh not signed in, an answer that is not JSON, a refused POST).
// Env: YUKTA_PICKUP_ONLY (hand only to the session of that name), YUKTA_PICKUP_REPO (infenia/yukta), YUKTA_PICKUP_PROJECT (5),
// YUKTA_PICKUP_OWNER (infenia), YUKTA_DEV_HOME.
// YUKTA_PICKUP_MODE=ticket (or the word `ticket` in $DEV_HOME/pickup-mode): no interactive session; the top issue starts `yk ticket run <n> --detach`
// (headless, examples/dogfood) -> "PICKUP handed=#<n> to=ticket", at most one run at a time (YUKTA_PICKUP_YK: another yk, for tests).
// Filter (YUKTA_PICKUP_ISSUES, `yk pickup now --issue 12,34`; else the file $DEV_HOME/pickup-issues, one number per line): only those issues are handed out.
// `pickup list` prints the candidates the next tick would consider, with [free] or [taken: ...].
import {
  DEV_HOME,
  HERE,
  type Json,
  alive,
  http,
  isFile,
  join,
  onPath,
  readFileSync,
  readdirSync,
  rmSync,
  run,
  text,
  tryJson,
} from './lib.ts'

const REPO = process.env.YUKTA_PICKUP_REPO ?? 'infenia/yukta'
const PROJECT = process.env.YUKTA_PICKUP_PROJECT ?? '5'
const OWNER = process.env.YUKTA_PICKUP_OWNER ?? 'infenia'
const ONLY = process.env.YUKTA_PICKUP_ONLY ?? ''
const MODE = process.env.YUKTA_PICKUP_MODE || text(join(DEV_HOME, 'pickup-mode')).trim() || 'session'
// The session is an orchestrator: by default (meta.via = ticket) it runs the ticket pipeline (`yk ticket run`) and answers its questions, and never
// implements the issue itself (the pickup skill, #1922). YUKTA_PICKUP_VIA=direct (or `direct` in $DEV_HOME/pickup-via) opts back into implementing.
const VIA = process.env.YUKTA_PICKUP_VIA || text(join(DEV_HOME, 'pickup-via')).trim() || 'ticket'
const YK = process.env.YUKTA_PICKUP_YK || join(HERE, 'yk')

class Stop extends Error {}
const fail = (msg: string): never => {
  throw Object.assign(new Stop(msg), { code: 1 })
}
const skip = (why: string): never => {
  throw Object.assign(new Stop(`PICKUP skipped=${why}`), { code: 0, out: true })
}

type Idle = { name: string; port: number }

/** idle sessions, oldest first, and the busy names; deletes the entries of dead channels */
async function sessions(): Promise<{ idle: Idle[]; busy: string[]; registered: number }> {
  const dir = join(DEV_HOME, 'pickup')
  const files = (() => {
    try {
      return readdirSync(dir)
        .filter((f) => f.endsWith('.json'))
        .sort()
        .map((f) => join(dir, f))
    } catch {
      return []
    }
  })()
  const rows = files.map((f) => ({ f, started: Number(tryJson(text(f))?.started ?? 0) || 0 })).sort((a, b) => a.started - b.started)
  const idle: Idle[] = []
  const busy: string[] = []
  let n = 0
  for (const { f } of rows) {
    const j = tryJson(text(f))
    if (!j) {
      rmSync(f, { force: true })
      continue
    }
    const { name, port, pid } = j as { name: string; port: number; pid: number }
    if (!(Number.isInteger(pid) && Number.isInteger(port) && alive(pid))) {
      rmSync(f, { force: true })
      continue
    }
    if (ONLY && name !== ONLY) continue
    n++
    const st = await http('GET', `http://127.0.0.1:${port}/v1/status`, { timeoutMs: 5000 })
    if (st.code === 200 && tryJson(st.body)?.busy === false) idle.push({ name, port })
    else busy.push(name)
  }
  return { idle, busy, registered: n }
}

function gh(args: string[], what: string): string {
  const r = run(['gh', ...args])
  return r.code ? fail(`${what}`) : r.stdout
}

/** The channel event for an issue: meta.issue is what the input bridge matches to find the session. */
export function eventFor(n: number | string, title: string, url: string, via = VIA): { content: string; meta: Record<string, string> } {
  const ticket = via === 'ticket'
  return {
    content: ticket
      ? `Issue #${n}: ${title}\n${url}\n` +
        `You are the orchestrator of this issue, not its implementer. Invoke the pickup skill (Skill tool, "pickup", ticket pipeline mode) and follow it: ` +
        `run \`yk ticket run ${n} --detach --notify\`, answer or relay the pipeline's questions, and report with pickup_done. ` +
        `Do NOT read the code to fix it or edit files yourself. Git steps have commands you call when the pipeline needs them: \`yk ticket worktree ${n} [--in DIR]\` (worktree or branch, reuses what exists) and \`yk ticket pr ${n}\` (push and draft PR).`
      : `Pick up issue #${n}: ${title}\n${url}`,
    meta: { issue: String(n), url, title, ...(ticket ? { via: 'ticket' } : {}) },
  }
}

/** The issue numbers this round may hand out: `--issue` (YUKTA_PICKUP_ISSUES) or $DEV_HOME/pickup-issues; null = no filter.
 *  Numbers anywhere in the text count ("12, 34", "#12", "012", one per line); lines starting with `//` are comments.
 *  A filter with text but no number selects nothing (a typo must not lift the filter); a file with only comments is no filter.
 *  A missing file is no filter; any other read error stops the round. */
export function allowlist(raw = process.env.YUKTA_PICKUP_ISSUES || filterFile(join(DEV_HOME, 'pickup-issues'))): Set<string> | null {
  const body = raw
    .split('\n')
    .filter((l) => !l.trim().startsWith('//'))
    .join(' ')
    .trim()
  if (!body) return null
  const nums = body.match(/\d+/g) ?? []
  return new Set(nums.map((n) => String(Number(n))))
}

/** the file's text; '' only when the file does not exist */
function filterFile(p: string): string {
  try {
    return readFileSync(p, 'utf8')
  } catch (e) {
    if ((e as { code?: string }).code === 'ENOENT') return ''
    return fail(`cannot read ${p}: ${(e as Error).message}`)
  }
}

/** Assigned to you AND Ready, highest Priority first (P0 before P3; no priority last), then the lowest number; the allowlist applies. */
function load(): { cands: Json[]; openPrs: Json[] } {
  const mine = tryJson(
    gh(
      ['issue', 'list', '-R', REPO, '--assignee', '@me', '--state', 'open', '--limit', '200', '--json', 'number,title,url'],
      'gh issue list',
    ),
  )
  const ready = tryJson(
    gh(['project', 'item-list', PROJECT, '--owner', OWNER, '--limit', '5000', '--format', 'json'], 'gh project item-list'),
  )
  const openPrs = tryJson(
    gh(['pr', 'list', '-R', REPO, '--state', 'open', '--limit', '200', '--json', 'headRefName,title,body'], 'gh pr list'),
  )
  if (mine === undefined || ready === undefined || openPrs === undefined) fail('gh answered something that is not JSON')
  const pri = new Map<string, string>()
  for (const i of (ready.items ?? []) as Json[]) if (i.status === 'Ready') pri.set(String(i.content?.number), i.priority ?? 'P9')
  const only = allowlist()
  const cands = (mine as Json[])
    .filter((i) => pri.has(String(i.number)) && (!only || only.has(String(i.number))))
    .map((i) => ({ ...i, p: pri.get(String(i.number))! }))
    .sort((a, b) => (a.p < b.p ? -1 : a.p > b.p ? 1 : a.number - b.number))
  return { cands, openPrs: openPrs as Json[] }
}

/** someone is on it: an open PR that names the issue, or its branch on the remote */
function taken(n: number | string, openPrs: Json[]): string | null {
  const re = new RegExp(`(fix(es|ed)?|close[sd]?|resolve[sd]?|refs?) #${n}\\b`, 'i')
  if (openPrs.some((p) => p.headRefName === `issue/${n}` || re.test(`${p.title} ${p.body ?? ''}`))) return 'open PR'
  const ref = tryJson(gh(['api', `repos/${REPO}/git/matching-refs/heads/issue/${n}`], 'gh api matching-refs'))
  if (Array.isArray(ref) && ref.length > 0) return `branch issue/${n}`
  return null
}

/** `pickup list`: what the next tick would take, and the filter in force */
export function list(): number {
  const { cands, openPrs } = load()
  const only = allowlist()
  console.log(`filter: ${only ? [...only].map((n) => `#${n}`).join(' ') : 'none (every assigned, Ready issue)'}`)
  if (!cands.length) console.log('no candidates: nothing assigned to you is Ready' + (only ? ' among the filtered issues' : ''))
  for (const c of cands) {
    const t = taken(c.number, openPrs)
    console.log(`#${c.number} ${c.p} ${c.title}  [${t ? `taken: ${t}` : 'free'}]`)
  }
  return 0
}

export async function tick(): Promise<number> {
  let idle: Idle[] = []
  let busy: string[] = []
  if (MODE === 'ticket') {
    // one headless run at a time: a run of this checkout that is still going holds the schedule
    if (run(['pgrep', '-f', '[w]orkflow start dogfood ticket']).code === 0) skip('a ticket run is going')
    idle = [{ name: 'ticket', port: 0 }]
  } else {
    const found = await sessions()
    ;({ idle, busy } = found)
    if (found.registered === 0) skip(`no channel${ONLY ? ` named ${ONLY}` : ''} (open one with: yk pickup session)`)
    if (!idle.length) skip(`all busy (${busy.join(',')})`)
  }
  const { cands, openPrs } = load()
  if (MODE !== 'ticket' && !(isFile(join(DEV_HOME, 'pickup-token')) && readFileSync(join(DEV_HOME, 'pickup-token')).length))
    fail(`no ${DEV_HOME}/pickup-token (yk pickup session creates it)`)

  let handed = 0
  for (const { number: n, title, url } of cands) {
    if (!idle.length) break
    if (taken(n, openPrs)) continue
    if (MODE === 'ticket') {
      if (run([YK, 'ticket', 'run', String(n), '--detach']).code) fail(`yk ticket run ${n} failed`)
      console.log(`PICKUP handed=#${n} to=ticket`)
      handed++
      break
    }
    const body = JSON.stringify(eventFor(n, title, url))
    // the first idle session that takes it; one that answers busy meanwhile is out for this round
    while (idle.length) {
      const { name, port } = idle.shift()!
      const r = await http('POST', `http://127.0.0.1:${port}/v1/events`, {
        headers: { Authorization: `Bearer ${text(join(DEV_HOME, 'pickup-token'))}` },
        body,
        timeoutMs: 10000,
      })
      if (r.code === 0) fail(`POST to ${name} (:${port}) failed`)
      if (r.code === 202) {
        console.log(`PICKUP handed=#${n} to=${name}`)
        handed++
        break
      }
      if (r.code === 409) busy.push(name)
      else fail(`POST to ${name} (:${port}) answered ${r.code}`)
    }
  }
  if (!handed)
    skip(
      `nothing ${allowlist() ? 'of the filtered issues ' : ''}assigned to you is Ready, or each such issue has a PR or an issue/<n> branch`,
    )
  return 0
}

export async function main(argv: string[]): Promise<number> {
  if (argv[0] !== 'tick' && argv[0] !== 'list') {
    console.error('usage: pickup tick|list')
    return 2
  }
  if (!onPath('gh')) {
    console.error('PICKUP error: gh is required')
    return 1
  }
  try {
    return argv[0] === 'list' ? list() : await tick()
  } catch (e) {
    if (e instanceof Stop) {
      const x = e as Stop & { code: number; out?: boolean }
      if (x.out) console.log(x.message)
      else console.error(`PICKUP error: ${x.message}`)
      return x.code
    }
    throw e
  }
}

if (import.meta.main) process.exit(await main(process.argv.slice(2)))
