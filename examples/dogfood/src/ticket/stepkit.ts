// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// Per-step tools of ticketkit (design 5.2, 5.4, 8.2, 11.1, 15.5, 15.7). One subcommand per call:
//
//   pack-step   RUN STEP [--worktree W] [--attempt N]   steps/<STEP>/packet.json + the byte-stable cache prefix (prefix.txt, prefix.hash)
//   scope-check RUN STEP --base REF [--worktree W]      is the diff since REF acceptable for this step; ERROR lines, exit 1, steps/<STEP>/scope.json
//   regen       RUN STEP --base REF [--worktree W]      run the regeneration commands of project.json for generated files whose trigger changed
//   free-fix    RUN --base REF [--worktree W]           formatters for the changed files (never an attempt)
//   digest      RUN STEP --attempt N --stage S [--class C] [--input FILE] [--junit DIR]   failure digest (<= 2 KB) to steps/<STEP>/attempt-N/failure.json
//   event       RUN key=value... [--claude-result FILE]  append one line to events.jsonl (tokens and cache fields from a claude result)
import { createHash } from 'node:crypto'
import { appendFileSync } from 'node:fs'
import {
  type Json,
  die,
  dumps,
  existsSync,
  git,
  isDir,
  isFile,
  join,
  load,
  matches,
  mkdirp,
  parseArgs,
  readFileSync,
  readdirSync,
  resolve,
  save,
  sh,
  writeFileSync,
} from './lib.ts'
import { junitCases } from './junit.ts'
import { validateSchema } from './validate-schema.ts'

const SUPPRESS = /@SuppressWarnings|@SuppressFBWarnings|NOPMD|CHECKSTYLE:OFF|eslint-disable|\/\/\s*nolint|#nosec|@ts-ignore|@ts-nocheck/
const ASSERT = /\bassert\w*\(|\bexpect\(|\bassertThat\(|StepVerifier|\bt\.(Error|Fatal)f?\(/
const TESTFILE = /(Test\.java|\.test\.tsx?|\.spec\.tsx?|_test\.go)$/
const CLASSES: Record<string, string> = {
  tests: 'test_fail',
  'ui.vitest': 'test_fail',
  'cli.cover-check': 'test_fail',
  coverage: 'test_fail',
  compile: 'typecheck_fail',
  'ui.tsc': 'typecheck_fail',
  spotless: 'format_fail',
  'ui.prettier': 'format_fail',
  'cli.fmt-check': 'format_fail',
  style: 'lint_fail',
  spotbugs: 'lint_fail',
  'ui.eslint': 'lint_fail',
  'cli.vet': 'lint_fail',
  'cli.lint': 'lint_fail',
  timeout: 'timeout',
}
const escapeRe = (s: string): string => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
const sha = (b: Buffer | string): string => createHash('sha256').update(b).digest('hex')
const lines = (s: string): string[] => (s.length ? s.replace(/\r?\n$/, '').split(/\r?\n/) : [])

export const eco = (path: string): string =>
  path.startsWith('ui/') ? 'ui' : path.startsWith('cli/') ? 'go' : path.startsWith('.github/') ? 'shell' : 'java'

function ctx(opt: Record<string, string>, runDir: string): { run: string; wt: string; project: Json } {
  return { run: runDir, wt: resolve(opt.worktree ?? '.'), project: load(join(runDir, 'project.json')) }
}

function stepOf(run: string, sid: string): [Json, Json] {
  const spec = load(join(run, 'spec', 'spec.json'))
  const s = spec.steps.find((x: Json) => x.id === sid)
  return s ? [spec, s] : die(`no step ${sid} in the spec`)
}

/** {path: status} of the diff since BASE, untracked files included, build output left out. */
export function changed(wt: string, base: string): Record<string, string> {
  const out: Record<string, string> = {}
  for (const l of lines(git(['diff', '--name-status', '--no-renames', base], { cwd: wt }).stdout)) {
    const i = l.indexOf('\t')
    out[l.slice(i + 1)] = l.slice(0, i)
  }
  for (const p of lines(git(['ls-files', '--others', '--exclude-standard'], { cwd: wt }).stdout)) out[p] ??= 'A'
  return Object.fromEntries(Object.entries(out).filter(([p]) => !/(^|\/)(build|node_modules|\.gradle|\.yukta-dev)\//.test(p)))
}

function walkMd(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true })
    .flatMap((e) => (e.isDirectory() ? walkMd(join(dir, e.name)) : e.name.endsWith('.md') ? [join(dir, e.name)] : []))
    .sort()
}

function conventions(wt: string, project: Json, ecos: Set<string>): string {
  const out: string[] = []
  for (const e of [...ecos].sort()) {
    for (const src of project.conventions_sources[e] ?? []) {
      const p = join(wt, src)
      const files = isFile(p) ? [p] : isDir(p) ? walkMd(p) : []
      for (const f of files)
        for (const l of readFileSync(f, 'utf8').split(/\r?\n/)) if (l.trim() && !l.startsWith('---')) out.push(l.trimEnd().slice(0, 160))
    }
  }
  return out.slice(0, 60).join('\n')
}

function pack(argv: string[]): number {
  const { pos, opt } = parseArgs(argv, { value: ['worktree', 'attempt'] })
  const { run, wt, project } = ctx(opt, pos[0]!)
  const stepId = pos[1]!
  const [spec, step] = stepOf(run, stepId)
  for (const f of [...step.write_files, ...step.read_files.map((r: Json) => r.path)]) {
    if (matches(f, project.denied_read)) die(`ERROR ${stepId}: ${f} is on the deny list; an agent never reads it`)
  }
  const ecos = new Set<string>(spec.steps.flatMap((s: Json) => s.write_files.map(eco)))
  const glob = {
    forbidden_imports: spec.scope.forbidden_imports,
    allow_new_dependencies: spec.scope.allow_new_dependencies,
    locked_files: spec.locked_files,
    conventions_digest: conventions(wt, project, ecos),
  }
  // the cacheable prefix: identical for every step and retry of a run (sorted keys, no timestamps)
  const prefix = dumps({ objective: spec.objective, scope: spec.scope, global: glob }, { sort: true, indent: 1 }) + '\n'
  const ph = 'sha256:' + sha(prefix)
  const old = join(run, 'prefix.hash')
  if (isFile(old) && readFileSync(old, 'utf8').trim() !== ph) {
    die(
      'ERROR the cache prefix changed within the run (spec scope, locked files or conventions changed); a revision must reset it with `rm prefix.hash`',
    )
  }
  writeFileSync(join(run, 'prefix.txt'), prefix)
  writeFileSync(old, ph + '\n')
  const ctxf: Json[] = []
  for (const rf of step.read_files) {
    const p = join(wt, rf.path)
    if (isFile(p)) {
      const body = lines(readFileSync(p, 'utf8'))
      const [a, b] = rf.lines ? ((rf.lines as string).split('-').map(Number) as [number, number]) : [1, body.length]
      ctxf.push({ path: rf.path, lines: `${a}-${b}`, content: body.slice(a - 1, b).join('\n') })
    }
  }
  for (const f of step.write_files) {
    const p = join(wt, f)
    if (isFile(p) && !ctxf.some((c) => c.path === f)) ctxf.push({ path: f, content: readFileSync(p, 'utf8') })
  }
  const sd = join(run, 'steps', stepId)
  const prior: Json[] = []
  if (existsSync(sd)) {
    for (const d of readdirSync(sd)
      .filter((n) => /^attempt-\d+$/.test(n))
      .sort()) {
      const fp = join(sd, d, 'failure.json')
      if (isFile(fp)) prior.push({ attempt: Number(/attempt-(\d+)/.exec(d)![1]), failure_digest: readFileSync(fp, 'utf8').slice(0, 2048) })
    }
  }
  const packet = {
    run_id: run.replace(/\/+$/, '').split('/').pop(),
    spec_hash: 'sha256:' + sha(readFileSync(join(run, 'spec', 'spec.json'))),
    step,
    cases: spec.test_cases.filter((c: Json) => step.covers.includes(c.id)),
    global: glob,
    context_files: ctxf,
    prior_attempts: prior,
  }
  mkdirp(sd)
  save(join(sd, 'packet.json'), packet)
  const r = validateSchema('step-packet', join(sd, 'packet.json'))
  if (r.code) die('ERROR packet is not valid: ' + r.lines.join('\n'))
  event([run, 'role=pack', `step_id=${stepId}`, `prefix_hash=${ph}`])
  console.log(`PACKED ${stepId} prefix_hash=${ph} context_files=${ctxf.length} prior_attempts=${prior.length}`)
  return 0
}

function scope(argv: string[]): number {
  const { pos, opt } = parseArgs(argv, { value: ['worktree', 'base'] })
  if (!opt.base) die('--base is required', 2)
  const { run, wt, project } = ctx(opt, pos[0]!)
  const stepId = pos[1]!
  const [spec, step] = stepOf(run, stepId)
  const errors: { class: string; message: string }[] = []
  const err = (c: string, m: string): void => void errors.push({ class: c, message: m })
  const ch = changed(wt, opt.base)
  const gen: string[] = project.generated.map((g: Json) => g.glob)
  const markerFile = join(run, 'steps', stepId, 'regen.json')
  const marker: Record<string, string> = isFile(markerFile) ? load(markerFile) : {}
  const lockedFile = join(run, 'spec', 'locked.json')
  const locked: Record<string, string> = isFile(lockedFile) ? load(lockedFile) : {}
  for (const [f, hsh] of Object.entries(locked)) {
    const p = join(wt, f)
    if (!isFile(p) || sha(readFileSync(p)) !== hsh) err('locked_file_modified', `${f} was changed`)
  }
  const writes = new Set<string>(step.write_files)
  let counted = 0
  for (const f of Object.keys(ch).sort()) {
    if (f in locked) continue
    if (matches(f, gen)) {
      if (f in marker && isFile(join(wt, f)) && marker[f] === sha(readFileSync(join(wt, f)))) continue
      err('scope_violation', `${f} is generated and was edited by hand; it is regenerated by \`ticketkit regen\``)
      continue
    }
    if (!writes.has(f)) err('scope_violation', `${f} is not in write_files`)
    if (matches(f, project.denied_write)) err('scope_violation', `${f} may not be edited by an agent`)
    if (matches(f, project.dependency_files) && !spec.scope.allow_new_dependencies)
      err('scope_violation', `${f} is a dependency file and allow_new_dependencies is false`)
    if (matches(f, spec.scope.forbidden_globs)) err('scope_violation', `${f} is in forbidden_globs`)
  }
  // content rules on the added / removed lines of the step's own files
  for (const f of Object.keys(ch)
    .filter((x) => !(x in locked))
    .sort()) {
    if (matches(f, gen)) continue
    let d = git(['diff', '-U0', '--no-color', opt.base, '--', f], { cwd: wt, check: false }).stdout
    if (!d && isFile(join(wt, f)))
      d = lines(readFileSync(join(wt, f), 'utf8'))
        .map((l) => '+' + l + '\n')
        .join('') // untracked: everything is added
    const add = lines(d)
      .filter((l) => l.startsWith('+') && !l.startsWith('+++'))
      .map((l) => l.slice(1))
    const rem = lines(d)
      .filter((l) => l.startsWith('-') && !l.startsWith('---'))
      .map((l) => l.slice(1))
    counted += add.length + rem.length
    for (const l of add) {
      if (SUPPRESS.test(l)) err('scope_violation', `${f}: new suppression \`${l.trim().slice(0, 80)}\``)
      for (const imp of spec.scope.forbidden_imports as string[]) {
        if (new RegExp(`\\b(import|from|require)\\b.*['"\\s.]${escapeRe(imp)}\\b`).test(l))
          err('scope_violation', `${f}: forbidden import ${imp}`)
      }
    }
    if (TESTFILE.test(f) && rem.filter((l) => ASSERT.test(l)).length > add.filter((l) => ASSERT.test(l)).length)
      err('scope_violation', `${f}: assertions were removed`)
  }
  if (Object.keys(ch).filter((f) => !(f in locked) && !matches(f, gen)).length > step.limits.max_files) {
    err('scope_violation', `${Object.keys(ch).length} files changed, limits.max_files is ${step.limits.max_files}`)
  }
  if (counted > step.limits.max_diff_lines)
    err('scope_violation', `${counted} diff lines (generated files not counted) exceed limits.max_diff_lines ${step.limits.max_diff_lines}`)
  const sd = join(run, 'steps', stepId)
  mkdirp(sd)
  save(join(sd, 'scope.json'), { errors, diff_lines: counted })
  for (const e of errors) console.log(`ERROR ${e.class}: ${e.message}`)
  console.log(`RESULT scope-check errors=${errors.length} diff_lines=${counted}`)
  return errors.length ? 1 : 0
}

function regen(argv: string[]): number {
  const { pos, opt } = parseArgs(argv, { value: ['worktree', 'base'] })
  if (!opt.base) die('--base is required', 2)
  const { run, wt, project } = ctx(opt, pos[0]!)
  const sd = join(run, 'steps', pos[1]!)
  mkdirp(sd)
  const marker: Record<string, string> = {}
  const ran = new Set<string>()
  for (let i = 0; i <= project.generated.length; i++) {
    // a regenerated file may trigger the next one
    const ch = changed(wt, opt.base)
    const todo = project.generated.filter((g: Json) => !ran.has(g.glob) && Object.keys(ch).some((p) => matches(p, [g.trigger])))
    if (!todo.length) break
    for (const g of todo) {
      ran.add(g.glob)
      const r = sh(g.regen, wt)
      if (r.code) {
        console.log(`ERROR regen \`${g.regen}\` exited ${r.code}: ${(r.stdout + r.stderr).trim().slice(-300)}`)
        return 1
      }
    }
  }
  const globs = project.generated.map((g: Json) => g.glob)
  for (const p of Object.keys(changed(wt, opt.base)))
    if (matches(p, globs) && isFile(join(wt, p))) marker[p] = sha(readFileSync(join(wt, p)))
  save(join(sd, 'regen.json'), marker)
  const py = (a: string[]): string =>
    `[${a
      .sort()
      .map((x) => `'${x}'`)
      .join(', ')}]`
  console.log(`REGEN ran=${py([...ran])} files=${py(Object.keys(marker))}`)
  return 0
}

function freeFix(argv: string[]): number {
  const { pos, opt } = parseArgs(argv, { value: ['worktree', 'base'] })
  if (!opt.base) die('--base is required', 2)
  const { wt, project } = ctx(opt, pos[0]!)
  const ch = Object.entries(changed(wt, opt.base))
    .filter(([p, s]) => s !== 'D' && isFile(join(wt, p)))
    .map(([p]) => p)
  const match: Record<string, (p: string) => boolean> = {
    java: (p) => p.endsWith('.java'),
    ui: (p) => /\.(tsx?|css|json)$/.test(p),
    go: (p) => p.endsWith('.go'),
  }
  for (const e of ['java', 'ui', 'go']) {
    const files = ch.filter((p) => eco(p) === e && match[e]!(p))
    if (!files.length) continue
    const cmd = (project.free_fix[e] as string).replaceAll('{files}', files.map((f) => (e === 'ui' ? f.slice(3) : f)).join(' '))
    console.log(`FREEFIX ${e} exit=${sh(cmd, wt).code}`)
  }
  return 0
}

function digest(argv: string[]): number {
  const { pos, opt } = parseArgs(argv, { value: ['attempt', 'stage', 'class', 'input', 'junit', 'exit-code', 'command'] })
  const run = pos[0]!
  const stepId = pos[1]!
  if (!opt.attempt || !opt.stage) die('--attempt and --stage are required', 2)
  const text = opt.input ? readFileSync(opt.input, 'utf8') : readFileSync(0, 'utf8')
  const all = text.split(/\r?\n/)
  const prefixed = all.filter((l) => l.startsWith(`OUTPUT ${opt.stage}:`))
  const outLines = prefixed.length ? prefixed : all.filter((l) => l.startsWith('ERROR'))
  const failing: string[] = []
  let message = ''
  let frames: string[] = []
  if (opt.junit) {
    for (const c of junitCases(opt.junit)) {
      if (!c.bad) continue
      failing.push(`${c.classname}.${c.name}`.replace(/^\.+|\.+$/g, ''))
      if (!message) {
        message = c.bad.text.trim() ? (c.bad.message || c.bad.text.trim().split(/\r?\n/)[0]!).slice(0, 300) : ''
        frames = [...c.bad.text.matchAll(/at (com\.infenia[^\s(]*\([\w.]+:\d+\))/g)].map((m) => m[1]!).slice(0, 5)
      }
    }
  }
  let cut = false
  if (!message) {
    message = outLines
      .slice(-5)
      .map((l) => l.replace(/^(OUTPUT [\w.-]+:|ERROR [\w/.-]*:)\s*/, ''))
      .join(' | ')
    cut = message.length > 300
    message = message.slice(0, 300)
  }
  const d: Json = {
    step_id: stepId,
    attempt: Number(opt.attempt),
    stage: opt.stage,
    class: opt.class ?? CLASSES[opt.stage] ?? 'infra_error',
    exit_code: Number(opt['exit-code'] ?? 1),
    message,
    failing: [...failing.slice(0, 3), ...(failing.length > 3 ? [`(+${failing.length - 3} more)`] : [])],
    frames,
  }
  if (opt.command) d.command = opt.command
  if (cut) d.truncated = true
  while (dumps(d).length > 2048 && (d.message || d.frames.length || d.failing.length)) {
    // at most 2 KB: shrink the free text first
    d.truncated = true
    if (d.message.length > 60) d.message = d.message.slice(0, Math.max(60, Math.floor(d.message.length / 2)))
    else if (d.frames.length) d.frames = d.frames.slice(0, -1)
    else if (d.failing.length > 1) d.failing = d.failing.slice(0, -1)
    else break
  }
  const ad = join(run, 'steps', stepId, `attempt-${opt.attempt}`)
  mkdirp(ad)
  save(join(ad, 'failure.json'), d)
  const r = validateSchema('failure-digest', join(ad, 'failure.json'))
  if (r.code) die('ERROR digest is not valid: ' + r.lines.join('\n'))
  console.log(dumps(d))
  return 0
}

/** One line to RUN/events.jsonl: key=value pairs (numbers become numbers) and the usage fields of a claude result. */
export function event(argv: string[]): number {
  const { pos, opt } = parseArgs(argv, { value: ['claude-result'] })
  const run = pos[0]!
  const e: Json = { ts: new Date().toISOString().replace(/\.\d+Z$/, 'Z'), run_id: run.replace(/\/+$/, '').split('/').pop() }
  for (const kv of pos.slice(1)) {
    const i = kv.indexOf('=')
    const k = i < 0 ? kv : kv.slice(0, i)
    const v = i < 0 ? '' : kv.slice(i + 1)
    e[k] = /^-?\d+$/.test(v) ? parseInt(v, 10) : /^-?\d+\.\d+$/.test(v) ? parseFloat(v) : v
  }
  if (opt['claude-result']) {
    const r = load(opt['claude-result'])
    const u = r.usage ?? {}
    Object.assign(e, {
      tokens_in: u.input_tokens ?? 0,
      tokens_out: u.output_tokens ?? 0,
      cache_read_tokens: u.cache_read_input_tokens ?? 0,
      cache_write_tokens: u.cache_creation_input_tokens ?? 0,
      cost_usd: r.total_cost_usd ?? 0,
      duration_ms: r.duration_ms ?? 0,
    })
  }
  appendFileSync(join(run, 'events.jsonl'), dumps(e, { sort: true }) + '\n')
  return 0
}

export function main(cmd: string, argv: string[]): number {
  const fn: Record<string, (a: string[]) => number> = { 'pack-step': pack, 'scope-check': scope, regen, 'free-fix': freeFix, digest, event }
  return (fn[cmd] ?? (() => die(`stepkit: unknown subcommand '${cmd}'`, 2)))(argv)
}
