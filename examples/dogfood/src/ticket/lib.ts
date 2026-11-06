// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// Shared helpers of the ticketkit commands: glob matching, the model table (design 3.1), run-dir access, JSON in the shape the
// pipeline has always written (sorted keys, 2-space indent, ASCII only), git and process helpers.
import { spawnSync } from 'node:child_process'
import { existsSync, mkdirSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

/** examples/dogfood */
export const KIT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..')
/** examples/dogfood */
export const DOGFOOD = KIT
export const GUARD_PATHS = ['config/**', 'build-logic/**', 'gradle/**', '.github/**', '.claude/**', 'examples/dogfood/**']
export const RISK = ['low', 'medium', 'high'] as const
export type Risk = (typeof RISK)[number]
// eslint-disable-next-line @typescript-eslint/no-explicit-any
export type Json = any

/** A one-line message on stderr and the exit code; nothing after it runs. */
export function die(msg: string, code = 1): never {
  console.error(msg)
  process.exit(code)
}

function sorted(v: Json): Json {
  if (Array.isArray(v)) return v.map(sorted)
  if (v && typeof v === 'object')
    return Object.fromEntries(
      Object.keys(v)
        .sort()
        .map((k) => [k, sorted(v[k])]),
    )
  return v
}

/** json.dumps of Python: ", " and ": " separators when compact, non-ASCII escaped; `sort` orders the keys. */
export function dumps(v: Json, opts: { indent?: number; sort?: boolean } = {}): string {
  const data = opts.sort ? sorted(v) : v
  const s = opts.indent ? JSON.stringify(data, null, opts.indent) : compact(data)
  return s.replace(/[\u0080-￿]/g, (c) => '\\u' + c.charCodeAt(0).toString(16).padStart(4, '0'))
}

function compact(v: Json): string {
  if (Array.isArray(v)) return '[' + v.map(compact).join(', ') + ']'
  if (v && typeof v === 'object') {
    return (
      '{' +
      Object.keys(v)
        .filter((k) => v[k] !== undefined)
        .map((k) => JSON.stringify(k) + ': ' + compact(v[k]))
        .join(', ') +
      '}'
    )
  }
  return JSON.stringify(v) ?? 'null'
}

export function load(path: string): Json {
  try {
    return JSON.parse(readFileSync(path, 'utf8'))
  } catch (e) {
    return die(`cannot read ${path}: ${(e as Error).message}`)
  }
}

export function save(path: string, data: Json): void {
  writeFileSync(path, dumps(data, { indent: 2, sort: true }) + '\n')
}

export const isFile = (p: string): boolean => existsSync(p) && !statSync(p).isDirectory()
export const isDir = (p: string): boolean => existsSync(p) && statSync(p).isDirectory()
export const mkdirp = (p: string): void => void mkdirSync(p, { recursive: true })

const escapeRe = (s: string): string => s.replace(/[.*+?^${}()|[\]\\/-]/g, '\\$&')

/** `**` crosses directories, `*` and `?` do not; `**` followed by a slash also matches nothing. */
export function globRe(glob: string): RegExp {
  let out = ''
  for (let i = 0; i < glob.length;) {
    if (glob.startsWith('**/', i)) {
      out += '(?:.*/)?'
      i += 3
    } else if (glob.startsWith('**', i)) {
      out += '.*'
      i += 2
    } else if (glob[i] === '*') {
      out += '[^/]*'
      i += 1
    } else if (glob[i] === '?') {
      out += '[^/]'
      i += 1
    } else {
      out += escapeRe(glob[i]!)
      i += 1
    }
  }
  return new RegExp('^' + out + '$', 's')
}

export const matches = (path: string, globs: string[]): boolean => globs.some((g) => globRe(g).test(path))

/** The unit a path belongs to: plugins/<a>/<b>, plus/<a>, else the first directory (a root file is its own unit). */
export function moduleOf(path: string): string {
  const p = path.split('/')
  const n = p[0] === 'plugins' ? 3 : p[0] === 'plus' ? 2 : 1
  return p.length > n ? p.slice(0, n).join('/') : p[0]!
}

export const maxRisk = (...rs: Risk[]): Risk => rs.reduce((a, b) => (RISK.indexOf(b) > RISK.indexOf(a) ? b : a))

type Model = { model: string; effort: string }
const m = (model: string, effort: string): Model => ({ model, effort })

/** Design 3.1: {role: {model, effort} or null}. S+low is its own row; L or high risk is the strong row. */
export function modelTable(complexity: string, risk: string, tierMode = 'two_tier', override?: Model | null): Record<string, Model | null> {
  let t: Record<string, Model | null>
  if (complexity === 'L' || risk === 'high') {
    t = {
      architect: m('opus', 'high'),
      test_writer: m('sonnet', 'medium'),
      implementer: m('haiku', 'medium'),
      implementer_tricky: m('sonnet', 'high'),
      reviewer: m('opus', 'medium'),
    }
  } else if (complexity === 'S' && risk === 'low') {
    t = {
      architect: m('sonnet', 'medium'),
      test_writer: null,
      implementer: m('haiku', 'medium'),
      implementer_tricky: m('sonnet', 'medium'),
      reviewer: m('sonnet', 'low'),
    }
  } else {
    t = {
      architect: m('sonnet', 'high'),
      test_writer: m('sonnet', 'low'),
      implementer: m('haiku', 'medium'),
      implementer_tricky: m('sonnet', 'medium'),
      reviewer: m('sonnet', 'medium'),
    }
  }
  if (tierMode === 'single') t.implementer = t.implementer_tricky = t.architect!
  if (override) t.implementer = t.implementer_tricky = override
  return t
}

/** The implementer model of one step (design 3.1): the ladder's choice (steps/S/model.json), else `implementer_tricky` for a step the
 * architect marked tricky, else `implementer`. Every reader of a step's model goes through here, so the call, the ladder and the telemetry agree. */
export function stepModel(run: string, sid: string, routing: Json = load(join(run, 'routing.json'))): Model {
  const mf = join(run, 'steps', sid, 'model.json')
  if (isFile(mf)) return load(mf)
  const sf = join(run, 'spec', 'spec.json')
  const tricky = isFile(sf) && load(sf).steps?.find((x: Json) => x.id === sid)?.complexity === 'tricky'
  return (tricky && routing.models.implementer_tricky) || routing.models.implementer
}

/** The list items (`- x` or `* x`) under the first heading matching `rx`, up to the next heading. */
export function sectionItems(body: string | null | undefined, rx: RegExp): string[] {
  const out: string[] = []
  let on = false
  for (const line of (body ?? '').split(/\r?\n/)) {
    if (/^\s{0,3}#{1,6}\s/.test(line)) {
      on = rx.test(line)
      continue
    }
    const m = /^\s*[-*]\s+(.+)/.exec(line)
    if (on && m) out.push(m[1]!.trim())
  }
  return out
}

const CONVENTIONAL: Record<string, string> = {
  feat: 'feature',
  fix: 'bug',
  refactor: 'refactor',
  docs: 'docs',
  chore: 'chore',
  ci: 'chore',
  build: 'chore',
  style: 'chore',
  perf: 'chore',
  revert: 'chore',
  test: 'chore',
}
const TYPE_LABEL: Record<string, string> = {
  'type/feature': 'feature',
  'type/bugfix': 'bug',
  'type/refactor': 'refactor',
  'type/docs': 'docs',
  'type/chore': 'chore',
  'type/test': 'chore',
}

export function kindOf(title: string, labels: string[]): string {
  for (const l of labels) if (l in TYPE_LABEL) return TYPE_LABEL[l]!
  const m = /^(\w+)(\([^)]*\))?!?:/.exec(title)
  return m ? (CONVENTIONAL[m[1]!] ?? 'feature') : 'feature'
}

/** The tests modes whose tests are written (and red-checked) before any step: only these need a test writer. */
export const UP_FRONT = ['repro_only', 'cases_to_code', 'architect_code']

/** Design 3.3 (decided by code, from the kind and size). architect_code is allowed later by validate-spec, not chosen here. */
export function testsModeFor(kind: string, complexity: string, risk: string): string {
  if (kind === 'docs' || kind === 'chore') return 'existing'
  if (kind === 'bug') return 'repro_only'
  if (kind === 'feature' && complexity === 'S' && risk === 'low') return 'implementer_writes'
  return 'cases_to_code'
}

export type Proc = { code: number; stdout: string; stderr: string }

/** The programs these tools launch by name. Anything else must be this runtime, or a binary the environment names on purpose (YUKTA_BIN, YUKTA_CLAUDE_BIN, YK_CLAUDE_BIN, YUKTA_PICKUP_YK). */
const PROGRAMS = new Set([
  'git',
  'gh',
  'bun',
  'bash',
  'sh',
  'claude',
  'cp',
  'mv',
  'ln',
  'tar',
  'cosign',
  'reuse',
  'pgrep',
  'yukta-cli',
  'java',
  'make',
])
function checkProgram(program: string): void {
  const named = [
    process.execPath,
    process.env.YUKTA_BIN,
    process.env.YUKTA_CLAUDE_BIN,
    process.env.YK_CLAUDE_BIN,
    process.env.YUKTA_PICKUP_YK,
  ]
  if (
    PROGRAMS.has(program.split('/').pop()!) ||
    named.includes(program) ||
    /(^|\/)yukta-cli(-[a-z0-9-]+)?$/.test(program) ||
    /(^|\/)claude(\.ts)?$/.test(program)
  )
    return
  throw new Error(`refusing to run '${program}': not a program of the dogfood tooling`)
}

/** Runs a program without a shell; never throws (a missing program is code 127). */
export function run(cmd: string[], opts: { cwd?: string; input?: string; env?: Record<string, string | undefined> } = {}): Proc {
  try {
    checkProgram(cmd[0]!)
  } catch (e) {
    return { code: 126, stdout: '', stderr: (e as Error).message }
  }
  const r = spawnSync(cmd[0]!, cmd.slice(1), {
    cwd: opts.cwd,
    input: opts.input,
    encoding: 'utf8',
    env: opts.env ? { ...process.env, ...opts.env } : process.env,
    maxBuffer: 1 << 28,
  })
  if (r.error) return { code: 127, stdout: '', stderr: String(r.error.message) }
  return { code: r.status ?? 1, stdout: r.stdout ?? '', stderr: r.stderr ?? '' }
}

/** Runs a command line through the shell (the project's own command templates are shell lines). */
export function sh(line: string, cwd?: string): Proc {
  // `yk` of the templates resolves even when the server that runs the workflow has no examples/dogfood on its PATH
  return run(['bash', '-c', line], { cwd, env: { PATH: `${process.env.PATH ?? ''}:${KIT}` } })
}

export function git(args: string[], opts: { cwd?: string; check?: boolean } = {}): Proc {
  const r = run(['git', ...args], { cwd: opts.cwd })
  if ((opts.check ?? true) && r.code) die(`git ${args.join(' ')}: ${r.stderr.trim()}`)
  return r
}

/** [{role: usd}, total] from RUN/events.jsonl. */
export function costByRole(runDir: string): [Record<string, number>, number] {
  const by: Record<string, number> = {}
  let total = 0
  const f = join(runDir, 'events.jsonl')
  const lines = isFile(f)
    ? readFileSync(f, 'utf8')
        .split('\n')
        .filter((l) => l.length)
    : []
  for (const l of lines) {
    const e = JSON.parse(l)
    if (e.cost_usd) {
      by[e.role] = (by[e.role] ?? 0) + e.cost_usd
      total += e.cost_usd
    }
  }
  return [by, total]
}

/** An attempt number from 'cur' (the highest existing, 1 when none), 'next' (one more) or an integer; the attempt dir is created. */
export function resolveAttempt(stepDir: string, v: string): number {
  const nums = existsSync(stepDir)
    ? readdirSync(stepDir)
        .map((d) => /^attempt-(\d+)$/.exec(d)?.[1])
        .filter((x): x is string => !!x)
        .map(Number)
    : []
  let n = Math.max(0, ...nums)
  n = v === 'next' ? n + 1 : v === 'cur' ? Math.max(n, 1) : parseInt(v, 10)
  mkdirp(join(stepDir, `attempt-${n}`))
  return n
}

/** The arguments of a command: `--flag value`, `--flag=value`, boolean flags and positionals (what the argparse calls needed). */
export function parseArgs(
  argv: string[],
  spec: { value?: string[]; bool?: string[] },
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
        if (v === undefined) die(`${a}: expects a value`, 2)
        opt[k!] = v
      } else die(`unknown option ${a}`, 2)
    } else pos.push(a)
  }
  return { pos, opt, flag }
}

export const readText = (p: string): string => readFileSync(p, 'utf8')
export { existsSync, join, resolve, readdirSync, readFileSync, writeFileSync }

/** `{name}` placeholders of a command template (the Python str.format of the pipeline, for the names given). */
export const fmt = (template: string, vars: Record<string, string>): string =>
  template.replace(/\{(\w+)\}/g, (m, k: string) => vars[k] ?? m)

/** Runs a ticketkit subcommand in its own process (for the callers that read its output and exit code). */
export function kit(args: string[], cwd?: string, env?: Record<string, string | undefined>): Proc {
  return run([process.execPath, join(KIT, 'src/ticket/ticketkit.ts'), ...args], { cwd, env })
}

/** Files named `name` below `dir`, recursively. */
export function findFiles(dir: string, name: string): string[] {
  if (!existsSync(dir)) return []
  return readdirSync(dir, { withFileTypes: true }).flatMap((e) =>
    e.isDirectory() ? findFiles(join(dir, e.name), name) : e.name === name ? [join(dir, e.name)] : [],
  )
}
