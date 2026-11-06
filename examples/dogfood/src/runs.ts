// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// The runs of the dev server as `yk` shows and steers them: the tree of runs, control and approvals, watching a run until it ends, and its RESULT line.
import { type Subprocess, spawn } from 'bun'
import {
  DEV_HOME,
  DEV_URL,
  HERE,
  type Json,
  REPO_ROOT,
  STATE_DIR,
  appendFileSync,
  changesSum,
  cliJson,
  die,
  emitResult,
  failedOutput,
  findCli,
  healRecords,
  healedStages,
  http,
  isFile,
  join,
  keyHeader,
  mkdirp,
  nodeStage,
  notifySession,
  notifyText,
  readFileSync,
  recordRun,
  run,
  runWall,
  secs,
  shq,
  sleep,
  text,
  tryJson,
  writeFileSync,
} from './lib.ts'
import { recordFailure } from './failures.ts'
import { mkdtempSync, openSync, closeSync, readdirSync, rmSync, statSync } from 'node:fs'
import { tmpdir } from 'node:os'

const RUN_STATES = /RUNNING|QUEUED|PAUSED/
const lines = (s: string): string[] => (s.length ? s.replace(/\n$/, '').split('\n') : [])

// ---- steering: the tree of runs, control and approvals

/** A call to the server with the key; the unwrapped data, or dies with the server's message. */
export async function api(method: string, path: string, o: { notFoundOk?: boolean } = {}): Promise<Json> {
  const r = await http(method, `${process.env.YUKTA_SERVER_URL ?? DEV_URL}${path}`, {
    headers: process.env.YUKTA_TOKEN ? { 'X-API-Key': process.env.YUKTA_TOKEN } : {},
    timeoutMs: 30000,
  })
  if (r.code === 0) die(`${method} ${path}: the server is not reachable (yk up)`)
  if (r.code === 404 && o.notFoundOk) return null
  if (r.code >= 400) die(`${method} ${path}: HTTP ${r.code} ${tryJson(r.body)?.message ?? ''}`.trimEnd())
  const j = tryJson(r.body)
  return j && typeof j === 'object' && 'data' in j ? j.data : j
}

export const uri = (v: string): string => encodeURIComponent(v)

/** A full execution id, or the unique prefix of one (the tree prints eight characters). */
export async function resolveExec(id: string | undefined): Promise<string> {
  if (!id) die('an execution id is required')
  if (id.length >= 32) return id
  const all: Json[] = await api('GET', '/api/executions?limit=1000')
  const found = [...new Set(all.filter((e) => String(e.executionId).startsWith(id)).map((e) => e.executionId as string))]
  return found.length === 1 ? found[0]! : die(`no single execution starts with '${id}'`)
}

export async function sessionOf(exec: string): Promise<string> {
  const all: Json[] = await api('GET', '/api/executions?limit=1000')
  return all.find((e) => e.executionId === exec)?.sessionId ?? ''
}

/** The nodes that wait for a signal in these sessions. */
export async function pending(sessions: string[]): Promise<Json[]> {
  const all: Json[] = []
  // the server of #1868 has no approvals endpoint any more: nothing waits for a signal there
  for (const s of sessions) all.push(...((await api('GET', `/api/sessions/${s}/approvals`, { notFoundOk: true })) ?? []))
  return all
}

export async function cmdTree(arg: string | undefined): Promise<number> {
  const root = await resolveExec(arg ?? die('usage: yk tree <execution>'))
  const runs: Json[] = await api('GET', `/api/executions/${root}/tree`)
  const all: Json[] = await api('GET', '/api/executions?limit=1000')
  const sessions = [
    ...new Set(all.filter((e) => runs.some((r) => r.executionId === e.executionId)).map((e) => e.sessionId as string)),
  ].sort()
  const cur = new Map<string, { nodes: string; paused: boolean }>()
  for (const r of runs.filter((r) => RUN_STATES.test(r.status))) {
    // the status stays RUNNING while paused; the control state says PAUSED
    const d = await api('GET', `/api/executions/${r.executionId}`)
    cur.set(r.executionId, {
      nodes: (d.tasks as Json[])
        .filter((t) => RUN_STATES.test(t.status))
        .map((t) => t.nodeId)
        .join(','),
      paused: d.controlState === 'PAUSED',
    })
  }
  const appr = await pending(sessions)
  const by = new Map(runs.map((r) => [r.executionId as string, r]))
  const depth = (r: Json): number =>
    r.parentExecutionId == null || !by.get(r.parentExecutionId) ? 0 : 1 + depth(by.get(r.parentExecutionId)!)
  for (const r of runs) {
    const c = cur.get(r.executionId)
    console.log(
      `${String(r.executionId).slice(0, 8)} ${'  '.repeat(depth(r))}${r.workflowId} ${r.status}${c?.paused ? ' paused' : ''}${c?.nodes ? ` [${c.nodes}]` : ''}${appr
        .filter((a) => a.executionId === r.executionId)
        .map((a) => ` awaiting ${a.name}@${a.nodeId}`)
        .join('')}`,
    )
  }
  return 0
}

/** yk pause|resume|stop EXEC [--tree] [--now]   (--now: stop ends the run at once, mode KILL; without it the stop drains, up to 30 s) */
export async function cmdRunControl(verb: string, argv: string[]): Promise<number> {
  const exec = await resolveExec(argv[0] ?? die(`usage: yk ${verb} <execution> [--tree]`))
  let tree = false
  let mode = ''
  for (const a of argv.slice(1)) {
    if (a === '--tree') tree = true
    else if (a === '--now') {
      if (verb !== 'stop') die('--now is for yk stop')
      mode = 'KILL'
    } else die(`unknown option '${a}' (yk ${verb} <execution> [--tree]${verb === 'stop' ? ' [--now]' : ''})`)
  }
  await api('POST', `/api/executions/${exec}/${verb}?tree=${tree}${mode ? `&mode=${mode}` : ''}`)
  console.log(`${verb}: ${exec}${tree ? ' and the runs below it' : ''}`)
  return 0
}

/** yk skip|unskip|retry EXEC NODE */
export async function cmdNodeControl(verb: string, argv: string[]): Promise<number> {
  const exec = await resolveExec(argv[0] ?? die(`usage: yk ${verb} <execution> <node>`))
  const node = argv[1] ?? die(`usage: yk ${verb} <execution> <node>`)
  if (verb === 'skip') await api('POST', `/api/executions/${exec}/nodes/${node}/skip?skip=true`)
  else if (verb === 'unskip') await api('POST', `/api/executions/${exec}/nodes/${node}/skip?skip=false`)
  else await api('POST', `/api/executions/${exec}/nodes/${node}/retry`)
  console.log(`${verb}: ${exec} ${node}`)
  return 0
}

/** yk break EXEC NODE --field F --equals V | --clear */
export async function cmdBreak(argv: string[]): Promise<number> {
  const usage = 'usage: yk break <execution> <node> --field F --equals V | --clear'
  const exec = await resolveExec(argv[0] ?? die(usage))
  const node = argv[1] ?? die(usage)
  let field = '',
    equals = '',
    clear = false
  for (let i = 2; i < argv.length; i++) {
    const a = argv[i]
    if (a === '--field') field = argv[++i] ?? die('--field needs a path')
    else if (a === '--equals') equals = argv[++i] ?? die('--equals needs a value')
    else if (a === '--clear') clear = true
    else die(`unknown option '${a}'`)
  }
  if (clear) {
    await api('DELETE', `/api/executions/${exec}/nodes/${node}/breakpoint`)
    console.log(`breakpoint cleared: ${exec} ${node}`)
  } else {
    if (!field || !equals) die('yk break needs --field and --equals (or --clear)')
    await api('POST', `/api/executions/${exec}/nodes/${node}/breakpoint?field=${uri(field)}&equals=${uri(equals)}`)
    console.log(`breakpoint set: ${exec} ${node} when ${field} == ${equals}`)
  }
  return 0
}

/** yk step EXEC NODE on|off|next */
export async function cmdStep(argv: string[]): Promise<number> {
  const usage = 'usage: yk step <execution> <node> on|off|next'
  const exec = await resolveExec(argv[0] ?? die(usage))
  const node = argv[1] ?? die(usage)
  const mode = argv[2] ?? die(usage)
  const path =
    mode === 'on' ? 'step/enable' : mode === 'off' ? 'step/disable' : mode === 'next' ? 'step' : die('step takes on, off or next')
  await api('POST', `/api/executions/${exec}/nodes/${node}/${path}`)
  console.log(`step ${mode}: ${exec} ${node}`)
  return 0
}

/** What waits for a signal, in the tree of EXEC or in every session with a running run: "<exec8> <node> <name> since <ts>" lines. */
export async function signalLines(arg?: string): Promise<string[]> {
  const root = arg ? await resolveExec(arg) : ''
  const all: Json[] = await api('GET', '/api/executions?limit=1000')
  let sessions: string[]
  let ids: string[] = []
  if (root) {
    const t: Json[] = await api('GET', `/api/executions/${root}/tree`)
    ids = t.map((x) => x.executionId)
    sessions = [...new Set(all.filter((e) => ids.includes(e.executionId)).map((e) => e.sessionId as string))].sort()
  } else sessions = [...new Set(all.filter((e) => /RUNNING|PAUSED|QUEUED/.test(e.status)).map((e) => e.sessionId as string))].sort()
  if (!sessions.length) return []
  return (await pending(sessions))
    .filter((a) => !root || ids.includes(a.executionId))
    .map((a) => `${String(a.executionId).slice(0, 8)} ${a.nodeId} ${a.name} since ${a.since}`)
}

export async function cmdSignals(arg?: string): Promise<number> {
  for (const l of await signalLines(arg)) console.log(l)
  return 0
}

/** yk approve|reject EXEC [SIGNAL] [--data TEXT]: answers a node that waits for a signal */
export async function cmdAnswer(verb: string, argv: string[]): Promise<number> {
  let exec = await resolveExec(argv[0] ?? die(`usage: yk ${verb} <execution> [signal] [--data text]`))
  let signal = '',
    data = ''
  const decision = verb === 'reject' ? 'reject' : 'approve'
  for (let i = 1; i < argv.length; i++) {
    const a = argv[i]!
    if (a === '--data') data = argv[++i] ?? die('--data needs text')
    else if (a.startsWith('-')) die(`unknown option '${a}'`)
    else signal = a
  }
  const session = await sessionOf(exec)
  if (!session) die(`execution ${exec} is not known to the server`)
  // the node may wait in a run below this one (a sub-workflow): look through the whole tree
  const tree: Json[] = await api('GET', `/api/executions/${exec}/tree`)
  const ids = tree.map((t) => t.executionId)
  const found = (await pending([session])).filter((p) => ids.includes(p.executionId) && (!signal || p.name === signal))
  // the gates of the ticket pipeline are ASK_INPUT nodes: an open input request of the tree (signal = its node or request id), answered as the person
  if (found.length === 0) {
    const open = ((await api('GET', '/api/inputs?limit=1000')) as Json[]).filter(
      (r) => ids.includes(r.executionId) && (!signal || r.nodeId === signal || r.id === signal),
    )
    if (open.length === 1) {
      const r = open[0]!
      const res = await http('POST', `${process.env.YUKTA_SERVER_URL ?? DEV_URL}/api/executions/${r.executionId}/inputs/${r.id}/answer`, {
        headers: { 'Content-Type': 'application/json', ...(process.env.YUKTA_TOKEN ? { 'X-API-Key': process.env.YUKTA_TOKEN } : {}) },
        body: JSON.stringify({ decision, ...(data ? (r.kind === 'TEXT' ? { text: data } : { rationale: data }) : {}) }),
      })
      if (res.code >= 400 || res.code === 0) die(`answer ${r.id}: HTTP ${res.code} ${tryJson(res.body)?.message ?? res.body}`.trimEnd())
      console.log(`${decision}: ${r.executionId} ${r.nodeId} (input ${r.id})`)
      return 0
    }
    if (open.length > 1) die(`${open.length} open input requests for ${exec}; name one: yk ${verb} ${exec} <node or request id>`)
  }
  if (found.length !== 1) die(`${found.length} pending signals for ${exec}${signal ? ` named ${signal}` : ''}; run: yk signals ${exec}`)
  const { nodeId: node, name } = found[0]!
  exec = found[0]!.executionId
  await api(
    'POST',
    `/api/executions/${exec}/nodes/${node}/signal?name=${uri(name)}&decision=${decision}${data ? `&data=${uri(data)}` : ''}`,
  )
  console.log(`${decision}: ${exec} ${node} (${name})`)
  return 0
}

/** Writes "N running · M awaiting" to $DEV_HOME/activity for the status line. */
export async function refreshActivity(): Promise<void> {
  try {
    const all: Json[] = await api('GET', '/api/executions?limit=1000')
    const live = all.filter((e) => /RUNNING|PAUSED|QUEUED/.test(e.status))
    let waiting = 0
    for (const s of new Set(live.map((e) => e.sessionId as string)))
      waiting += (((await api('GET', `/api/sessions/${s}/approvals`, { notFoundOk: true })) ?? []) as Json[]).length
    writeFileSync(
      join(DEV_HOME, 'activity.tmp'),
      live.length > 0 ? `${live.length} running${waiting > 0 ? ` · ${waiting} awaiting` : ''}\n` : 'idle\n',
    )
    run(['mv', join(DEV_HOME, 'activity.tmp'), join(DEV_HOME, 'activity')])
  } catch {
    /* best effort */
  }
}

// ---- the RESULT line

/** The progress of an execution as JSON (the CLI's workflow status). */
export function progressOf(session: string, exec: string): Json {
  const d = cliJson(['workflow', 'status', session, exec]).data
  return d
}

/** Prints the machine readable RESULT line; returns 0 only when the execution ended SUCCESS. */
export function printResult(session: string, exec: string): number {
  const progress = progressOf(session, exec)
  const records = healRecords(session)
  const tasks: Json[] = progress.tasks
  let status: string = progress.status
  let failed = tasks.find((t) => t.status === 'FAILURE' || t.status === 'TIMED_OUT')?.nodeId ?? '-'
  if (failed !== '-') {
    failed = nodeStage(failed)
    // the analyzers share one fixer chain; name the ones still failing
    if (failed === 'analyzers' && isFile(join(records, 'analyzers.failed')) && statSize(join(records, 'analyzers.failed')) > 0)
      failed = text(join(records, 'analyzers.failed')).trim()
  }
  const healed = healedStages(session, failed, records)
  // a run can end well and still not be done: an OUTCOME_TERMINAL that reached ESCALATE, FAILED or RETRY
  const reached = tasks.filter((t) => t.module === 'OUTCOME_TERMINAL' && t.metadata?.reached === true)
  const outcome: string = reached[0]?.metadata?.outcome ?? '-'
  if (status === 'SUCCESS' && outcome !== '-' && outcome !== 'SUCCESS') status = outcome
  // the tool and check workflows fail by outcome, not by a failed node: the stage is the reached terminal's name (<stage>.failed in check, failed in tool)
  if (failed === '-' && outcome === 'FAILED') {
    failed = (reached[0]?.nodeId ?? 'tool').replace(/\.failed$/, '')
    if (failed === 'failed') failed = 'tool'
  }
  // yk check: the failed stage's own output (the last 40 lines), which lives in the terminal's payload
  let shown = ''
  if (process.env.YUKTA_FAILED_OUTPUT && outcome === 'FAILED') {
    const r = run([findCli(), '-o', 'json', 'result', session, exec])
    const docs = r.stdout
      .split('\n')
      .filter((l) => l.trim())
      .map(tryJson)
      .filter(Boolean)
    const out: string = docs.find((d: Json) => d.kind === 'RESULT' && d.payload?.outcome === 'FAILED')?.payload?.results?.[0]?.stdout ?? ''
    if (out)
      shown = lines(out)
        .slice(-40)
        .map((l) => `OUTPUT ${failed}: ${l}`)
        .join('\n')
    if (shown) console.log(shown)
  }
  emitResult(session, exec, status, failed, healed || '-', progress)
  // what the run cost: wall seconds, heal dollars (the records of every fix attempt), and the bytes the orchestrator was shown
  const wall = runWall(progress)
  let cost = 0
  try {
    for (const f of readdirSync(records)) if (/-[0-9][^/]*\.json$/.test(f)) cost += tryJson(text(join(records, f)))?.cost ?? 0
  } catch {
    /* no records */
  }
  const costS = String(Math.round(cost * 100) / 100)
  let line = `RESULT status=${status} failed_node=${failed} healed=${healed || '-'} execution=${exec} session=${session} wall=${wall || 0} cost=$${costS}`
  // the next level: the full text of the failure is one command away
  const more =
    status === 'SUCCESS' || status === 'COMPLETED' ? '' : `MORE yk detail ${session} ${exec} [--grep PATTERN] [--tail N] [--head N]`
  let bytes =
    Number(process.env.YUKTA_WATCHED_BYTES ?? 0) +
    shown.length +
    more.length +
    (more.length > 0 ? 1 : 0) +
    (shown.length > 0 ? 1 : 0) +
    line.length +
    8
  bytes += String(bytes).length // the digits of the count are part of the count
  line += ` bytes=${bytes}`
  if (more) console.log(more)
  console.log(line)
  recordRun(status, process.env.YUKTA_SCOPE ?? '-', String(wall || 0), costS, bytes, healed || '-')
  // what the status line and the Stop hook read: the changes this result is for, and how it ended
  mkdirp(STATE_DIR)
  writeFileSync(
    join(STATE_DIR, 'last-result'),
    [
      changesSum(process.env.YUKTA_PROJECT || REPO_ROOT),
      status,
      process.env.YUKTA_SCOPE ?? '-',
      healed || '-',
      Math.floor(Date.now() / 1000),
    ].join('\t') + '\n',
  )
  if (status === 'COMPLETED' || status === 'SUCCESS') return 0
  recordFailure({
    kind: 'result',
    where: failed,
    status,
    execution: exec,
    session,
    evidence: lines(shown || failedOutput(session, exec))
      .slice(-40)
      .join('\n'),
  })
  return 1
}

const statSize = (p: string): number => {
  try {
    return statSync(p).size
  } catch {
    return 0
  }
}

const localIso = (d: Date): string => {
  const p = (n: number): string => String(n).padStart(2, '0')
  const off = -d.getTimezoneOffset()
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}${off >= 0 ? '+' : '-'}${p(Math.floor(Math.abs(off) / 60))}:${p(Math.abs(off) % 60)}`
}

/** Appends each finished node's duration to $STATE_DIR/durations.tsv (date, workflow:scope, stage, seconds, execution) and prints
 *   SLOW <stage> <s>s (median <m>s, <n> runs)
 * for a stage that took over 15s and 1.5x the median of its last 10 runs at the same workflow and scope (at least 3 earlier runs). A node reports its own
 * start, so the seconds of a stage are the sum of its nodes (S.run1, S.f1, S.run2, ...). */
export function recordDurations(session: string, exec: string, scope: string): void {
  const file = join(STATE_DIR, 'durations.tsv')
  mkdirp(STATE_DIR)
  appendFileSync(file, '')
  const p = progressOf(session, exec)
  const per = new Map<string, number>()
  for (const t of (p.tasks as Json[]).filter(
    (t) =>
      !/TERMINAL/.test(t.module ?? '') &&
      t.startTime?.slice(0, 4) !== '0001' &&
      t.endTime?.slice(0, 4) !== '0001' &&
      t.startTime &&
      t.endTime,
  )) {
    const st = nodeStage(t.nodeId)
    per.set(st, (per.get(st) ?? 0) + (secs(t.endTime) - secs(t.startTime)))
  }
  for (const [stage, total] of [...per].sort(([a], [b]) => (a < b ? -1 : 1))) {
    const s = Math.round(total * 1000) / 1000
    // a stage that was not planned takes about no time: recording it would drag the median to 0
    if (!(s >= 2)) continue
    const key = `${p.workflowId}:${scope}`
    const prior = lines(text(file))
      .map((l) => l.split('\t'))
      .filter((r) => r[1] === key && r[2] === stage)
      .map((r) => r[3]!)
      .slice(-10)
      .sort((a, b) => Number(a) - Number(b))
    if (prior.length >= 3 && s > 15) {
      const median = prior[Math.floor((prior.length + 1) / 2) - 1]!
      if (s * 2 > Number(median) * 3) console.log(`SLOW ${stage} ${s}s (median ${median}s, ${prior.length} runs)`)
    }
    appendFileSync(file, [localIso(new Date()), key, stage, s, exec].join('\t') + '\n')
  }
}

// ---- watching a run

const NOISE = new RegExp(
  [
    '^NODE [^ ]+ (SKIPPED|UPSTREAM_FAILED|CANCELLED)$',
    '^(ERROR|WARN) (heal|attempt|claude|fix-from)/(trigger|heal\\?|auto\\?|brief|prepare|first|triage\\?|report|fixed\\?|carry\\.[a-z]+):',
    '^(ERROR|WARN) [^ ]+: (BUILD FAILED in .*|Process failed with exit code [0-9]+|WARNING: .*(sun\\.misc\\.Unsafe|Unsafe).*)$',
    '^WARN [^ ]+: (\\* Deprecated licenses: 0|Set `VITE_CONFIG_NATIVE_IGNORE_WARNING=true`.*)$',
  ].join('|'),
)

/** Streams the lines of a child process to a callback, one by one. */
async function eachLine(proc: Subprocess<'ignore', 'pipe', 'ignore'>, f: (line: string) => void): Promise<void> {
  const dec = new TextDecoder()
  let buf = ''
  for await (const chunk of proc.stdout) {
    buf += dec.decode(chunk, { stream: true })
    let i
    while ((i = buf.indexOf('\n')) >= 0) {
      f(buf.slice(0, i))
      buf = buf.slice(i + 1)
    }
  }
  if (buf) f(buf)
}

/** Follows a run until it ends, problems only (--all: every event; --stages: also one "STAGE <name> finished" line per stage that ran, and
 * "STAGE <name>.heal finished" per heal, so a Monitor sees progress on a clean run), then SLOW lines and the RESULT line. */
export async function cmdWatch(session: string, target: string, mode = ''): Promise<number> {
  let shownBytes = 0
  const out = (l: string): void => {
    process.stdout.write(l + '\n')
    shownBytes += Buffer.byteLength(l) + 1
  }
  process.env.YUKTA_SERVER_URL ??= DEV_URL
  // a checkpoint that waits for an answer is news too: say so, once, while the run is watched
  const seen = new Set<string>()
  const announce = { stop: false }
  const announcer = (async (): Promise<void> => {
    while (!announce.stop) {
      await sleep(3000)
      if (announce.stop) return
      try {
        for (const line of await signalLines(target)) {
          const key = line.split(' since')[0]!
          if (seen.has(key)) continue
          seen.add(key)
          const [, node, name] = line.split(' ')
          out(`APPROVAL ${name} waits at ${node}: yk approve ${target} ${name} (or yk reject ${target} ${name})`)
        }
      } catch {
        /* the server may be busy */
      }
    }
  })()
  const cli = findCli()
  const procs: Promise<void>[] = []
  if (mode === '--stages') {
    const p = spawn([cli, 'controlbus', 'watch', '--execution', target, '--until-done'], {
      stdout: 'pipe',
      stderr: 'ignore',
      stdin: 'ignore',
      env: process.env as Record<string, string>,
    })
    procs.push(
      eachLine(p, (l) => {
        const m = /^NODE (([a-z_]+|verify)(\.heal)?) SUCCESS$/.exec(l)
        if (m && !/^NODE (trigger|done) /.test(l)) out(`STAGE ${m[1]} finished`)
      }),
    )
  }
  // A node that never ran (a rung nobody needed, or one behind the failure) is not news: the check has dozens of them.
  const quiet = mode !== '--all'
  const w = spawn([cli, 'controlbus', 'watch', '--execution', target, '--until-done', ...(quiet ? ['--quiet'] : [])], {
    stdout: 'pipe',
    stderr: 'inherit',
    stdin: 'ignore',
    env: process.env as Record<string, string>,
  })
  procs.push(
    eachLine(w as never, (l) => {
      if (!quiet || !NOISE.test(l)) out(l)
    }),
  )
  await Promise.all(procs)
  process.env.YUKTA_WATCHED_BYTES = String(shownBytes)
  announce.stop = true
  await announcer
  recordDurations(session, target, process.env.YUKTA_SCOPE ?? '-')
  return printResult(session, target)
}

/** cmdWatch for a run this client started. The run ends with the client: Ctrl-C, or Claude Code stopping the background task (SIGTERM), stops the
 * execution and the runs below it (a drain: work in progress gets up to 30 s). --detach is the way to let a run outlive its client. */
export async function watchOwned(session: string, exec: string): Promise<number> {
  const onSignal = async (): Promise<void> => {
    console.error(`interrupted: stopping ${exec} and the runs below it`)
    try {
      await api('POST', `/api/executions/${exec}/stop?tree=true`)
    } catch {
      /* best effort */
    }
    recordStopped(session, exec)
    process.exit(130)
  }
  process.on('SIGINT', () => void onSignal())
  process.on('SIGTERM', () => void onSignal())
  return cmdWatch(session, exec)
}

/** The result event of a run its client stopped (the watch that would have written it is gone), so the run does not vanish from the log. */
function recordStopped(session: string, exec: string): void {
  let progress: Json
  try {
    progress = progressOf(session, exec)
  } catch {
    progress = undefined
  }
  emitResult(session, exec, 'STOPPED', '-', '-', progress ?? { tasks: [] })
}

// ---- --notify: a detached `yk _notify` follows the run and posts into the Claude Code session that started it
export function startNotifier(session: string, exec: string, ykPath: string): void {
  if (!process.env.CLAUDE_CODE_MESSAGING_SOCKET) {
    console.error(
      'notify: not started from a Claude Code session with cross-session messaging (no CLAUDE_CODE_MESSAGING_SOCKET); the run goes on without it',
    )
    return
  }
  if ((process.env.YUKTA_NOTIFY ?? 'on') === 'off') return
  mkdirp(STATE_DIR)
  const fd = openSync(join(STATE_DIR, `notify-${exec}.log`), 'a')
  const p = spawn([process.execPath, ykPath, '_notify', session, exec], {
    stdin: 'ignore',
    stdout: fd,
    stderr: fd,
    env: process.env as Record<string, string>,
  })
  p.unref()
  closeSync(fd)
}

/** yk _notify SESSION EXECUTION (internal): follows the run like `yk watch`, posts each APPROVAL line at once and, when the run ends, the summary. */
export async function cmdNotifyRun(session: string, exec: string): Promise<number> {
  const tmp = mkdtempSync(join(tmpdir(), 'yk-notify-'))
  const logFile = join(tmp, 'log')
  writeFileSync(logFile, '')
  const origWrite = process.stdout.write.bind(process.stdout)
  process.stdout.write = ((chunk: string | Uint8Array): boolean => {
    const s = typeof chunk === 'string' ? chunk : Buffer.from(chunk).toString()
    appendFileSync(logFile, s)
    for (const l of s.split('\n')) if (l.startsWith('APPROVAL ')) void notifySession(`[yk] ${l}`)
    return origWrite(chunk)
  }) as typeof process.stdout.write
  try {
    await cmdWatch(session, exec)
  } finally {
    process.stdout.write = origWrite
  }
  await notifySession(notifyText(session, exec, readFileSync(logFile, 'utf8')))
  rmSync(tmp, { recursive: true, force: true })
  return 0
}

/** yk detail SESSION EXECUTION [--grep P] [--tail N] [--head N] */
export function cmdDetail(argv: string[]): number {
  const usage = 'usage: yk detail <session> <execution> [--grep P] [--tail N] [--head N]'
  const session = argv[0] ?? die(usage)
  const target = argv[1] ?? die(usage)
  let grep = '',
    tail = '',
    head = ''
  for (let i = 2; i < argv.length; i++) {
    const a = argv[i]
    if (a === '--grep') grep = argv[++i] ?? die('--grep needs a pattern')
    else if (a === '--tail') tail = argv[++i] ?? die('--tail needs a number')
    else if (a === '--head') head = argv[++i] ?? die('--head needs a number')
    else die(`unknown option '${a}' (${usage})`)
  }
  process.env.YUKTA_SERVER_URL ??= DEV_URL
  let t = failedOutput(session, target)
  if (!t) {
    console.log(`no failed stage output for ${target} (it did not fail, or it is not a check or tool run)`)
    return 0
  }
  let ls = lines(t)
  if (grep) ls = ls.filter((l) => l.includes(grep)) // a plain substring: the pattern comes from the command line
  if (head) ls = ls.slice(0, Number(head))
  if (tail) ls = ls.slice(-Number(tail))
  t = ls.join('\n')
  console.log(t)
  return 0
}

void HERE
void keyHeader
void shq
