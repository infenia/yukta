// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// Collect real failures of the repository's CI for the triage evaluation (README.md in this directory).
//
//   bun collect.ts [--repo infenia/yukta] [--limit 200] [--out data/cases.json] [--logs DIR]
//
// For every failed run of the Gradle, lint, REUSE and license workflows it keeps what heal's `brief` step would have shown the
// triage model (the stage, the problem lines, the changed files and the first lines of the log) and, as an objective hint of how
// hard the fix was, what happened next on the same branch: how many failed runs it took to get green and how big the change to
// the next green commit was. Needs the `gh` CLI, logged in. Logs are downloaded to --logs (default data/logs) and kept there.
// The output holds log text of your CI: it stays out of git (data/ is ignored); labels.json holds the labels by run id.
import { createHash } from 'node:crypto'
import { spawnSync } from 'node:child_process'
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

// eslint-disable-next-line @typescript-eslint/no-explicit-any
type Json = any
const here = dirname(fileURLToPath(import.meta.url))
const WORKFLOWS = new Set(['Java CI with Gradle', 'License Compliance Check'])
const STEPS = /Gradle build|Spotless|REUSE|licenses|actionlint|Check the title|Run the examples|Fast Lint/i
// eslint-disable-next-line no-control-regex
const ANSI = /(\x1b|\^)?\[[0-9;]*[A-Za-z]/g
// what a CI log has that `yk check` output does not: the environment of the step and the build tool's chatter
const NOISE = /^(##\[(warning|group|endgroup|debug)\]|\s*Did you mean|\s*[A-Za-z_-]+: (true|false|\S+)$|Not performing cache|CI Gate\t|\[Cache cleanup\]|\s*(public|private|protected) )/
// where the failure starts in the log of a step
const START = /FAILED$|What went wrong|: error: |^error: |Rule violated|violations? (were )?found|had format violations|MISSING COPYRIGHT|Unlicensed|^\s*[×✗]|Process completed with exit code/
const PREFIX = /^[^\t]*\t[^\t]*\t\d{4}-\d\d-\d\dT[\d:.]+Z ?/
const PROBLEM = /(^|[^a-z])(error|failed|failure)([^a-z]|$)|exception|see the report at|violation/i
const CACHE = join(here, 'data', 'api-cache')

function gh(args: string[], check = true): string {
  const r = spawnSync('gh', args, { encoding: 'utf8', maxBuffer: 1 << 28 })
  if (check && r.status) throw new Error((r.stderr ?? '').trim())
  return r.stdout ?? ''
}

/** The JSON a gh call prints, kept under data/api-cache so a second collection only asks for what is new. */
function ghJson(...args: string[]): Json | null {
  const key = join(CACHE, createHash('sha1').update(args.join(' ')).digest('hex') + '.json')
  if (existsSync(key)) return JSON.parse(readFileSync(key, 'utf8'))
  let d: Json
  try { d = JSON.parse(gh(args)) } catch { return null }
  mkdirSync(CACHE, { recursive: true })
  writeFileSync(key, JSON.stringify(d))
  return d
}

const clean = (log: string): string[] => log.split('\n').map(l => l.replace(PREFIX, '').replace(ANSI, '').trimEnd())

function stageOf(lines: string[], job: string, step: string): string {
  const text = lines.join('\n')
  const failedTasks = [...text.matchAll(/> Task (\S+) FAILED/g)].map(m => m[1]!)
  // the failed tasks say what failed; the job and step only when there are none (a job named "Spotless, Checkstyle, PMD" is all lint)
  const names = failedTasks.length ? failedTasks.join(' ') : `${step} ${job}`
  const rules: [string, RegExp][] = [['spotless', /spotless/i], ['reuse', /REUSE|reuse lint/i], ['licenses', /licensee|license/i], ['actionlint', /actionlint/i], ['coverage', /jacoco|Rule violated/i],
    ['style', /checkstyle|pmd|opengrep/i], ['spotbugs', /spotbugs/i], ['tests', /:test\b|Test\b|tests completed|integrationTest/i], ['compile', /compile|error: /i]]
  for (const [stage, rx] of rules) if (rx.test(names)) return stage
  for (const [stage, rx] of rules) if (rx.test(text)) return stage
  return 'other'
}

/** The lines of the failing step from where the failure starts, without the environment and tool chatter. */
function failurePart(lines: string[]): string[] {
  const k = lines.findIndex(l => START.test(l))
  return lines.slice(Math.max(0, (k < 0 ? 0 : k) - 2)).filter(l => !NOISE.test(l))
}

function problemLines(lines: string[], n = 25): string[] {
  const out: string[] = []
  let ctx = 0
  for (const l of lines) {
    if (l.includes('What went wrong')) { ctx = 3; out.push(l); continue }
    if (ctx) { ctx--; out.push(l); continue }
    if (PROBLEM.test(l)) out.push(l)
  }
  return [...new Set(out.map(l => l.slice(0, 300)))].slice(0, n)
}

/** The end of the failing step: a build tool reports what failed last. */
const excerpt = (lines: string[], n = 60): string[] => lines.slice(-n).map(l => l.slice(0, 300))

function filesBetween(repo: string, base: string, head: string): Json | null {
  const d = ghJson('api', `repos/${repo}/compare/${base}...${head}`)
  if (!d) return null
  const fs: Json[] = d.files ?? []
  return { files: fs.length, lines: fs.reduce((a, f) => a + (f.additions ?? 0) + (f.deletions ?? 0), 0), list: fs.slice(0, 20).map(f => `${f.filename} (+${f.additions ?? 0} -${f.deletions ?? 0})`), total: d.total_commits }
}

export async function main(argv: string[]): Promise<number> {
  const o: Record<string, string> = {}
  for (let i = 0; i < argv.length; i += 2) o[argv[i]!.replace(/^--/, '')] = argv[i + 1]!
  const repo = o.repo ?? 'infenia/yukta'
  const out = o.out ?? join(here, 'data', 'cases.json')
  const logs = o.logs ?? join(dirname(out), 'logs')
  mkdirSync(logs, { recursive: true })
  const failed: Json[] = ghJson('run', 'list', '-R', repo, '--status', 'failure', '--limit', o.limit ?? '200', '--json', 'databaseId,workflowName,headBranch,headSha,createdAt,url') ?? []
  const allruns: Json[] = []
  for (const wf of [...new Set(failed.map(r => r.workflowName as string).filter(w => WORKFLOWS.has(w)))].sort()) {
    allruns.push(...(ghJson('run', 'list', '-R', repo, '--workflow', wf, '--limit', '1000', '--json', 'databaseId,workflowName,headBranch,headSha,conclusion,createdAt') ?? []))
  }
  const cases: Json[] = []
  for (const r of failed) {
    if (!WORKFLOWS.has(r.workflowName)) continue
    const rid = r.databaseId
    const jobs = ghJson('api', `repos/${repo}/actions/runs/${rid}/jobs`) ?? { jobs: [] }
    const bad: [string, string][] = (jobs.jobs as Json[]).filter(j => j.conclusion === 'failure').map(j => [j.name, (j.steps as Json[]).find(s => s.conclusion === 'failure')?.name ?? ''] as [string, string])
      .filter(([j, s]) => STEPS.test(`${j} ${s}`) && !j.includes('CI Gate'))
    if (!bad.length) continue
    const path = join(logs, `${rid}.txt`)
    if (!existsSync(path)) writeFileSync(path, gh(['run', 'view', String(rid), '-R', repo, '--log-failed'], false))
    let lines = clean(readFileSync(path, 'utf8'))
    if (lines.length < 3) continue
    const [job, step] = bad[0]!
    const stage = stageOf(lines, job, step)
    lines = failurePart(lines)
    const problems = problemLines(lines)
    const mine = filesBetween(repo, 'main', r.headSha)
    const later = allruns.filter(x => x.workflowName === r.workflowName && x.headBranch === r.headBranch && x.createdAt > r.createdAt).sort((a, b) => (a.createdAt < b.createdAt ? -1 : 1))
    const green = later.find(x => x.conclusion === 'success')
    const between = green ? later.slice(0, later.indexOf(green)).filter(x => x.conclusion === 'failure') : []
    const fix = green && green.headSha !== r.headSha ? filesBetween(repo, r.headSha, green.headSha) : null
    const short = [`stage: ${stage}`, `job: ${job} / ${step}`, ...problems, `files changed so far: ${mine ? mine.files : 'unknown'}`].join('\n')
    const long = [`changed files: ${mine && mine.list.length ? mine.list.join(', ') + (mine.files > 20 ? `, ... ${mine.files} files` : '') : 'unknown'}`, 'log:', ...excerpt(lines)].join('\n')
    cases.push({
      id: String(rid), stage, short: short.slice(0, 4000), long: long.slice(0, 8000), source: { run: r.url, branch: r.headBranch, sha: r.headSha, job, step },
      next: { green_after_failed_runs: green ? between.length + 1 : null, fix_files: fix?.files ?? null, fix_lines: fix?.lines ?? null, fix_commits: fix?.total ?? null, fix_list: fix?.list ?? null },
    })
    console.error(`${rid} ${stage.padEnd(10)} ${job} / ${step}`)
  }
  mkdirSync(dirname(out), { recursive: true })
  writeFileSync(out, JSON.stringify(cases, null, 1))
  console.log(`${cases.length} cases -> ${out}`)
  return 0
}

if (import.meta.main) process.exit(await main(process.argv.slice(2)))
