// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// Evaluate the triage prompt of heal.json against labelled failures (README.md in this directory).
//
//   bun eval.ts [--cases data/cases.json --labels labels.json] [--variant heal.json ...] [--split dev|test] [--min-confidence low|medium|high] [--model M] [--misses] [--json OUT]
//
// A case is {id, stage, short, long}; a label is {level, basis, by, confidence, set} (`set` is dev or test: tune on dev, report on test) or {exclude: why}.
// Fixtures with the labels inside the case (`want`) work too: --cases fixtures/synthetic-b.json.
// A variant is a heal.json, or a JSON file with {question, levels: {name: description}, state: "short" | "brief", caps, from7, collapse}:
// state "short" (the default) is what heal's brief gives the model, the problem lines; "brief" adds the changed files and the end of the log
// (caps: how many characters of each). A variant with other levels than the seven of labels.json says how the labels map onto its own
// (from7: label to level) and onto the 3 common classes easy, average and hard (collapse: its level to class), which every variant is also
// scored on. Prints, per variant, the exact match, the match within one level (a miss by one level costs one extra attempt), misses by two
// levels or more in each direction, what the policy would do (a probability under minProbability falls back to the default level), the
// match on the 3 classes and the time per call. OLLAMA_URL picks the server (default http://localhost:11434).
import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { basename, dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

// eslint-disable-next-line @typescript-eslint/no-explicit-any
type Json = any
const here = dirname(fileURLToPath(import.meta.url))
const LEVELS = ['trivial', 'easy', 'simple', 'moderate', 'average', 'hard', 'veryhard']
const ORDER = ['low', 'medium', 'high']
// the 3 classes every variant is also scored on, so variants with other levels compare: easy (trivial..simple), average (moderate, average), hard
const COLLAPSE7: Record<string, string> = { trivial: 'easy', easy: 'easy', simple: 'easy', moderate: 'average', average: 'average', hard: 'hard', veryhard: 'hard' }
// What heal's `brief` keeps (src/heal.ts, keep them equal): the problem lines, 1000 characters. A variant file may ask for more.
const CAPS = { short: 1000, changed: 600, total: 5000 }

/** The line heal's brief adds (src/heal.ts ecosystem()): what the stage checks, from its name. */
export function ecosystem(stage?: string): string {
  const s = stage ?? ''
  if (s.startsWith('cli_')) return 'go (cli)'
  if (s.startsWith('ui_')) return 'typescript (ui)'
  return ['reuse', 'actionlint', 'docs'].includes(s) ? 'repo files' : 'java (gradle)'
}

/** The 3 common classes of a variant's levels: the seven names as they are, else a variant with three levels is its own. */
function collapseOf(levels: string[]): Record<string, string> {
  if (levels.every(k => k in COLLAPSE7)) return COLLAPSE7
  return levels.length === 3 ? Object.fromEntries(levels.map(k => [k, k])) : {}
}

function loadVariant(path: string, heal: Json): Json {
  const d = JSON.parse(readFileSync(path, 'utf8'))
  const t = d.triage ?? d
  const levels: Record<string, string> = Object.fromEntries(Object.entries<Json>(t.levels).map(([k, v]) => [k, typeof v === 'object' ? v.describe : v]))
  return {
    name: basename(path), question: t.question ?? 'How hard is it to fix this failed check of a Java build?', levels, state: d.state ?? 'short', model: t.model ?? heal.model, caps: d.caps ?? null,
    ecosystem: Boolean(d.ecosystem), from7: d.from7 ?? t.from7, collapse: d.collapse ?? collapseOf(Object.keys(levels)), floor: t.minProbability ?? heal.minProbability, default: t.default ?? heal.default,
  }
}

function briefState(c: Json, caps = CAPS): string {
  const short = (c.short as string).slice(0, caps.short)
  const i = (c.long as string).indexOf('\nlog:\n')
  const changed = i < 0 ? c.long : c.long.slice(0, i)
  const log: string = i < 0 ? '' : c.long.slice(i + '\nlog:\n'.length)
  const top = `${short}\n---\n${changed.slice(0, caps.changed)}\nlog (the end):\n`
  const room = Math.max(0, caps.total - top.length - 1)
  return top + (room ? log.slice(-room) : '')
}

async function ask(url: string, model: string, state: string, question: string, levels: Record<string, string>): Promise<[string, number]> {
  const r = await fetch(`${url}/v1/systemone`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, signal: AbortSignal.timeout(600000),
    body: JSON.stringify({ model, state, questions: { q: { type: 'choice', instructions: question, criteria: levels } } }),
  })
  if (!r.ok) throw new Error(`HTTP ${r.status}`)
  const a = ((await r.json()) as Json).answers.q
  return [a.choice, Math.max(...(Object.values(a.probabilities) as number[]))]
}

const pad = (s: string | number, n: number): string => String(s).padEnd(n)
const rpad = (s: string | number, n: number): string => String(s).padStart(n)

export async function main(argv: string[]): Promise<number> {
  const o: Record<string, string> = {}
  const variants: string[] = []
  let misses = false
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i]!
    if (a === '--misses') misses = true
    else if (a === '--variant') variants.push(argv[++i]!)
    else if (a.startsWith('--')) o[a.slice(2)] = argv[++i]!
  }
  if (o.split && !['dev', 'test'].includes(o.split)) { console.error('--split is dev or test'); return 2 }
  const heal = JSON.parse(readFileSync(resolve(here, '..', '..', 'heal.json'), 'utf8')).triage
  const cases: Json[] = JSON.parse(readFileSync(o.cases ?? join(here, 'data', 'cases.json'), 'utf8'))
  const labelsFile = o.labels ?? join(here, 'labels.json')
  const labels: Json = existsSync(labelsFile) ? JSON.parse(readFileSync(labelsFile, 'utf8')) : {}
  const rows: [Json, string][] = []
  for (const c of cases) {
    const lab = labels[c.id] ?? {}
    const want = lab.level || c.want
    if (lab.exclude || !want || !LEVELS.includes(want) || (o.split && (lab.set ?? 'dev') !== o.split)) continue
    if (o['min-confidence'] && ORDER.indexOf(lab.confidence ?? 'high') < ORDER.indexOf(o['min-confidence'])) continue
    rows.push([c, want])
  }
  if (!rows.length) { console.error('no labelled case (labels.json holds the labels by case id; see README.md)'); return 1 }
  const url = process.env.OLLAMA_URL ?? 'http://localhost:11434'
  console.log(`${rows.length} labelled case(s), model ${o.model ?? heal.model}`)
  console.log(`${pad('variant', 26)} exact  within1  under2  over2  policy-exact  3-class  latency`)
  const out: Json = {}
  for (const path of variants.length ? variants : [resolve(here, '..', '..', 'heal.json')]) {
    const v = loadVariant(path, heal)
    const model = o.model ?? v.model
    let ex = 0, w1 = 0, un = 0, ov = 0, pol = 0, errors = 0, c3 = 0
    const order = Object.keys(v.levels)
    const res: Json[] = []
    const secs: number[] = []
    for (const [c, want7] of rows) {
      let state: string = v.state === 'short' ? (c.short as string).slice(0, (v.caps ?? CAPS).short) : briefState(c, v.caps ?? CAPS)
      if (v.ecosystem) state = `ecosystem: ${ecosystem(c.stage)}\n${state}`
      let want: string = (v.from7 ?? {})[want7] ?? want7
      if (!order.includes(want)) { // a variant with fewer levels and no mapping: the nearest of its levels
        want = order.reduce((best, l) => (dist(l, want7) < dist(best, want7) ? l : best))
      }
      const t0 = Date.now()
      let ch: string, p: number
      try { [ch, p] = await ask(url, model, state, v.question, v.levels) } catch (e) { // a failed call is what heal sees too: the default level, and a miss here
        errors++; ch = 'error'; p = 0
        console.error(`   error ${c.id}: ${(e as Error).message}`)
      }
      secs.push((Date.now() - t0) / 1000)
      if (ch === 'error') { res.push({ id: c.id, stage: c.stage, want, got: 'error', p: 0, policy: v.default }); pol += v.default === want ? 1 : 0; continue }
      const i = order.indexOf(ch), j = order.indexOf(want)
      ex += i === j ? 1 : 0; w1 += Math.abs(i - j) <= 1 ? 1 : 0; un += j - i >= 2 ? 1 : 0; ov += i - j >= 2 ? 1 : 0
      const pick = p >= v.floor ? ch : v.default
      pol += order.indexOf(pick) === j ? 1 : 0
      c3 += v.collapse[ch] === COLLAPSE7[want7] ? 1 : 0
      res.push({ id: c.id, stage: c.stage, want, got: ch, p: Math.round(p * 100) / 100, policy: pick })
    }
    const n = rows.length
    console.log(`${pad(v.name, 26)} ${rpad(ex, 3)}/${n}  ${rpad(w1, 4)}/${n}  ${rpad(un, 6)}  ${rpad(ov, 5)}  ${rpad(pol, 7)}/${n}  ${rpad(c3, 5)}/${n}   ${rpad((secs.reduce((a, b) => a + b, 0) / secs.length).toFixed(1), 5)}s/call, ${errors} failed`)
    out[v.name] = res
    if (misses) for (const r of res) if (r.got !== r.want) console.log(`   miss ${rpad(r.id, 12)} ${pad(String(r.stage), 10)} want ${pad(r.want, 9)} got ${pad(r.got, 9)} p=${r.p}`)
  }
  if (o.json) writeFileSync(o.json, JSON.stringify(out, null, 1))
  return 0
}
const dist = (l: string, w: string): number => (LEVELS.includes(l) ? Math.abs(LEVELS.indexOf(l) - LEVELS.indexOf(w)) : 99)

if (import.meta.main) process.exit(await main(process.argv.slice(2)))
