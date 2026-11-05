// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// Dogfood helpers: results, heal events, plan filtering, argument parsing. `lib.ts` re-exports every module.
import {
  DEV_HOME,
  HERE,
  Json,
  REPO_ROOT,
  STATE_DIR,
  appendFileSync,
  basename,
  die,
  dirname,
  git,
  isDir,
  isFile,
  isoNow,
  join,
  mkdirp,
  readJson,
  readdirSync,
  run,
  text,
  tryJson,
} from './base.ts'
import { findCli } from './client.ts'
import { RUNNING } from './server.ts'

// ---- the self-healing check
/** Where heal's monitoring events go: YUKTA_HEAL_EVENTS, else heal-events.jsonl in the dev state dir; "off" or an empty value turns it off. */
export function healEventsFile(): string {
  const f = process.env.YUKTA_HEAL_EVENTS ?? join(DEV_HOME, 'heal-events.jsonl')
  return f === 'off' ? '' : f
}

/** The stage a node of the check belongs to (S.heal and S.failed belong to S). */
export const nodeStage = (n: string): string => n.replace(/\.(run[0-9]+|f[0-9]+|r[0-9]+|pause|heal)$/, '')

const BAD_TIME = (t: string | undefined): boolean => !t || t.slice(0, 4) === '0001'
/** Whole seconds plus the fraction the server prints. */
export function secs(t: string): number {
  const base = Date.parse(t.slice(0, 19) + 'Z') / 1000
  const f = /^\.(\d+)/.exec(t.slice(19))
  return base + (f ? parseFloat('0.' + f[1]) : 0)
}
const r1 = (x: number): number => Math.round(x * 10) / 10

type Task = { nodeId: string; startTime?: string; endTime?: string; status?: string; module?: string; metadata?: Json }
const finished = (progress: Json): { node: string; s: number; e: number }[] =>
  (progress.tasks as Task[])
    .filter((t) => !BAD_TIME(t.startTime) && !BAD_TIME(t.endTime))
    .map((t) => ({ node: t.nodeId, s: secs(t.startTime!), e: secs(t.endTime!) }))

/** The `result` event of the monitoring log: how the run ended, its wall time, and the seconds each node took. Every run ends in exactly one result
 * with a terminal status. */
export function emitResult(session: string, exec: string, status: string, failed: string, healed: string, progress: Json): void {
  const events = healEventsFile()
  if (!events || RUNNING.test(status)) return
  try {
    if (
      isFile(events) &&
      text(events)
        .split('\n')
        .some((l) => l.includes(`"run":"${exec}"`) && l.includes('"event":"result"'))
    )
      return
    const project = process.env.YUKTA_PROJECT || REPO_ROOT
    mkdirp(dirname(events))
    const t = finished(progress)
    const dur: Record<string, number> = {}
    for (const x of t) dur[nodeStage(x.node)] = (dur[nodeStage(x.node)] ?? 0) + (x.e - x.s)
    const ev = {
      v: 1,
      ts: isoNow(),
      run: exec,
      session,
      project: basename(project),
      event: 'result',
      workflow: progress.workflowId ?? '',
      status,
      failed_node: failed,
      healed,
      scope: process.env.YUKTA_SCOPE ?? 'all',
      sha: git(['rev-parse', '--short=9', 'HEAD'], project).stdout.trim() || 'unknown',
      branch: git(['rev-parse', '--abbrev-ref', 'HEAD'], project).stdout.trim() || 'unknown',
      secs: t.length ? r1(Math.max(...t.map((x) => x.e)) - Math.min(...t.map((x) => x.s))) : null,
      durations: Object.fromEntries(
        Object.entries(dur)
          .sort(([a], [b]) => (a < b ? -1 : 1))
          .map(([k, v]) => [k, r1(v)]),
      ),
    }
    appendFileSync(events, JSON.stringify(ev) + '\n')
  } catch {
    /* monitoring must not break a run */
  }
}

/** The directory with the heal records of the last check run: YUKTA_HEAL_RECORDS when set, else the session's own. */
export const healRecords = (session: string): string => process.env.YUKTA_HEAL_RECORDS ?? join(STATE_DIR, 'heal', session)

const recordFiles = (dir: string): string[] =>
  isDir(dir)
    ? readdirSync(dir)
        .filter((f) => /-[0-9][^/]*\.json$/.test(f))
        .sort()
        .map((f) => join(dir, f))
    : []

/** "stage:step,..." for every heal step of the last check run that fixed its stage, except the stage that still failed. */
export function healedStages(session: string, failed = '-', dir = healRecords(session)): string {
  const out = new Set<string>()
  for (const f of recordFiles(dir)) {
    const r = tryJson(text(f))
    if (r?.fixed && r.stage !== failed) out.add(`${r.stage}:${r.mode}${r.effort ? '/' + r.effort : ''}`)
  }
  return [...out].sort().join(',')
}

/** Whole seconds from the first node start to the last node end ("" when no node ran). */
export function runWall(progress: Json): string {
  const t = finished({
    tasks: (progress.tasks as Task[]).map((x) => ({
      ...x,
      startTime: x.startTime?.replace(/\.\d+/, ''),
      endTime: x.endTime?.replace(/\.\d+/, ''),
    })),
  })
  return t.length ? String(Math.max(...t.map((x) => x.e)) - Math.min(...t.map((x) => x.s))) : ''
}

/** One tab separated row per run in $STATE_DIR/runs.tsv (yk stats reads it). */
export function recordRun(status: string, kind: string, wall: string, cost: string, bytes: number, healed: string): void {
  try {
    mkdirp(STATE_DIR)
    appendFileSync(join(STATE_DIR, 'runs.tsv'), [isoNow(), kind, status, wall, cost, bytes, healed].join('\t') + '\n')
  } catch {
    /* best effort */
  }
}

/** The plan on stdin (yk plan's lines) without the stages not in ONLY (a comma list, "" = all) or in SKIP. A stage name matches with - or _ either way. */
export function filterPlan(plan: string, only: string, skip: string): string {
  const norm = (s: string): string => s.replace(/-/g, '_')
  const O = new Set(only.split(',').filter(Boolean).map(norm))
  const K = new Set(skip.split(',').filter(Boolean).map(norm))
  return plan
    .split('\n')
    .filter(
      (l) => l.startsWith('#') || (l.length > 0 && (O.size === 0 || O.has(norm(l.split('\t')[0]!))) && !K.has(norm(l.split('\t')[0]!))),
    )
    .join('\n')
}

/** The whole output of the failed stage (the stdout in the payload of the terminal that ended the run FAILED). */
export function failedOutput(session: string, exec: string): string {
  const r = run([findCli(), '-o', 'json', 'result', session, exec])
  const docs = r.stdout
    .split('\n')
    .filter((l) => l.trim())
    .map(tryJson)
    .filter(Boolean)
  const hit = docs.find((d: Json) => d.kind === 'RESULT' && (d.payload?.outcome === 'FAILED' || d.payload?.success === false))
  const p = hit?.payload
  return (p?.results?.[0]?.stdout ?? p?.stdout ?? '') as string
}

/** The triage model of heal. YUKTA_TRIAGE_<KEY> beats $DEV_HOME/triage.json, which beats heal.json. */
export function triageConf(key: 'url' | 'model'): string {
  const v = process.env[`YUKTA_TRIAGE_${key.toUpperCase()}`]
  if (v) return v
  const local = tryJson(text(join(process.env.YUKTA_DEV_HOME || DEV_HOME, 'triage.json')))?.[key]
  if (local) return String(local)
  return String(readJson(join(HERE, 'heal.json')).triage[key])
}

export function parseArgs(
  argv: string[],
  spec: { value?: string[]; bool?: string[] },
  usage: string,
): { pos: string[]; opt: Record<string, string>; flag: Set<string> } {
  const pos: string[] = []
  const opt: Record<string, string> = {}
  const flag = new Set<string>()
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i]!
    if (a.startsWith('--') && a.length > 2) {
      const [k, eq] = a.slice(2).split(/=(.*)/s)
      if (spec.bool?.includes(k!)) flag.add(k!)
      else if (spec.value?.includes(k!)) {
        const v = eq ?? argv[++i]
        if (v === undefined) die(`--${k} needs a value (${usage})`, 2)
        opt[k!] = v
      } else die(`unknown option '${a}' (${usage})`, 2)
    } else pos.push(a)
  }
  return { pos, opt, flag }
}

/** json.dumps of Python: ", " and ": " separators when compact, non-ASCII escaped; `sort` orders the keys. */
export function dumps(v: Json, opts: { indent?: number; sort?: boolean } = {}): string {
  const sorted = (x: Json): Json =>
    Array.isArray(x)
      ? x.map(sorted)
      : x && typeof x === 'object'
        ? Object.fromEntries(
            Object.keys(x)
              .sort()
              .map((k) => [k, sorted(x[k])]),
          )
        : x
  const compact = (x: Json): string =>
    Array.isArray(x)
      ? '[' + x.map(compact).join(', ') + ']'
      : x && typeof x === 'object'
        ? '{' +
          Object.keys(x)
            .filter((k) => x[k] !== undefined)
            .map((k) => JSON.stringify(k) + ': ' + compact(x[k]))
            .join(', ') +
          '}'
        : (JSON.stringify(x) ?? 'null')
  const data = opts.sort ? sorted(v) : v
  const s = opts.indent ? JSON.stringify(data, null, opts.indent) : compact(data)
  return s.replace(/[\u0080-￿]/g, (c) => '\\u' + c.charCodeAt(0).toString(16).padStart(4, '0'))
}
