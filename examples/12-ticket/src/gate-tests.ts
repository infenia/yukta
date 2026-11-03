// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// ticketkit gate-tests RUN_DIR [--worktree W] [--runner CMD --report FORMAT:PATH]  (design 3.3, 8.1b, 15.6)
//
// After the test writer: applies the scaffold stubs (once), checks that only stubs and locked test files changed, that every case has a
// [T#] tagged test and no tag is unknown, that the tagged tests compile and each fails with an assertion or "not implemented" (never a
// setup, import or syntax error), and that no test is trivial. Then it locks the test files (spec/locked.json: sha256) and writes
// spec/test_map.json (case -> tests, built from the report, never from the agent's claim).
// tests_mode existing and implementer_writes have no red check up front (exit 0, SKIP). Errors go to stdout and spec/gate.json; exit 1.
//
// --runner CMD (shell, run in the worktree) and --report junit:DIR | vitest:FILE | gotest:FILE | gotest:stdout override the per-ecosystem
// defaults (a Gradle, Vitest or Go run through yk, only the tagged test files).
import { createHash } from 'node:crypto'
import { rmSync } from 'node:fs'
import { type Json, git, isDir, isFile, join, load, moduleOf, parseArgs, readFileSync, readdirSync, resolve, save, sh } from './lib.ts'
import { junitCases } from './junit.ts'

const TAG = /\[(T\d+)\]/g
const TRIVIAL: [RegExp, string][] = [
  [/assert(True|That)\(\s*true\s*\)(\.isTrue\(\))?|assertEquals\(\s*(\w+)\s*,\s*\3\s*\)|expect\(\s*true\s*\)\.toBe\(\s*true\s*\)/g, 'always-true assertion'],
  [/@Disabled|\bxit\(|\bxdescribe\(|\b(it|test|describe)\.skip\(|\bt\.Skip\(/g, 'skipped test'],
  [/(void\s+\w+\([^)]*\)\s*(throws\s+[\w., ]+)?\{\s*\}|=>\s*\{\s*\}\s*\)|func\(t \*testing\.T\)\s*\{\s*\})/g, 'empty test body'],
]
const SETUP = /ExceptionInInitializerError|NoClassDefFoundError|ClassNotFoundException|NoSuchMethodError|SyntaxError|Cannot find module|Failed to resolve import|ReferenceError|is not defined|cannot find package|undefined:|build failed|setup failed/i
const RED = /AssertionError|AssertionFailedError|ComparisonFailure|expected|not implemented|UnsupportedOperationException|NotImplemented|toBe|toEqual|Mismatch|got .* want/i

type T = { name: string; status: 'pass' | 'fail' | 'skipped'; message: string; error_elem: boolean }
const tags = (s: string): string[] => [...new Set([...s.matchAll(TAG)].map(m => m[1]!))]

function parseJunit(dir: string): T[] {
  return junitCases(dir).map(c => ({
    name: c.name,
    status: c.skipped ? 'skipped' : c.bad ? 'fail' : 'pass',
    message: c.bad ? `${c.bad.type} ${c.bad.message} ${c.bad.text.slice(0, 400)}` : '',
    error_elem: c.bad?.kind === 'error',
  }))
}

function parseVitest(file: string): T[] {
  const out: T[] = []
  for (const fr of JSON.parse(readFileSync(file, 'utf8')).testResults ?? []) {
    for (const t of fr.assertionResults ?? []) {
      out.push({ name: t.fullName || t.title || '', status: t.status === 'passed' ? 'pass' : t.status === 'failed' ? 'fail' : 'skipped', message: (t.failureMessages ?? []).join(' ').slice(0, 400), error_elem: false })
    }
  }
  return out
}

function parseGotest(text: string): T[] {
  const tests = new Map<string, T>()
  const msgs: Record<string, string> = {}
  for (const line of text.split('\n')) {
    let e: Json
    try { e = JSON.parse(line) } catch { continue }
    const n = e?.Test
    if (!n) continue
    if (e.Action === 'output') msgs[n] = (msgs[n] ?? '') + e.Output
    else if (['pass', 'fail', 'skip'].includes(e.Action)) {
      tests.set(n, { name: n, status: e.Action === 'pass' ? 'pass' : e.Action === 'fail' ? 'fail' : 'skipped', message: (msgs[n] ?? '').slice(0, 400), error_elem: (msgs[n] ?? '').includes('panic:') })
    }
  }
  return [...tests.values()]
}

const fqcn = (path: string): string | undefined => /src\/test\/java\/(.+)\.java$/.exec(path)?.[1]?.replaceAll('/', '.')

/** [runner, report] per ecosystem of the locked files. */
function defaultRuns(locked: string[]): [string, string][] {
  const runs: [string, string][] = []
  const java = locked.filter(f => f.endsWith('.java'))
  for (const mod of [...new Set(java.map(moduleOf))].sort()) {
    const tests = java.filter(f => moduleOf(f) === mod).map(fqcn).filter(Boolean).map(c => `--tests ${c}`).join(' ')
    runs.push([`yk gradle :${mod.replaceAll('/', ':')}:test ${tests}`, `junit:${mod}/build/test-results/test`])
  }
  const ui = locked.filter(f => f.startsWith('ui/') && /\.(test|spec)\.tsx?$/.test(f)).map(f => f.slice(3))
  if (ui.length) runs.push(['yk tool -C ui npx --no-install vitest run --reporter=json --outputFile=node_modules/.cache/ticket-gate.json ' + ui.join(' '), 'vitest:ui/node_modules/.cache/ticket-gate.json'])
  if (locked.some(f => f.startsWith('cli/') && f.endsWith('_test.go'))) runs.push(['yk tool -C cli go test -json ./...', 'gotest:stdout'])
  return runs
}

function execute(wt: string, runner: string, report: string, errors: string[]): T[] {
  const i = report.indexOf(':')
  const fmt = report.slice(0, i)
  const path = report.slice(i + 1)
  const target = join(wt, path)
  if (fmt === 'junit') {
    if (isDir(target)) for (const f of readdirSync(target).filter(n => /^TEST-.*\.xml$/.test(n))) rmSync(join(target, f))
  } else if (!(fmt === 'gotest' && path === 'stdout')) rmSync(target, { force: true })
  const r = sh(runner, wt)
  let tests: T[]
  try {
    tests = fmt === 'junit' ? parseJunit(target) : fmt === 'vitest' ? parseVitest(target) : parseGotest(path === 'stdout' ? r.stdout : readFileSync(target, 'utf8'))
  } catch {
    tests = []
  }
  if (!tests.length) {
    const tail = (r.stdout + r.stderr).trim().split('\n').slice(-6).join(' | ').slice(0, 600)
    errors.push(`compile_or_setup: no test report from \`${runner}\` (exit ${r.code}): ${tail}`)
  }
  return tests
}

export function main(argv: string[]): number {
  const { pos, opt } = parseArgs(argv, { value: ['worktree', 'runner', 'report'] })
  const run = pos[0]!
  const wt = resolve(opt.worktree ?? '.')
  const spec = load(join(run, 'spec', 'spec.json'))
  const errors: string[] = []
  const warnings: string[] = []
  const mode: string = spec.tests_mode
  if (mode === 'existing' || mode === 'implementer_writes') {
    console.log(`SKIP gate-tests: tests_mode ${mode} has no red check up front`)
    save(join(run, 'spec', 'gate.json'), { skipped: mode, errors: [] })
    return 0
  }
  const locked: string[] = spec.locked_files
  // apply the stubs once; a patch that is already applied (reverse applies) is left alone
  const sp = join(run, spec.scaffold_patch)
  const scaffold = new Set<string>(isFile(sp) ? [...readFileSync(sp, 'utf8').matchAll(/^\+\+\+ b\/(\S+)/gm)].map(m => m[1]!) : [])
  if (scaffold.size && git(['apply', '--check', '-R', sp], { cwd: wt, check: false }).code) {
    const r = git(['apply', sp], { cwd: wt, check: false })
    if (r.code) errors.push(`scaffold does not apply: ${r.stderr.trim().slice(0, 200)}`)
  }
  const tp = join(run, 'spec', 'tests.patch')
  if (mode === 'architect_code' && isFile(tp) && git(['apply', '--check', '-R', tp], { cwd: wt, check: false }).code) git(['apply', tp], { cwd: wt, check: false })
  // only stubs and locked test files may differ from HEAD
  const st = git(['status', '--porcelain', '--untracked-files=all'], { cwd: wt }).stdout.split('\n').filter(l => l.length)
  const changed = new Set(st.map(l => l.slice(3).split(' -> ').pop()!))
  for (const f of [...changed].filter(f => !scaffold.has(f) && !locked.includes(f)).sort()) {
    if (!/(^|\/)(build|node_modules|\.gradle|\.yukta-dev)\//.test(f)) errors.push(`scope_violation: ${f} was changed; only stubs and locked test files may change in this phase`)
  }
  for (const f of locked) if (!isFile(join(wt, f))) errors.push(`locked test file ${f} does not exist`)
  // tags
  const cases = new Set<string>(spec.test_cases.map((c: Json) => c.id))
  const taggedInSrc: Record<string, string[]> = {}
  for (const f of locked) {
    const p = join(wt, f)
    if (!isFile(p)) continue
    const text = readFileSync(p, 'utf8')
    for (const t of tags(text)) (taggedInSrc[t] ??= []).push(f)
    for (const [rx, why] of TRIVIAL) for (const m of text.matchAll(rx)) errors.push(`trivial test (${why}) in ${f}:${text.slice(0, m.index).split('\n').length}`)
  }
  for (const c of [...cases].filter(c => !(c in taggedInSrc)).sort()) errors.push(`case ${c} has no tagged test`)
  for (const t of Object.keys(taggedInSrc).filter(t => !cases.has(t)).sort()) errors.push(`tag [${t}] in [${taggedInSrc[t]!.map(f => `'${f}'`).join(', ')}] is not a case of the spec`)
  // run and red check
  if (!errors.length) {
    const runs: [string, string][] = opt.runner ? [[opt.runner, opt.report ?? '']] : defaultRuns(locked)
    const tests: T[] = []
    for (const [runner, report] of runs) tests.push(...execute(wt, runner, report, errors))
    const byCase: Record<string, T[]> = {}
    for (const t of tests) for (const c of tags(t.name)) (byCase[c] ??= []).push(t)
    for (const t of tests) if (!tags(t.name).length) warnings.push(`untagged test: ${t.name}`)
    if (!errors.length) {
      for (const c of [...cases].sort()) {
        const ts = byCase[c]
        if (!ts) { errors.push(`case ${c}: tagged in source but not in the test report (not run)`); continue }
        for (const t of ts) {
          if (t.status === 'pass') errors.push(`case ${c}: \`${t.name}\` passes before any implementation (not red)`)
          else if (t.status === 'skipped') errors.push(`case ${c}: \`${t.name}\` is skipped`)
          else if (SETUP.test(t.message) || (t.error_elem && !RED.test(t.message))) errors.push(`case ${c}: \`${t.name}\` fails with a setup, import or syntax error, not an assertion: ${t.message.slice(0, 160).trim()}`)
          else if (!RED.test(t.message)) warnings.push(`case ${c}: \`${t.name}\` fails for an unrecognised reason: ${t.message.slice(0, 120).trim()}`)
        }
      }
      save(join(run, 'spec', 'test_map.json'), Object.fromEntries([...cases].sort().map(c => [c, (byCase[c] ?? []).map(t => t.name)])))
    }
  }
  if (!errors.length) save(join(run, 'spec', 'locked.json'), Object.fromEntries([...locked].sort().map(f => [f, createHash('sha256').update(readFileSync(join(wt, f))).digest('hex')])))
  save(join(run, 'spec', 'gate.json'), { errors, warnings })
  for (const e of errors) console.log('ERROR ' + e)
  for (const w of warnings) console.log('WARN ' + w)
  console.log(`RESULT gate-tests errors=${errors.length} warnings=${warnings.length}`)
  return errors.length ? 1 : 0
}
