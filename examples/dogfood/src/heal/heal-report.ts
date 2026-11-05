// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// What heal did, from its event log: where the time and the money went, and what looks wrong.
//
//   heal-report report  [--events FILE] [--since 7d|DATE] [--run ID] [--project NAME] [--json]
//   heal-report show    [same options]      the events as one line each, oldest first
//
// The log is examples/dogfood/README.md "Monitoring heal": one JSON object per line, written by heal.ts and yk (run is "-" in the first; the report
// stamps it from the session's next result). Every event has v, ts, run (the root execution id), session, project and event; the rest depends on the event:
//   stage    stage, secs, exit, ok, cmd, and on a failure sig and head (a stable hash of the failure and its first lines)
//   triage   stage, route (level | uncertain | error), label, p, start, model, secs (since the brief), chars, fallback, error
//   fix      stage, attempt, model, effort, fixed, claimed, files, cost, usage, secs, guard, summary
//   recheck  stage, secs, ok          verify  secs, ok          giveup  stage
//   result   workflow, status, failed_node, healed, secs, scope, durations (seconds per stage), sha, branch
//
import { homedir } from 'node:os'
import { type Json, dumps, isFile, join, parseArgs, readFileSync, tryJson } from '../lib.ts'

const DEFAULT = join(process.env.YUKTA_DEV_STATE_DIR ?? join(homedir(), '.local/state/yukta-dev'), 'heal-events.jsonl')
const MODELS = ['haiku', 'sonnet', 'opus']

export function load(path: string): Json[] {
  if (!isFile(path)) return []
  return readFileSync(path, 'utf8')
    .split('\n')
    .map(tryJson)
    .filter((e: Json) => e && typeof e === 'object' && !Array.isArray(e) && e.event)
}

/** heal.ts cannot know its run id (run "-"); the result event closes the run, so an unstamped event belongs to the next result of its session. */
export function stamp(ev: Json[]): Json[] {
  const pending = new Map<string, Json[]>()
  const unset = (r: Json): boolean => r === undefined || r === null || r === '' || r === '-'
  for (const e of ev) {
    if (unset(e.run)) pending.set(e.session, [...(pending.get(e.session) ?? []), e])
    if (e.event === 'result' && !unset(e.run)) {
      for (const u of pending.get(e.session) ?? []) u.run = e.run
      pending.delete(e.session)
    }
  }
  return ev
}

function since(spec?: string): string | null {
  if (!spec) return null
  if ('dh'.includes(spec.slice(-1)) && /^\d+$/.test(spec.slice(0, -1))) {
    const n = Number(spec.slice(0, -1)) * (spec.endsWith('d') ? 86400 : 3600)
    return new Date(Date.now() - n * 1000).toISOString().replace(/\.\d+Z$/, 'Z')
  }
  return spec
}

const roundHalfEven = (x: number): number => {
  const f = Math.floor(x)
  const d = x - f
  return d < 0.5 ? f : d > 0.5 ? f + 1 : f % 2 === 0 ? f : f + 1
}
const pct = (xs: number[], q: number): number => {
  const s = [...xs].sort((a, b) => a - b)
  return s.length ? s[Math.min(s.length - 1, roundHalfEven(q * (s.length - 1)))]! : 0
}
const mean = (xs: number[]): number => xs.reduce((a, b) => a + b, 0) / xs.length
const median = (xs: number[]): number => {
  const s = [...xs].sort((a, b) => a - b)
  const m = s.length >> 1
  return s.length % 2 ? s[m]! : (s[m - 1]! + s[m]!) / 2
}
const sum = (xs: number[]): number => xs.reduce((a, b) => a + b, 0)
const fmtS = (s: number): string => (s < 120 ? `${s.toFixed(0)}s` : `${(s / 60).toFixed(1)}m`)
const rung = (e: Json): string => (e.effort ? `${e.model}/${e.effort}` : String(e.model))
const counter = <T>(xs: T[]): Map<T, number> => {
  const m = new Map<T, number>()
  for (const x of xs) m.set(x, (m.get(x) ?? 0) + 1)
  return m
}
const mostCommon = <T>(m: Map<T, number>): [T, number][] =>
  [...m]
    .map((kv, i) => [kv, i] as const)
    .sort(([a, i], [b, j]) => b[1] - a[1] || i - j)
    .map(([kv]) => kv)
const obj = (m: Map<unknown, number>): Json => Object.fromEntries([...m].map(([k, v]) => [String(k), v]))
const pyDict = (o: Json): string =>
  '{' +
  Object.entries(o)
    .map(([k, v]) => `'${k}': ${v}`)
    .join(', ') +
  '}'
const pad = (s: string | number, n: number): string => String(s).padEnd(n)
const rpad = (s: string | number, n: number): string => String(s).padStart(n)

type Heal = { fix: Json[]; triage: Json | null; recheck: Json[]; giveup: boolean }
function analyse(ev: Json[]): { runs: Map<string, Json[]>; heals: Map<string, Heal> } {
  const runs = new Map<string, Json[]>()
  for (const e of ev) {
    const r = e.run ?? '-'
    runs.set(r, [...(runs.get(r) ?? []), e])
  }
  const heals = new Map<string, Heal>() // run\0stage; only heal events open an entry
  const entry = (r: string, stage: string): Heal => {
    const k = `${r}\0${stage}`
    return heals.get(k) ?? heals.set(k, { fix: [], triage: null, recheck: [], giveup: false }).get(k)!
  }
  for (const [r, es] of runs) {
    for (const e of es) {
      if (e.event === 'fix' && e.model !== 'auto') entry(r, e.stage).fix.push(e)
      else if (e.event === 'triage') entry(r, e.stage).triage = e
      else if (e.event === 'recheck') entry(r, e.stage).recheck.push(e)
      else if (e.event === 'giveup') entry(r, e.stage).giveup = true
    }
  }
  return { runs, heals }
}

export function report(ev: Json[]): Json {
  const { runs, heals } = analyse(ev)
  const R: Json = {}
  // a plain gradle run has no heal; a run's result can be written twice: the last one counts
  const last = new Map<string, Json>()
  for (const e of ev) if (e.event === 'result' && e.workflow !== 'gradle-run') last.set(e.run, e)
  const results = [...last.values()]
  const secsOf = results.filter((e) => 'secs' in e).map((e) => e.secs as number)
  R.runs = {
    with_events: runs.size,
    results: results.length,
    status: obj(counter(results.map((e) => e.status))),
    wall_median_s: median(secsOf.length ? secsOf : [0]),
    wall_p95_s: pct(secsOf, 0.95),
  }
  const tot = new Map<string, number>()
  const label: Record<string, string> = {
    stage: 'stage runs',
    triage: 'triage',
    fix: 'fix attempts',
    recheck: 'rechecks',
    verify: 'verify',
  }
  for (const e of ev) {
    const k = label[e.event]
    if (k) tot.set(k, (tot.get(k) ?? 0) + (e.secs || 0))
  }
  R.time = obj(tot)
  const stages = new Map<string, { runs: number; failed: number; secs: number[] }>()
  for (const e of ev) {
    if (e.event === 'stage') {
      const s = stages.get(e.stage) ?? stages.set(e.stage, { runs: 0, failed: 0, secs: [] }).get(e.stage)!
      s.runs++
      s.failed += (e.ok ?? true) ? 0 : 1
      s.secs.push(e.secs || 0)
    }
  }
  R.stages = Object.fromEntries(
    [...stages].map(([k, v]) => [k, { runs: v.runs, failed: v.failed, mean_s: mean(v.secs), p95_s: pct(v.secs, 0.95) }]),
  )
  const hs = [...heals.values()].filter((h) => h.fix.length || h.triage || h.giveup)
  const solved = hs.filter((h) => h.fix.some((f) => f.fixed))
  const fixedAt = counter(solved.map((h) => rung(h.fix.find((f) => f.fixed)!)))
  R.heals = {
    count: hs.length,
    solved: solved.length,
    giveups: hs.filter((h) => h.giveup).length,
    attempts_per_heal: hs.length ? mean(hs.map((h) => h.fix.length)) : 0,
    fixed_at: Object.fromEntries(mostCommon(fixedAt)),
  }
  const tr = ev.filter((e) => e.event === 'triage')
  const lat = tr.filter((e) => e.secs !== undefined && e.secs !== null).map((e) => e.secs as number)
  const ps = tr.filter((e) => e.p).map((e) => e.p as number)
  R.triage = {
    calls: tr.length,
    routes: obj(counter(tr.map((e) => e.route))),
    fallbacks: obj(counter(tr.filter((e) => e.fallback).map((e) => e.fallback))),
    latency_p50_s: pct(lat, 0.5),
    latency_p95_s: pct(lat, 0.95),
    confidence_median: median(ps.length ? ps : [0]),
    start: Object.fromEntries(mostCommon(counter(tr.map((e) => e.start)))),
  }
  let under = 0
  for (const h of solved) {
    if (h.triage?.start && h.triage.route === 'level') {
      const s = String(h.triage.start).split('/')[0]!
      const f = h.fix.find((x) => x.fixed)!.model
      if (MODELS.includes(s) && MODELS.includes(f)) under += MODELS.indexOf(f) > MODELS.indexOf(s) ? 1 : 0
    }
  }
  R.triage.fixed_by_a_higher_model_than_it_started_at = under
  const cost = new Map<string, { attempts: number; fixed: number; usd: number; secs: number; cache: number; tokens: number }>()
  for (const e of ev) {
    if (e.event === 'fix' && e.model !== 'auto') {
      const c = cost.get(rung(e)) ?? cost.set(rung(e), { attempts: 0, fixed: 0, usd: 0, secs: 0, cache: 0, tokens: 0 }).get(rung(e))!
      c.attempts++
      c.fixed += e.fixed ? 1 : 0
      c.usd += e.cost || 0
      c.secs += e.secs || 0
      const u = e.usage ?? {}
      c.cache += u.cache_read || 0
      c.tokens += (u.input || 0) + (u.cache_creation || 0) + (u.cache_read || 0)
    }
  }
  const r3 = (x: number): number => Math.round(x * 1000) / 1000
  R.cost = Object.fromEntries(
    [...cost]
      .sort(([a], [b]) => (a < b ? -1 : 1))
      .map(([k, v]) => [k, { ...v, usd: r3(v.usd), cache_read_share: v.tokens ? Math.round((v.cache / v.tokens) * 100) / 100 : null }]),
  )
  R.cost_total_usd = r3(sum([...cost.values()].map((v) => v.usd)))
  // recurring failures
  const sig = new Map<string, { n: number; runs: Set<string>; stage: string | null; head: string; last: string; healed: number }>()
  for (const e of ev) {
    if (e.event === 'stage' && e.sig) {
      const s = sig.get(e.sig) ?? sig.set(e.sig, { n: 0, runs: new Set(), stage: null, head: '', last: '', healed: 0 }).get(e.sig)!
      s.n++
      s.runs.add(e.run)
      s.stage = e.stage
      s.head = e.head ?? ''
      s.last = (e.ts ?? '') > s.last ? (e.ts ?? '') : s.last
    }
  }
  for (const [k, h] of heals) {
    if (h.fix.some((f) => f.fixed)) {
      const [r, st] = k.split('\0') as [string, string]
      const hit = (runs.get(r) ?? []).find((e) => e.event === 'stage' && String(e.stage) === st && e.sig)
      if (hit) sig.get(hit.sig)!.healed++
    }
  }
  R.recurring = [...sig]
    .filter(([, v]) => v.n > 1)
    .sort(([, a], [, b]) => b.n - a.n)
    .slice(0, 10)
    .map(([k, v]) => ({ sig: k, n: v.n, runs: v.runs.size, stage: v.stage, healed: v.healed, last: v.last, head: v.head }))
  return R
}

export function findings(ev: Json[]): [string, string][] {
  const { runs, heals } = analyse(ev)
  const out: [string, string][] = []
  const fx = ev.filter((e) => e.event === 'fix' && e.model !== 'auto')
  const noop = fx.filter((e) => !e.fixed && !(e.files ?? []).length)
  if (noop.length) {
    out.push([
      'noop-fixers',
      `${noop.length} of ${fx.length} fixer attempt(s) changed nothing and cost $${sum(noop.map((e) => e.cost || 0)).toFixed(2)}: ${[...new Set(noop.map((e) => `${e.stage} ${rung(e)}`))].sort().slice(0, 6).join(', ')}`,
    ])
  }
  const outside: string[] = []
  for (const [r, es] of runs) {
    const scope = es.find((e) => e.event === 'result' && e.scope)?.scope as string | undefined
    const mods = (scope ?? '')
      .replaceAll(',', ' ')
      .split(/\s+/)
      .filter((m) => m && m !== 'all' && !m.startsWith('args:'))
    if (!mods.length) continue
    for (const e of es) {
      if (e.event === 'fix' && e.model !== 'auto') {
        const bad = ((e.files ?? []) as string[]).filter(
          (f) => !mods.some((m) => f === m || f.startsWith(m + '/')) && !/^(config\/|gradle\/|build-logic\/)/.test(f),
        )
        if (bad.length) outside.push(`${r.slice(0, 8)} ${e.stage}: ${bad.slice(0, 3).join(', ')}`)
      }
    }
  }
  if (outside.length)
    out.push([
      'fixer-outside-module',
      `${outside.length} fixer attempt(s) changed files outside the scoped module(s): ${outside.slice(0, 4).join('; ')}`,
    ])
  const mism = [...runs]
    .filter(
      ([, es]) =>
        es.some((e) => e.event === 'result' && ['-', '', undefined, null].includes(e.healed) && e.status === 'SUCCESS') &&
        es.some((e) => e.event === 'fix' && e.fixed),
    )
    .map(([r]) => r)
  if (mism.length)
    out.push([
      'healed-field-mismatch',
      `${mism.length} run(s) say healed=- although a fixer fixed something: ${mism
        .slice(0, 5)
        .map((m) => m.slice(0, 8))
        .join(', ')}`,
    ])
  const tr = ev.filter((e) => e.event === 'triage')
  const fb = tr.filter((e) => e.fallback)
  if (tr.length >= 3 && fb.length / tr.length > 0.2) {
    const why = [...counter(fb.map((e) => e.fallback))].map(([k, v]) => `${k}: ${v}`).join(', ')
    out.push([
      'triage-fallbacks',
      `${fb.length} of ${tr.length} triage call(s) fell back to the default level (${why}); the first error: ${String(fb.find((e) => e.error)?.error ?? 'none').slice(0, 160)}`,
    ])
  }
  const lat = tr.filter((e) => e.secs !== undefined && e.secs !== null).map((e) => e.secs as number)
  if (lat.length >= 3 && pct(lat, 0.95) > 45)
    out.push([
      'triage-slow',
      `triage p95 latency is ${pct(lat, 0.95).toFixed(0)}s (Ollama cuts a System One call off at 60s): shorten the brief or the level descriptions`,
    ])
  const solved = [...heals.values()].filter((h) => h.fix.some((f) => f.fixed) && h.triage && h.triage.route === 'level')
  const under = solved.filter((h) => {
    const s = String(h.triage!.start ?? '/').split('/')[0]!
    const f = h.fix.find((x) => x.fixed)!.model
    return MODELS.includes(s) && MODELS.includes(f) && MODELS.indexOf(f) > MODELS.indexOf(String(h.triage!.start).split('/')[0]!)
  })
  if (solved.length >= 5 && under.length / solved.length >= 0.5)
    out.push([
      'under-triage',
      `${under.length} of ${solved.length} triaged heals were fixed by a stronger model than the triage started at: the levels are rated too low`,
    ])
  const guard = fx.filter((e) => e.guard)
  if (guard.length)
    out.push([
      'guard-hits',
      `${guard.length} fixer attempt(s) hit a guard: ${[...new Set(guard.map((e) => String(e.guard).slice(0, 60)))].sort().slice(0, 3).join('; ')}`,
    ])
  const give = [...heals].filter(([, h]) => h.giveup)
  if (give.length)
    out.push([
      'giveups',
      `${give.length} heal(s) gave up after every model: ${[...new Set(give.map(([k]) => k.split('\0')[1]))].sort().join(', ')}`,
    ])
  const sigs = new Map<string, number>()
  for (const e of ev) if (e.event === 'stage' && e.sig) sigs.set(`${e.sig}\0${e.stage}`, (sigs.get(`${e.sig}\0${e.stage}`) ?? 0) + 1)
  const rec = [...sigs].filter(([, n]) => n >= 3)
  if (rec.length) {
    const worst = rec.reduce((a, b) => (b[1] > a[1] ? b : a))
    out.push([
      'repeated-failure',
      `${rec.length} failure(s) came back three times or more, the worst: ${worst[0].split('\0')[1]} (${Math.max(...rec.map(([, n]) => n))} times)`,
    ])
  }
  const stuck = [...runs]
    .filter(
      ([, es]) =>
        es.some((e) => e.event === 'result' && e.status === 'RUNNING') &&
        !es.some((e) => e.event === 'result' && e.status !== 'RUNNING' && e.status !== undefined && e.status !== null),
    )
    .map(([r]) => r)
  if (stuck.length)
    out.push([
      'unfinished-results',
      `${stuck.length} run(s) logged status RUNNING and never a final result: ${stuck
        .slice(0, 5)
        .map((r) => r.slice(0, 8))
        .join(', ')}`,
    ])
  const ru = fx.filter((e) => e.stage === 'reuse')
  if (ru.length >= 5 && ru.filter((e) => e.fixed).length / ru.length < 0.5)
    out.push([
      'reuse-low-fix-rate',
      `the reuse heal fixed ${ru.filter((e) => e.fixed).length} of ${ru.length} attempt(s): a free fix (src/heal/fix-reuse.ts) should come first`,
    ])
  return out
}

export function text(R: Json): string {
  const L: string[] = []
  const r = R.runs
  L.push(
    `Runs: ${r.with_events} with events, ${r.results} finished ${pyDict(r.status)}; wall time median ${fmtS(r.wall_median_s)}, p95 ${fmtS(r.wall_p95_s)}`,
  )
  const t: Record<string, number> = R.time
  const total = sum(Object.values(t)) || 1
  L.push('\nWhere the time goes (summed over the events)')
  for (const [k, v] of Object.entries(t).sort(([, a], [, b]) => b - a))
    L.push(`  ${pad(k, 14)} ${rpad(fmtS(v), 8)}  ${rpad(((100 * v) / total).toFixed(0), 4)}%`)
  L.push('\nStages            runs  failed  mean    p95')
  for (const [k, v] of Object.entries<Json>(R.stages).sort(([, a], [, b]) => b.mean_s * b.runs - a.mean_s * a.runs))
    L.push(`  ${pad(k, 14)} ${rpad(v.runs, 5)} ${rpad(v.failed, 7)} ${rpad(fmtS(v.mean_s), 6)} ${rpad(fmtS(v.p95_s), 6)}`)
  const h = R.heals
  L.push(
    `\nHeals: ${h.count} started, ${h.solved} solved, ${h.giveups} gave up; ${h.attempts_per_heal.toFixed(1)} fixer attempt(s) per heal`,
  )
  if (Object.keys(h.fixed_at).length)
    L.push(
      '  solved at: ' +
        Object.entries(h.fixed_at)
          .map(([k, v]) => `${k} x${v}`)
          .join(', '),
    )
  const tr = R.triage
  L.push(
    `\nTriage: ${tr.calls} call(s), routes ${pyDict(tr.routes)}, fallbacks ${Object.keys(tr.fallbacks).length ? pyDict(tr.fallbacks) : 'none'}`,
  )
  L.push(
    `  latency p50 ${fmtS(tr.latency_p50_s)}, p95 ${fmtS(tr.latency_p95_s)}; median confidence ${tr.confidence_median.toFixed(2)}; ${tr.fixed_by_a_higher_model_than_it_started_at} heal(s) needed a stronger model than the start`,
  )
  if (Object.keys(tr.start).length)
    L.push(
      '  started at: ' +
        Object.entries(tr.start)
          .map(([k, v]) => `${k} x${v}`)
          .join(', '),
    )
  L.push(`\nCost by rung (total $${R.cost_total_usd})      attempts  fixed   $      time   cache-read`)
  for (const [k, v] of Object.entries<Json>(R.cost)) {
    const cr = v.cache_read_share === null ? '-' : `${(100 * v.cache_read_share).toFixed(0)}%`
    L.push(
      `  ${pad(k, 26)} ${rpad(v.attempts, 8)} ${rpad(v.fixed, 6)} ${rpad(v.usd.toFixed(2), 7)} ${rpad(fmtS(v.secs), 7)} ${rpad(cr, 8)}`,
    )
  }
  if (R.recurring.length) {
    L.push('\nFailures that came back')
    for (const x of R.recurring.slice(0, 6))
      L.push(
        `  ${x.n}x in ${x.runs} run(s), ${x.stage}, healed ${x.healed}x: ${x.head ? String(x.head).split('\n')[0]!.slice(0, 110) : x.sig}`,
      )
  }
  return L.join('\n')
}

export function main(argv: string[]): number {
  const { pos, opt, flag } = parseArgs(argv, { value: ['events', 'since', 'run', 'project'], bool: ['json'] }, 'heal-report report|show')
  const cmd = pos[0]
  if (!['report', 'analyze', 'show'].includes(cmd ?? '')) {
    console.error('usage: heal-report report|show [--events FILE] [--since 7d|DATE] [--run ID] [--project NAME] [--json]')
    return 2
  }
  const events = opt.events ?? process.env.YUKTA_HEAL_EVENTS ?? DEFAULT
  const t0 = since(opt.since)
  const ev = stamp(load(events)).filter(
    (e) =>
      (!t0 || (e.ts ?? '') >= t0) && (!opt.run || String(e.run ?? '').startsWith(opt.run)) && (!opt.project || e.project === opt.project),
  )
  if (!ev.length) {
    console.log(`no heal events in ${events} for this selection (yk check writes them)`)
    return 0
  }
  if (cmd === 'show') {
    for (const e of [...ev].sort((a, b) => ((a.ts ?? '') < (b.ts ?? '') ? -1 : (a.ts ?? '') > (b.ts ?? '') ? 1 : 0)))
      console.log(dumps(e, { sort: true }))
    return 0
  }
  const R = report(ev)
  if (flag.has('json')) console.log(dumps({ ...R, findings: findings(ev).map(([rule, evidence]) => ({ rule, evidence })) }, { indent: 1 }))
  else {
    console.log(text(R))
    const f = findings(ev)
    if (f.length) {
      console.log('\nFindings')
      for (const [r, x] of f) console.log(`  ${r}: ${x}`)
    }
  }
  return 0
}

if (import.meta.main) process.exit(main(process.argv.slice(2)))
