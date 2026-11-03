// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// yk ticket: the commands of the ticket pipeline (Epic #1872). Called by 11-dogfood/src/yk.ts; examples/12-ticket/yk is the same entry for people who stand in
// this folder.
//
//   yk ticket run <issue> [--detach [--notify]]   run the `ticket` workflow on an issue: a tested change on branch issue/<n> in ../yukta-issue-<n>
//   yk ticket status <issue|execution>            the run's tree (which node runs or failed) and its last events
//   yk ticket report [--json]                      first-try pass rate, cost share and cache hit ratio by role, prefix mismatches, BLOCKED rate (ticket-events.jsonl)
//   yk ticket resume|stop <issue|execution>       continue a paused run / end it (a drain, like yk stop --tree)
// Approvals use `yk approve|reject <execution>` as the other gates do. The run dir is .yukta-dev/ticket/<run_id>/ (see README.md).
import { mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname } from 'node:path'
import { DEV_HOME, STATE_DIR, cliJson, die, ensureServer, findCli, git, isFile, join, mkdirp, readJson, renderSession, rmSync, run, text, writeFileSync } from '../../11-dogfood/src/lib.ts'
import { cmdRunControl, cmdTree, startNotifier, watchOwned } from '../../11-dogfood/src/runs.ts'
import { main as report } from './report.ts'
import { KIT } from './lib.ts'

/** The `dogfood` session plus the workflows `ticket` and `step`..., __TICKET__ replaced by the folder of ticketkit (the main checkout the dev server runs when
 * it already has this folder, else this checkout). */
export function renderTicketSession(out: string): void {
  let t = join(DEV_HOME, 'main/examples/12-ticket')
  if (!isFile(join(t, 'session.json'))) t = KIT
  const sub = (v: unknown): unknown => (typeof v === 'string' ? v.split('__TICKET__').join(t) : Array.isArray(v) ? v.map(sub) : v && typeof v === 'object' ? Object.fromEntries(Object.entries(v).map(([k, x]) => [k, sub(x)])) : v)
  const base = mkdtempSync(join(tmpdir(), 'yk-ticket-'))
  try {
    renderSession(join(base, 'base.json'))
    const b = readJson(join(base, 'base.json'))
    const ticket = readJson(join(t, 'session.json'))
    b.workflows = { ...b.workflows, ...(sub(ticket.workflows) as Record<string, unknown>) }
    writeFileSync(out, JSON.stringify(b, null, 2) + '\n')
  } finally { rmSync(base, { recursive: true, force: true }) }
}

/** "<run_id> <execution>" of the latest run of an issue. */
function ticketLast(issue: string): [string, string] {
  const f = join(STATE_DIR, `ticket-last-${issue}`)
  if (!isFile(f)) die(`no ticket run of issue ${issue} started from this checkout`)
  const [runId, execId] = text(f).trim().split(/\s+/)
  return [runId!, execId!]
}

async function ticketRun(argv: string[], ykPath: string): Promise<number> {
  let mode: 'watch' | 'detach' = 'watch'
  let notify = false
  let issue = ''
  for (const a of argv) {
    if (a === '--detach') mode = 'detach'
    else if (a === '--notify') notify = true
    else if (a.startsWith('-')) die(`unknown option '${a}' (yk ticket run <issue> [--detach [--notify]])`)
    else { if (issue) die('one issue at a time'); issue = a }
  }
  if (!/^[0-9]+$/.test(issue)) die('usage: yk ticket run <issue number> [--detach [--notify]]')
  if (notify && mode !== 'detach') die('--notify needs --detach: a run you wait for reports to you anyway')
  const root = git(['rev-parse', '--show-toplevel']).stdout.trim()
  const stamp = new Date().toISOString().replace(/[-:]/g, '').replace(/\.\d+Z$/, 'Z')
  const runId = `${issue}-${stamp}`
  const wtPattern: string = readJson(join(KIT, 'project.json')).git.worktree.replaceAll('{n}', issue)
  const wt = join(run(['sh', '-c', 'cd "$1" && cd "$2" && pwd', '_', root, dirname(wtPattern)]).stdout.trim(), `yukta-issue-${issue}`)
  await ensureServer()
  const cli = findCli()
  mkdirp(STATE_DIR)
  const tmp = mkdtempSync(join(tmpdir(), 'yk-ticket-run-'))
  try {
    renderTicketSession(join(tmp, 'session.json'))
    const a = run([cli, 'session', 'apply', join(tmp, 'session.json')])
    if (a.code) { console.error(a.stdout + a.stderr); die('session apply failed') }
    const e = process.env
    const payload = {
      project: root, issue, runId, stateDir: STATE_DIR, run: `${STATE_DIR}/ticket/${runId}`, worktree: wt, claudeBin: e.YUKTA_CLAUDE_BIN || '-', gateStep: e.TICKET_GATE_STEP || '-', gateFinal: e.TICKET_GATE_FINAL || '-',
      healCmd: e.TICKET_HEAL_CMD || '-', baselineCmd: e.TICKET_BASELINE_CMD || '-', gateWave: e.TICKET_GATE_WAVE || '-', events: e.YUKTA_TICKET_EVENTS || join(DEV_HOME, 'ticket-events.jsonl'),
    }
    const execId = cliJson(['workflow', 'start', 'dogfood', 'ticket', '--payload', JSON.stringify(payload)]).data?.executionId
    if (!execId) die('could not start the ticket workflow')
    writeFileSync(join(STATE_DIR, `ticket-last-${issue}`), `${runId} ${execId}\n`)
    console.log(`STARTED execution=${execId} session=dogfood run=${STATE_DIR}/ticket/${runId} worktree=${wt}`)
    process.env.YUKTA_PROJECT = root
    process.env.YUKTA_SCOPE = `ticket:${issue}`
    if (notify) startNotifier('dogfood', execId, ykPath)
    if (mode === 'detach') return 0
    return await watchOwned('dogfood', execId)
  } finally { rmSync(tmp, { recursive: true, force: true }) }
}

export async function cmdTicket(argv: string[], o: { ykPath: string }): Promise<number> {
  const sub = argv[0] ?? ''
  const rest = argv.slice(1)
  switch (sub) {
    case 'run': return ticketRun(rest, o.ykPath)
    case 'report':
      process.env.YUKTA_TICKET_EVENTS ||= join(DEV_HOME, 'ticket-events.jsonl')
      return report(rest)
    case 'status': case 'resume': case 'stop': {
      const target = rest[0] ?? die(`usage: yk ticket ${sub} <issue|execution>`)
      let execId = target
      let runId = ''
      if (/^[0-9]{1,8}$/.test(target)) [runId, execId] = ticketLast(target)
      if (sub === 'status') {
        await cmdTree(execId)
        if (runId) {
          const ev = join(STATE_DIR, 'ticket', runId, 'events.jsonl')
          for (const l of text(ev).split('\n').filter(Boolean).slice(-5)) {
            try { const e = JSON.parse(l); console.log(JSON.stringify({ role: e.role, model: e.model, step_id: e.step_id, attempt: e.attempt, cost_usd: e.cost_usd, cache_read_tokens: e.cache_read_tokens })) } catch { /* skip */ }
          }
        }
        return 0
      }
      return cmdRunControl(sub, [execId, '--tree'])
    }
    default: return die('usage: yk ticket run <issue> [--detach [--notify]] | report [--json] | status|resume|stop <issue|execution>')
  }
}
