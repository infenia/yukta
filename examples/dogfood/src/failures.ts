// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// Failures of the dogfood or of Yukta itself, captured as they happen and filed as GitHub issues once you approve.
//
//   yk report                 the captured failures, grouped by fingerprint (count, last seen, issue)
//   yk report on|off|status   turn all capture (and the Stop-hook scan) on or off; off = a report.off file next to failures.jsonl, or YUKTA_REPORT=off
//   yk report file [--yes]    draft one issue per unfiled fingerprint; with --yes create it (or comment on the open one) with gh
//
// Captured by yk: a RESULT that is not SUCCESS (runs.ts printResult) and an exception of yk itself (yk.ts). One JSON line per failure in
// $DEV_HOME/failures.jsonl (YUKTA_FAILURES); fingerprint -> issue number in failures-filed.json next to it.
import { createHash } from 'node:crypto'
import { rmSync } from 'node:fs'
import {
  DEV_HOME,
  type Json,
  REPO_ROOT,
  appendFileSync,
  dirname,
  git,
  isFile,
  isoNow,
  join,
  mkdirp,
  parseArgs,
  readFileSync,
  run,
  tryJson,
  writeFileSync,
} from './lib.ts'

const file = (): string => process.env.YUKTA_FAILURES ?? join(DEV_HOME, 'failures.jsonl')
const filedFile = (): string => join(dirname(file()), 'failures-filed.json')
/** Capture is on unless YUKTA_REPORT=off or `yk report off` left report.off next to failures.jsonl. */
export const reportOff = (): boolean => process.env.YUKTA_REPORT === 'off' || isFile(join(dirname(file()), 'report.off'))
const LABELS = 'dogfood,bug'

/** The first line that names an error, with the parts that change from run to run (ids, numbers, paths, times) blanked. */
export function fingerprint(kind: string, where: string, evidence: string): string {
  const ls = evidence
    .split('\n')
    .map((l) => l.trim())
    .filter(Boolean)
  const line = ls.find((l) => /error|exception|fail|expected|refus/i.test(l)) ?? ls.at(-1) ?? ''
  const norm = line
    .replace(/[0-9a-f]{8}-[0-9a-f-]{27}/gi, '<id>')
    .replace(/\/[\w./-]+/g, '<path>')
    .replace(/\d+/g, '<n>')
    .slice(0, 200)
  return createHash('sha1').update(`${kind}\0${where}\0${norm}`).digest('hex').slice(0, 12)
}

/** Appends one failure record; never throws (capturing a failure must not hide it). */
export function recordFailure(f: {
  kind: 'result' | 'crash'
  where: string
  status?: string
  execution?: string
  session?: string
  evidence: string
}): void {
  try {
    if (reportOff()) return
    mkdirp(dirname(file()))
    const sha = git(['rev-parse', '--short', 'HEAD'], REPO_ROOT).stdout.trim()
    const rec = {
      v: 1,
      ts: isoNow(),
      fp: fingerprint(f.kind, f.where, f.evidence),
      ...f,
      evidence: f.evidence.slice(-4000),
      sha,
      argv: process.argv.slice(2).join(' '),
    }
    appendFileSync(file(), JSON.stringify(rec) + '\n')
  } catch {
    /* best effort */
  }
}

const load = (): Json[] =>
  isFile(file())
    ? readFileSync(file(), 'utf8')
        .split('\n')
        .filter(Boolean)
        .map((l) => tryJson(l))
        .filter(Boolean)
    : []
const filed = (): Json => tryJson(isFile(filedFile()) ? readFileSync(filedFile(), 'utf8') : '') ?? {}

function group(): Map<string, Json[]> {
  const g = new Map<string, Json[]>()
  for (const r of load()) g.set(r.fp, [...(g.get(r.fp) ?? []), r])
  return g
}

function draft(fp: string, rs: Json[]): { title: string; body: string } {
  const last = rs.at(-1)!
  const title = `dogfood: ${last.kind === 'crash' ? 'yk crashed' : `${last.where} ${last.status}`} (${
    last.evidence
      .split('\n')
      .find((l: string) => /error|exception|fail/i.test(l))
      ?.trim()
      .slice(0, 80) ?? 'see evidence'
  })`
  const body = [
    `Captured by the dogfood (\`yk report\`), fingerprint dfp${fp}.`,
    '',
    `- kind: ${last.kind}, where: ${last.where}${last.status ? `, status: ${last.status}` : ''}`,
    `- occurrences: ${rs.length} (first ${rs[0]!.ts}, last ${last.ts})`,
    `- sha ${last.sha}, command \`yk ${last.argv}\`${last.execution ? `, execution ${last.execution}, session ${last.session}` : ''}`,
    '',
    '<details><summary>Evidence (tail)</summary>',
    '',
    '```',
    last.evidence.replaceAll('```', "'''"),
    '```',
    '</details>',
  ].join('\n')
  return { title, body }
}

function gh(args: string[]): { ok: boolean; out: string } {
  const r = run(['gh', ...args], { cwd: REPO_ROOT, timeoutMs: 60000 })
  return { ok: r.code === 0, out: (r.stdout + r.stderr).trim() }
}

export function main(argv: string[]): number {
  const { pos, flag } = parseArgs(argv, { bool: ['yes'] }, 'yk report [file [--yes]]')
  if (pos[0] === 'off' || pos[0] === 'on') {
    const f = join(dirname(file()), 'report.off')
    if (pos[0] === 'off') {
      mkdirp(dirname(f))
      writeFileSync(f, '')
    } else rmSync(f, { force: true })
    console.log(`failure reporting ${pos[0]}`)
    return 0
  }
  if (pos[0] === 'status') {
    console.log(`failure reporting ${reportOff() ? 'off' : 'on'}; ${[...group().keys()].filter((k) => !filed()[k]).length} unfiled`)
    return 0
  }
  const g = group()
  if (!g.size) {
    console.log('no failures captured')
    return 0
  }
  const done = filed()
  if (!pos[0]) {
    for (const [fp, rs] of g)
      console.log(`${fp}  x${rs.length}  ${rs.at(-1)!.ts}  ${rs.at(-1)!.where}  ${done[fp] ? `#${done[fp]}` : 'unfiled'}`)
    return 0
  }
  if (pos[0] !== 'file') {
    console.error('usage: yk report [file [--yes] | on | off | status]')
    return 2
  }
  let code = 0
  for (const [fp, rs] of g) {
    const issue = done[fp]
    const { title, body } = draft(fp, rs)
    // a repeat of a filed fingerprint only adds a comment, and only when there are new occurrences
    if (issue && (done[`${fp}:n`] ?? 0) >= rs.length) continue
    if (!flag.has('yes')) {
      console.log(`--- DRAFT ${fp}${issue ? ` (comment on #${issue})` : ''}\n${title}\n\n${body}\n`)
      continue
    }
    const found = issue
      ? { number: issue, state: 'OPEN' }
      : (tryJson(
          gh(['issue', 'list', '--label', 'dogfood', '--state', 'all', '--search', `dfp${fp}`, '--json', 'number,state', '--limit', '1'])
            .out,
        ) ?? [])[0]
    if (found?.state === 'OPEN') {
      const r = gh([
        'issue',
        'comment',
        String(found.number),
        '--body',
        `Again: ${rs.length} occurrence(s), last ${rs.at(-1)!.ts}, sha ${rs.at(-1)!.sha}.`,
      ])
      if (r.ok) {
        done[fp] = found.number
        done[`${fp}:n`] = rs.length
        console.log(`commented on #${found.number}`)
      } else {
        code = 1
        console.error(r.out)
      }
      continue
    }
    const reg = found ? `\n\nRegression of #${found.number} (closed).` : ''
    const r = gh(['issue', 'create', '--title', title, '--body', body + reg, '--label', LABELS])
    const n = /\/issues\/(\d+)/.exec(r.out)?.[1]
    if (r.ok && n) {
      done[fp] = Number(n)
      done[`${fp}:n`] = rs.length
      console.log(`filed #${n}: ${title}`)
    } else {
      code = 1
      console.error(r.out)
    }
  }
  if (flag.has('yes')) writeFileSync(filedFile(), JSON.stringify(done, null, 1) + '\n')
  return code
}

/** Fingerprints captured and not yet filed. */
export const unfiled = (): number => [...group().keys()].filter((k) => !filed()[k]).length
