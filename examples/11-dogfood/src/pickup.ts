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
// (headless, examples/12-ticket) -> "PICKUP handed=#<n> to=ticket", at most one run at a time (YUKTA_PICKUP_YK: another yk, for tests).
import { DEV_HOME, HERE, type Json, alive, http, isFile, join, onPath, readFileSync, readdirSync, rmSync, run, text, tryJson } from './lib.ts'

const REPO = process.env.YUKTA_PICKUP_REPO ?? 'infenia/yukta'
const PROJECT = process.env.YUKTA_PICKUP_PROJECT ?? '5'
const OWNER = process.env.YUKTA_PICKUP_OWNER ?? 'infenia'
const ONLY = process.env.YUKTA_PICKUP_ONLY ?? ''
const MODE = process.env.YUKTA_PICKUP_MODE || text(join(DEV_HOME, 'pickup-mode')).trim() || 'session'
const YK = process.env.YUKTA_PICKUP_YK || join(HERE, 'yk')

class Stop extends Error {}
const fail = (msg: string): never => { throw Object.assign(new Stop(msg), { code: 1 }) }
const skip = (why: string): never => { throw Object.assign(new Stop(`PICKUP skipped=${why}`), { code: 0, out: true }) }

type Idle = { name: string; port: number }

/** idle sessions, oldest first, and the busy names; deletes the entries of dead channels */
async function sessions(): Promise<{ idle: Idle[]; busy: string[]; registered: number }> {
  const dir = join(DEV_HOME, 'pickup')
  const files = (() => { try { return readdirSync(dir).filter(f => f.endsWith('.json')).sort().map(f => join(dir, f)) } catch { return [] } })()
  const rows = files.map(f => ({ f, started: Number(tryJson(text(f))?.started ?? 0) || 0 })).sort((a, b) => a.started - b.started)
  const idle: Idle[] = []
  const busy: string[] = []
  let n = 0
  for (const { f } of rows) {
    const j = tryJson(text(f))
    if (!j) { rmSync(f, { force: true }); continue }
    const { name, port, pid } = j as { name: string; port: number; pid: number }
    if (!(Number.isInteger(pid) && Number.isInteger(port) && alive(pid))) { rmSync(f, { force: true }); continue }
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

export async function tick(): Promise<number> {
  let idle: Idle[] = []
  let registered = 0
  let busy: string[] = []
  if (MODE === 'ticket') {
    // one headless run at a time: a run of this checkout that is still going holds the schedule
    if (run(['pgrep', '-f', '[w]orkflow start dogfood ticket']).code === 0) skip('a ticket run is going')
    idle = [{ name: 'ticket', port: 0 }]
    registered = 1
  } else {
    ;({ idle, busy, registered } = await sessions())
    if (registered === 0) skip(`no channel${ONLY ? ` named ${ONLY}` : ''} (open one with: yk pickup session)`)
    if (!idle.length) skip(`all busy (${busy.join(',')})`)
  }
  const mine = tryJson(gh(['issue', 'list', '-R', REPO, '--assignee', '@me', '--state', 'open', '--limit', '200', '--json', 'number,title,url'], 'gh issue list'))
  const ready = tryJson(gh(['project', 'item-list', PROJECT, '--owner', OWNER, '--limit', '5000', '--format', 'json'], 'gh project item-list'))
  const openPrs = tryJson(gh(['pr', 'list', '-R', REPO, '--state', 'open', '--limit', '200', '--json', 'headRefName,title,body'], 'gh pr list'))
  if (mine === undefined || ready === undefined || openPrs === undefined) fail('gh answered something that is not JSON')
  if (MODE !== 'ticket' && !(isFile(join(DEV_HOME, 'pickup-token')) && readFileSync(join(DEV_HOME, 'pickup-token')).length)) fail(`no ${DEV_HOME}/pickup-token (yk pickup session creates it)`)

  // assigned AND Ready, highest Priority first (P0 before P3; no priority last), then the lowest number
  const pri = new Map<string, string>()
  for (const i of (ready.items ?? []) as Json[]) if (i.status === 'Ready') pri.set(String(i.content?.number), i.priority ?? 'P9')
  const cands = (mine as Json[]).filter(i => pri.has(String(i.number))).map(i => ({ ...i, p: pri.get(String(i.number))! }))
    .sort((a, b) => (a.p < b.p ? -1 : a.p > b.p ? 1 : a.number - b.number))
  let handed = 0
  for (const { number: n, title, url } of cands) {
    if (!idle.length) break
    // an open PR that names the issue, or its branch on the remote: someone is on it
    const re = new RegExp(`(fix(es|ed)?|close[sd]?|resolve[sd]?|refs?) #${n}\\b`, 'i')
    if ((openPrs as Json[]).some(p => p.headRefName === `issue/${n}` || re.test(`${p.title} ${p.body ?? ''}`))) continue
    const ref = tryJson(gh(['api', `repos/${REPO}/git/matching-refs/heads/issue/${n}`], 'gh api matching-refs'))
    if (Array.isArray(ref) && ref.length > 0) continue
    if (MODE === 'ticket') {
      if (run([YK, 'ticket', 'run', String(n), '--detach']).code) fail(`yk ticket run ${n} failed`)
      console.log(`PICKUP handed=#${n} to=ticket`)
      handed++
      break
    }
    const body = JSON.stringify({ content: `Pick up issue #${n}: ${title}\n${url}`, meta: { issue: String(n), url, title } })
    // the first idle session that takes it; one that answers busy meanwhile is out for this round
    while (idle.length) {
      const { name, port } = idle.shift()!
      const r = await http('POST', `http://127.0.0.1:${port}/v1/events`, { headers: { Authorization: `Bearer ${text(join(DEV_HOME, 'pickup-token'))}` }, body, timeoutMs: 10000 })
      if (r.code === 0) fail(`POST to ${name} (:${port}) failed`)
      if (r.code === 202) { console.log(`PICKUP handed=#${n} to=${name}`); handed++; break }
      if (r.code === 409) busy.push(name)
      else fail(`POST to ${name} (:${port}) answered ${r.code}`)
    }
  }
  if (!handed) skip('nothing assigned to you is Ready, or each such issue has a PR or an issue/<n> branch')
  return 0
}

export async function main(argv: string[]): Promise<number> {
  if (argv[0] !== 'tick') { console.error('usage: pickup tick'); return 2 }
  if (!onPath('gh')) { console.error('PICKUP error: gh is required'); return 1 }
  try { return await tick() } catch (e) {
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
