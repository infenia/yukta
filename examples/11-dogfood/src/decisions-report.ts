// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// yk stats --decisions: how the decision model did where it ran in shadow, from .yukta-dev/decisions.tsv (see decisionLog in lib.ts).
//
//   decisions-report TSV DECISIONS-JSON
//
// A decision row is: ts, "decision", name, hash, mode, label, probability, ms. An outcome row is: ts, "outcome", name, hash, -, real label, -, -.
// They join on (name, hash); the last outcome wins. Per decision it prints how many were made and joined, the agreement with the real outcome,
// the mean latency, a calibration table (predicted probability bucket against the observed agreement) and whether the bar written for the
// decision in decisions.json ("bar": {"minN", "minAgreement"}) is met; a decision that does not meet it stays in shadow.
import { type Json, isFile, readFileSync, readJson } from './lib.ts'

const BUCKETS: [number, number][] = [[0.0, 0.5], [0.5, 0.7], [0.7, 0.9], [0.9, 1.01]]
const pct = (x: number): string => `${Math.round(x * 100)}%`

export function report(tsv: string, cfg: string): string[] {
  const bars: Record<string, Json> = Object.fromEntries(Object.entries<Json>(readJson(cfg).decisions ?? {}).map(([k, v]) => [k, v.bar]))
  const decisions: string[][] = []
  const outcomes = new Map<string, string>()
  if (isFile(tsv)) {
    for (const line of readFileSync(tsv, 'utf8').split('\n')) {
      const r = line.split('\t')
      if (r.length < 8) continue
      if (r[1] === 'decision') decisions.push(r)
      else if (r[1] === 'outcome') outcomes.set(`${r[2]}\0${r[3]}`, r[5]!)
    }
  }
  if (!decisions.length) return ['no decisions recorded yet (they are made in shadow mode by the steps that use the decision model)']
  const by = new Map<string, string[][]>()
  for (const r of decisions) by.set(r[2]!, [...(by.get(r[2]!) ?? []), r])
  const out: string[] = []
  for (const [name, rows] of [...by].sort(([a], [b]) => (a < b ? -1 : 1))) {
    const joined = rows.filter(r => outcomes.has(`${name}\0${r[3]}`)).map(r => [r, outcomes.get(`${name}\0${r[3]}`)!] as const)
    const agree = joined.filter(([r, o]) => r[5] === o).length
    const ms = rows.map(r => r[7]!).filter(x => /^\d+(\.\d*)?$|^\.\d+$/.test(x)).map(Number)
    const rate = joined.length ? agree / joined.length : 0
    out.push(ms.length
      ? `${name}: ${rows.length} decisions, ${joined.length} with an outcome, agreement ${agree}/${joined.length}${joined.length ? ` = ${pct(rate)}` : ''}, mean ${(ms.reduce((a, b) => a + b, 0) / ms.length).toFixed(0)} ms`
      : `${name}: ${rows.length} decisions`)
    for (const [lo, hi] of BUCKETS) {
      const b = joined.filter(([r]) => lo <= Number(r[6]) && Number(r[6]) < hi)
      if (b.length) {
        const k = b.filter(([r, o]) => r[5] === o).length
        out.push(`  p ${lo.toFixed(1)}-${Math.min(hi, 1.0).toFixed(1)}: ${b.length} decisions, right ${k} (${pct(k / b.length)})`)
      }
    }
    out.push(`  routing by it would have differed from what happened in ${joined.length - agree} of ${joined.length} cases`)
    const bar = bars[name]
    if (bar) {
      const ok = joined.length >= bar.minN && rate >= bar.minAgreement
      out.push(`  bar (n >= ${bar.minN}, agreement >= ${pct(bar.minAgreement)}): ${ok ? 'met, may be promoted to on' : 'not met, stays in shadow'}`)
    } else out.push('  no bar in decisions.json: stays in shadow')
  }
  return out
}

export function main(argv: string[]): number {
  console.log(report(argv[0]!, argv[1]!).join('\n'))
  return 0
}

if (import.meta.main) process.exit(main(process.argv.slice(2)))
