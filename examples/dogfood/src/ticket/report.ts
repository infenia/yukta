// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// ticketkit report [--events FILE | --run RUN_DIR]... [--json] (design 11 tuning loop, 11.1, 11.3)
//
// From ticket-events.jsonl (default $YUKTA_TICKET_EVENTS, else ~/.local/state/yukta-dev/ticket-events.jsonl):
//   first-try pass rate and mean attempts by (model, effort, complexity)   the tuning loop: Haiku below 70% on standard steps -> Sonnet/medium
//   cost share by role, cache hit ratio by role                            architect + test_writer above half the cost on M/L -> revisit tests_mode
//   prefix_hash mismatches                                                 two different prefixes in one run without a revision in between: a bug
//   BLOCKED rate                                                           above 15% the steps are too small: retune the sizing lint (design 11.3)
import { homedir } from 'node:os'
import { type Json, dumps, isFile, join, readFileSync } from './lib.ts'

const BLOCKED_LIMIT = 0.15
const r2 = (x: number): number => Math.round(x * 100) / 100
const pct = (x: number): string => `${Math.round(x * 100)}%`

export function read(paths: string[]): Json[] {
  return paths.filter(isFile).flatMap((p) =>
    readFileSync(p, 'utf8')
      .split('\n')
      .filter((l) => l.trim())
      .map((l) => JSON.parse(l)),
  )
}

export function build(events: Json[]): Json {
  const runs = new Map<string, Json[]>()
  for (const e of events) runs.set(e.run_id, [...(runs.get(e.run_id) ?? []), e])
  type Outcome = { ok: boolean; blocked?: boolean; key: [unknown, unknown, unknown] }
  const attempts = new Map<string, Map<number, Outcome>>() // run\0step -> {attempt: outcome}
  for (const [rid, evs] of runs) {
    for (const e of evs) {
      const key: [unknown, unknown, unknown] = [e.model, e.effort, e.complexity]
      const k = `${rid}\0${e.step_id}`
      if (e.role === 'validate') (attempts.get(k) ?? attempts.set(k, new Map()).get(k)!).set(e.attempt, { ok: e.status === 'pass', key })
      else if (e.role === 'decide' && e.fail_class === 'blocked_reported')
        (attempts.get(k) ?? attempts.set(k, new Map()).get(k)!).set(e.attempt, { ok: false, blocked: true, key })
    }
  }
  const byKey = new Map<string, { key: [unknown, unknown, unknown]; steps: number; first_try: number; attempts: number }>()
  let nAtt = 0
  let nBlocked = 0
  for (const att of attempts.values()) {
    const first = att.get(1) ?? att.get(Math.min(...att.keys()))!
    const id = first.key.map(String).join('\0')
    const k = byKey.get(id) ?? byKey.set(id, { key: first.key, steps: 0, first_try: 0, attempts: 0 }).get(id)!
    k.steps++
    k.first_try += first.ok ? 1 : 0
    k.attempts += att.size
    nAtt += att.size
    nBlocked += [...att.values()].filter((a) => a.blocked).length
  }
  const cost: Record<string, number> = {}
  const cache: Record<string, [number, number]> = {}
  for (const e of events) {
    if (e.cost_usd) cost[e.role] = (cost[e.role] ?? 0) + e.cost_usd
    if ('cache_read_tokens' in e) {
      const c = (cache[e.role] ??= [0, 0])
      c[0] += e.cache_read_tokens
      c[1] += e.cache_read_tokens + (e.cache_write_tokens ?? 0) + (e.tokens_in ?? 0)
    }
  }
  const mismatch: Json[] = []
  for (const [rid, evs] of runs) {
    const hashes = new Set(evs.filter((e) => e.prefix_hash).map((e) => e.prefix_hash))
    const revisions = evs.filter((e) => e.role === 'architect_revise').length
    if (hashes.size > 1 + revisions) mismatch.push({ run_id: rid, prefixes: hashes.size, revisions })
  }
  const total = Object.values(cost).reduce((a, b) => a + b, 0)
  const sortedKeys = (o: Record<string, unknown>): string[] => Object.keys(o).sort()
  return {
    runs: runs.size,
    steps: attempts.size,
    by_model: [...byKey.values()]
      .sort((a, b) => a.key.map(String).join('\0').localeCompare(b.key.map(String).join('\0')))
      .map((v) => ({
        model: v.key[0],
        effort: v.key[1],
        complexity: v.key[2],
        steps: v.steps,
        first_try: v.first_try,
        attempts: v.attempts,
        first_try_rate: r2(v.first_try / v.steps),
        mean_attempts: r2(v.attempts / v.steps),
      })),
    cost_usd: Math.round(total * 10000) / 10000,
    cost_share: total ? Object.fromEntries(sortedKeys(cost).map((r) => [r, r2(cost[r]! / total)])) : {},
    cache_hit: Object.fromEntries(sortedKeys(cache).map((r) => [r, cache[r]![1] ? r2(cache[r]![0] / cache[r]![1]) : null])),
    prefix_mismatches: mismatch,
    blocked_rate: nAtt ? r2(nBlocked / nAtt) : 0.0,
    blocked_too_small: Boolean(nAtt && nBlocked / nAtt > BLOCKED_LIMIT),
  }
}

export function text(r: Json): string {
  const L = [
    `ticket report: ${r.runs} runs, ${r.steps} steps, cost $${r.cost_usd.toFixed(2)}`,
    '',
    'first-try pass rate and mean attempts by (model, effort, complexity):',
  ]
  L.push(
    ...(r.by_model.length
      ? r.by_model.map(
          (m: Json) =>
            `  ${m.model}/${m.effort}/${m.complexity}: ${m.steps} steps, first try ${pct(m.first_try_rate)}, ${m.mean_attempts} attempts`,
        )
      : ['  no steps yet']),
  )
  const share = Object.entries(r.cost_share)
    .map(([k, v]) => `${k} ${pct(v as number)}`)
    .join(', ')
  const hit = Object.entries(r.cache_hit)
    .map(([k, v]) => `${k} ${v === null ? '-' : pct(v as number)}`)
    .join(', ')
  L.push('', 'cost share by role: ' + (share || '-'), 'cache hit ratio by role: ' + (hit || '-'))
  L.push(
    'prefix_hash mismatches: ' +
      (r.prefix_mismatches.map((m: Json) => `${m.run_id} (${m.prefixes} prefixes, ${m.revisions} revisions)`).join(', ') || 'none'),
  )
  L.push(
    `BLOCKED rate: ${pct(r.blocked_rate)}` + (r.blocked_too_small ? ' (above 15%: the steps are too small; retune the sizing lint)' : ''),
  )
  for (const m of r.by_model) {
    if (m.model === 'haiku' && m.complexity === 'standard' && m.first_try_rate < 0.7 && m.steps >= 5)
      L.push('tuning: haiku first-try pass rate on standard steps is below 70%: make Sonnet/medium the default for them')
  }
  return L.join('\n')
}

export function main(argv: string[]): number {
  const events: string[] = []
  const runs: string[] = []
  let json = false
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--events') events.push(argv[++i]!)
    else if (argv[i] === '--run') runs.push(argv[++i]!)
    else if (argv[i] === '--json') json = true
  }
  let paths = [...events, ...runs.map((r) => join(r, 'events.jsonl'))]
  if (!paths.length)
    paths = [
      process.env.YUKTA_TICKET_EVENTS ||
        join(process.env.YUKTA_DEV_HOME ?? join(homedir(), '.local/state/yukta-dev'), 'ticket-events.jsonl'),
    ]
  const r = build(read(paths))
  console.log(json ? dumps(r, { indent: 1 }) : text(r))
  return 0
}
