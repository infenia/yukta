// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// yk report scan [--since 24h] [--stuck 6h] [--hook]
// Records the failures printResult never saw: every ended execution of the dev server (any workflow, any initiator, detached or not) that failed or
// ended with a failing outcome, a RUNNING one older than --stuck, and new ERROR lines of server.log. Safe to run again and again: executions already
// recorded or found clean are skipped (sweep.json next to failures.jsonl).
import { recordFailure, reportOff, unfiled } from './failures.ts'
import {
  DEV_HOME,
  type Json,
  dirname,
  executions,
  failedOutput,
  isFile,
  join,
  mkdirp,
  nodeStage,
  parseArgs,
  readFileSync,
  secs,
  statSync,
  tryJson,
  writeFileSync,
} from './lib.ts'
import { progressOf } from './runs.ts'

const stateFile = (): string => join(dirname(process.env.YUKTA_FAILURES ?? join(DEV_HOME, 'failures.jsonl')), 'sweep.json')
const ENDED_BAD = /^(FAILED|FAILURE|TIMED_OUT|ERROR)$/
const LIVE = /^(RUNNING|PENDING|QUEUED|PAUSED)$/
const lines = (s: string): string[] => (s ? s.replace(/\n$/, '').split('\n') : [])

/** "6h", "90m", "2d" to seconds. */
export function span(s: string): number {
  const m = /^(\d+)([mhd])$/.exec(s)
  return m ? Number(m[1]) * { m: 60, h: 3600, d: 86400 }[m[2] as 'm' | 'h' | 'd'] : 86400
}

/** The ERROR lines of a log text, each with the 3 lines after it. */
export function logErrors(t: string): string[] {
  const ls = t.split('\n')
  const out: string[] = []
  ls.forEach((l, i) => {
    if (/\bERROR\b/.test(l)) out.push(ls.slice(i, i + 4).join('\n'))
  })
  return out
}

export async function sweep(argv: string[]): Promise<number> {
  const { opt, flag } = parseArgs(argv, { value: ['since', 'stuck'], bool: ['hook'] }, 'yk report scan [--since 24h] [--stuck 6h]')
  if (reportOff()) return flag.has('hook') ? 0 : (console.log('failure reporting is off (yk report on)'), 0)
  const since = Date.now() / 1000 - span(opt.since ?? '24h')
  const stuck = Date.now() / 1000 - span(opt.stuck ?? '6h')
  const ex = await executions()
  if (!ex) {
    console.error('the dev server cannot be read (yk up starts it)')
    return 1
  }
  const st: Json = tryJson(isFile(stateFile()) ? readFileSync(stateFile(), 'utf8') : '') ?? {}
  const seen: Record<string, number> = st.seen ?? {}
  const known = new Set(
    isFile(process.env.YUKTA_FAILURES ?? join(DEV_HOME, 'failures.jsonl'))
      ? readFileSync(process.env.YUKTA_FAILURES ?? join(DEV_HOME, 'failures.jsonl'), 'utf8')
          .split('\n')
          .map((l) => tryJson(l)?.execution)
          .filter(Boolean)
      : [],
  )
  let found = 0
  let budget = 40 // executions judged per scan: the Stop hook has 30 s, and the rest follow on the next scan
  for (const e of ex) {
    if (budget <= 0) break
    const id: string = e.executionId
    if (known.has(id) || seen[id] || e.workflowId === 'pickup') continue
    const ended = e.endTime && !String(e.endTime).startsWith('0001') ? secs(e.endTime) : 0
    const status = String(e.status)
    if (!ended) {
      if (status === 'RUNNING' && e.startTime && secs(e.startTime) < stuck && !seen[`${id}:stuck`]) {
        recordFailure({
          kind: 'result',
          where: e.workflowId,
          status: 'STUCK',
          execution: id,
          session: e.sessionId,
          evidence: `workflow ${e.workflowId} still RUNNING since ${e.startTime}`,
        })
        seen[`${id}:stuck`] = 1
        found++
      }
      continue
    }
    if (ended < since) continue
    budget--
    let bad = ENDED_BAD.test(status)
    let where: string = e.workflowId
    let detail = ''
    if (!LIVE.test(status) && (bad || status === 'SUCCESS' || status === 'COMPLETED')) {
      // routeByOutcome workflows end SUCCESS with a failing outcome terminal; the tasks say which node
      try {
        const p = await progressOf(e.sessionId, id)
        const tasks: Json[] = p.tasks ?? []
        const hit = tasks.find((t) => t.status === 'FAILURE' || t.status === 'TIMED_OUT')
        const out = tasks.find(
          (t) => t.module === 'OUTCOME_TERMINAL' && t.metadata?.reached === true && t.metadata?.outcome && t.metadata.outcome !== 'SUCCESS',
        )
        if (hit || out) {
          bad = true
          where = nodeStage((hit ?? out)!.nodeId)
            .replace(/^failed$/, 'tool')
            .replace(/\.failed$/, '')
          detail = `${(hit ?? out)!.nodeId}: ${hit?.status ?? out!.metadata.outcome}\n`
        }
      } catch {
        /* progress unreadable: judge by the status alone */
      }
    }
    if (!bad) {
      seen[id] = ended
      continue
    }
    const output = lines(failedOutput(e.sessionId, id)).slice(-40).join('\n')
    recordFailure({
      kind: 'result',
      where,
      status,
      execution: id,
      session: e.sessionId,
      evidence: `workflow ${e.workflowId}\n${detail}${output}`,
    })
    seen[id] = ended
    found++
  }
  // forget the executions that left the window, so the file stays small
  for (const k of Object.keys(seen)) if (seen[k]! < since && !k.includes(':')) delete seen[k]
  // server.log: the ERROR lines since the last scan
  const log = join(DEV_HOME, 'server.log')
  if (isFile(log)) {
    const size = statSync(log).size
    let off: number = st.logOffset ?? size // first scan: from now on, not the whole history
    if (off > size) off = 0
    const chunk = Buffer.from(readFileSync(log)).subarray(off).toString('utf8')
    for (const block of logErrors(chunk).slice(0, 20)) {
      recordFailure({ kind: 'crash', where: 'server', evidence: block })
      found++
    }
    st.logOffset = size
  }
  mkdirp(dirname(stateFile()))
  writeFileSync(stateFile(), JSON.stringify({ ...st, seen }) + '\n')
  const n = unfiled()
  if (flag.has('hook')) {
    if (n) console.log(`${n} unfiled dogfood failure(s) (${found} new): yk report, then yk report file`)
  } else console.log(`scan: ${found} new failure(s) recorded, ${n} unfiled (yk report lists them)`)
  return 0
}
