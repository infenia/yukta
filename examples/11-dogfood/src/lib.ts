// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// Shared helpers of `yk` and the dogfood commands: paths, the dev server, the CLI, sessions, results, durations and the decision model.
import { spawn, spawnSync } from 'node:child_process'
import { createHash, randomBytes } from 'node:crypto'
import {
  appendFileSync, chmodSync, closeSync, existsSync, mkdirSync, openSync, readFileSync, readdirSync, renameSync, rmSync, statSync, utimesSync, writeFileSync,
} from 'node:fs'
import { createConnection } from 'node:net'
import { homedir, tmpdir } from 'node:os'
import { basename, dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export type Json = any
export { existsSync, join, dirname, basename, resolve, readFileSync, writeFileSync, mkdirSync, readdirSync, rmSync, statSync, appendFileSync, renameSync }

/** examples/11-dogfood */
export const HERE = resolve(dirname(fileURLToPath(import.meta.url)), '..')
export type Proc = { code: number; stdout: string; stderr: string }

export function run(cmd: string[], opts: { cwd?: string; input?: string; env?: Record<string, string | undefined>; timeoutMs?: number } = {}): Proc {
  const env = opts.env ? { ...process.env, ...opts.env } : process.env
  for (const k of Object.keys(env)) if (env[k] === undefined) delete env[k]
  const r = spawnSync(cmd[0]!, cmd.slice(1), { cwd: opts.cwd, input: opts.input, encoding: 'utf8', env: env as NodeJS.ProcessEnv, maxBuffer: 1 << 28, timeout: opts.timeoutMs })
  if (r.error) return { code: 127, stdout: '', stderr: String(r.error.message) }
  return { code: r.status ?? (r.signal ? 128 : 1), stdout: r.stdout ?? '', stderr: r.stderr ?? '' }
}

/** A line through the shell (the commands of the stages are shell lines). */
export const sh = (line: string, cwd?: string, env?: Record<string, string | undefined>): Proc => run(['bash', '-c', line], { cwd, env })

export function git(args: string[], cwd?: string): Proc {
  return run(['git', ...(cwd ? ['-C', cwd] : []), ...args])
}

export class Fail extends Error {
  constructor(msg: string, readonly code = 1) { super(msg) }
}
export function die(msg: string, code = 1): never {
  console.error(`error: ${msg}`)
  process.exit(code)
}

export function onPath(cmd: string): string | null {
  for (const d of (process.env.PATH ?? '').split(':')) {
    const f = join(d || '.', cmd)
    try { if (statSync(f).isFile()) return f } catch { /* not here */ }
  }
  return null
}
export function need(cmd: string): void {
  if (!onPath(cmd)) die(`'${cmd}' is required but not on PATH`)
}

/** printf %q: a word the shell reads back as the argument. */
export function shq(s: string): string {
  if (s === '') return "''"
  if (/^[A-Za-z0-9_@%+=:,./-]+$/.test(s)) return s
  if (/[\n\t\r]/.test(s)) return "$'" + s.replace(/\\/g, '\\\\').replace(/'/g, "\\'").replace(/\n/g, '\\n').replace(/\t/g, '\\t').replace(/\r/g, '\\r') + "'"
  return s.replace(/[^A-Za-z0-9_@%+=:,./-]/g, c => '\\' + c)
}

/** jq @sh: a single-quoted word. */
export const sq = (s: string): string => "'" + s.replace(/'/g, "'\\''") + "'"

export const text = (p: string, d = ''): string => { try { return readFileSync(p, 'utf8') } catch { return d } }
export const readJson = (p: string): Json => JSON.parse(readFileSync(p, 'utf8'))
export function tryJson(s: string): Json | undefined { try { return JSON.parse(s) } catch { return undefined } }
export const isFile = (p: string): boolean => { try { return statSync(p).isFile() } catch { return false } }
export const isDir = (p: string): boolean => { try { return statSync(p).isDirectory() } catch { return false } }
export const mkdirp = (p: string): void => void mkdirSync(p, { recursive: true })
export const sha1 = (s: string | Buffer): string => createHash('sha1').update(s).digest('hex')
export const isoNow = (): string => new Date().toISOString().replace(/\.\d+Z$/, 'Z')
export const sleep = (ms: number): Promise<void> => new Promise(r => setTimeout(r, ms))

// ---- where things live
export const REPO_ROOT = process.env.YUKTA_REPO_ROOT || git(['rev-parse', '--show-toplevel'], HERE).stdout.trim()
/** Per checkout: heal records, durations, check logs, hook marks. */
export const STATE_DIR = process.env.YUKTA_DEV_STATE_DIR || join(REPO_ROOT, '.yukta-dev')
/** Per machine: the one dev server every checkout shares (main worktree, builds by commit, pid, log, sessions). */
export const DEV_HOME = process.env.YUKTA_DEV_HOME || join(process.env.XDG_STATE_HOME || join(homedir(), '.local/state'), 'yukta-dev')
export const DEV_PORT = process.env.YUKTA_DEV_PORT || '9858'
export const DEV_URL = `http://127.0.0.1:${DEV_PORT}`

export function osArch(): string {
  const os = process.platform === 'darwin' ? 'darwin' : process.platform
  const arch = process.arch === 'x64' ? 'amd64' : process.arch === 'arm64' ? 'arm64' : process.arch
  return `${os}-${arch}`
}

// ---- keys
function devSecret(name: string): string {
  const file = join(DEV_HOME, name)
  if (!isFile(file) || statSync(file).size === 0) {
    mkdirp(DEV_HOME)
    writeFileSync(file, randomBytes(48).toString('base64').replace(/[^A-Za-z0-9]/g, '').slice(0, 32) || randomBytes(16).toString('hex'), { mode: 0o600 })
    chmodSync(file, 0o600)
  }
  return text(file)
}
/** The dev server's API key (generated once, readable only by this user). */
export const devKey = (): string => devSecret('api-key')
/** A second, named VIEWER key of the dev server for the headless fixers (they may read a run over MCP, never change it). */
export const fixerKey = (): string => devSecret('fixer-key')
/** Hands the key to the yukta CLI (YUKTA_TOKEN) when it talks to the dev server. */
export function useDevKey(): void {
  if (!process.env.YUKTA_TOKEN && (process.env.YUKTA_SERVER_URL ?? DEV_URL) === DEV_URL) process.env.YUKTA_TOKEN = devKey()
}
/** A value from the running dev server's state file, or ''. */
export function serverEnv(key: string): string {
  const m = new RegExp(`^${key}=(.*)$`, 'm').exec(text(join(DEV_HOME, 'server.env')))
  return m ? m[1]! : ''
}

function newestBuildCli(): string {
  const dir = join(DEV_HOME, 'builds')
  if (!isDir(dir)) return ''
  const f = readdirSync(dir).map(d => join(dir, d, 'yukta-cli')).filter(isFile).sort((a, b) => statSync(b).mtimeMs - statSync(a).mtimeMs)
  return f[0] ?? ''
}
const executable = (p: string): boolean => { try { return statSync(p).isFile() && (statSync(p).mode & 0o111) !== 0 } catch { return false } }

/** Locate the yukta CLI: $YUKTA_BIN, the CLI built with the running server, the newest main build, PATH, then this checkout's cli/build. */
export function findCli(): string {
  if (process.env.YUKTA_BIN) return process.env.YUKTA_BIN
  let f = serverEnv('YUKTA_DEV_CLI')
  if (f && executable(f)) return f
  f = newestBuildCli()
  if (f && executable(f)) return f
  const onp = onPath('yukta-cli')
  if (onp) return onp
  for (const c of [join(REPO_ROOT, 'cli/build', `yukta-cli-${osArch()}`), join(REPO_ROOT, 'cli/build/yukta-cli')]) if (executable(c)) return c
  return die("yukta-cli not found; run 'yk up' (builds it from main) or set YUKTA_BIN")
}

/** The yukta CLI with JSON output; the unwrapped data of the answer. */
export function cliJson(args: string[]): { code: number; data: Json; raw: string; err: string } {
  const r = run([findCli(), '-o', 'json', ...args])
  const j = tryJson(r.stdout)
  return { code: r.code, data: j && typeof j === 'object' && 'data' in j ? j.data : j, raw: r.stdout, err: r.stderr }
}

// ---- sessions
/** Writes the one `dogfood` session to OUT, always from the main checkout the dev server runs ($DEV_HOME/main), never from the worktree yk
 * was called in: __PROJECT_PATH__ (the project) and __DOGFOOD__ (the scripts) are that checkout's. */
export function renderSession(out: string, extra?: string): void {
  let d = join(DEV_HOME, 'main/examples/11-dogfood')
  if (!isFile(join(d, 'session.json'))) d = HERE
  const project = d.replace(/\/examples\/11-dogfood$/, '')
  const sub = (v: Json): Json => (typeof v === 'string' ? v.split('__PROJECT_PATH__').join(project).split('__DOGFOOD__').join(d) : Array.isArray(v) ? v.map(sub) : v && typeof v === 'object' ? Object.fromEntries(Object.entries(v).map(([k, x]) => [k, sub(x)])) : v)
  const s = sub(readJson(join(d, 'session.json')))
  s.sessionId = 'dogfood'
  s.name = 'dogfood'
  if (extra) Object.assign(s.workflows, extra)
  writeFileSync(out, JSON.stringify(s, null, 2) + '\n')
}

/** The one session every run of this checkout's tools shares on the dev server. */
export const checkSession = (): string => 'dogfood'

/** A hash of the uncommitted code changes of a checkout (the Stop hook skips a check that already passed on the same hash). */
export function changesSum(root: string): string {
  const a = git(['diff', 'HEAD'], root).stdout
  const b = git(['status', '--porcelain', '--', '*.java', '*.kts', '*.gradle', '*.go', '*.ts', '*.tsx', '*.json'], root).stdout
  return sha1(a + b)
}

// ---- telling the Claude Code session that started a run (cross-session messaging)
/** Posts TEXT into the Claude Code session this process was started from, as a message from another session. Best effort: it never fails the caller.
 *   {"type":"auth","token":"<token>"}
 *   {"type":"user","message":{"role":"user","content":"<text>"}} */
export async function notifySession(msg: string): Promise<void> {
  const path = process.env.CLAUDE_CODE_MESSAGING_SOCKET
  if ((process.env.YUKTA_NOTIFY ?? 'on') === 'off' || !path) return
  const token = process.env.CLAUDE_CODE_MESSAGING_TOKEN ?? ''
  const lines = [...(token ? [{ type: 'auth', token }] : []), { type: 'user', message: { role: 'user', content: msg.slice(0, 8000) } }]
  await new Promise<void>(done => {
    const s = createConnection({ path })
    s.setTimeout(5000, () => { s.destroy(); done() })
    s.on('error', e => { console.error(`notify: ${e.message}`); done() })
    s.on('connect', () => s.end(lines.map(l => JSON.stringify(l)).join('\n') + '\n', () => done()))
    s.on('close', () => done())
  })
}

/** The message for a finished run from what `yk watch` printed: its RESULT line, the problem lines before it, and how to read more. */
export function notifyText(session: string, exec: string, log: string): string {
  const ls = log.split('\n')
  const result = ls.filter(l => l.startsWith('RESULT ')).pop()
  if (!result) return `[yk] run ${exec} ended without a RESULT line (stopped?). yk watch ${session} ${exec} shows where it is.`
  const problems = ls.filter(l => /^(ERROR|FAILED|OUTPUT) /.test(l)).slice(-12).map(l => l.slice(0, 300)).join('\n')
  return `[yk] run finished: ${result}\n${problems}${problems ? '\n' : ''}\nMore: yk detail ${session} ${exec}\n`
}

// ---- http
export async function http(method: string, url: string, o: { headers?: Record<string, string>; body?: string; timeoutMs?: number } = {}): Promise<{ code: number; body: string }> {
  try {
    const r = await fetch(url, { method, headers: o.headers, body: o.body, signal: AbortSignal.timeout(o.timeoutMs ?? 30000) })
    return { code: r.status, body: await r.text() }
  } catch (e) {
    return { code: 0, body: String((e as Error).message) }
  }
}
export const keyHeader = (): Record<string, string> => ({ 'X-API-Key': devKey() })

// ---- the dev server: built from origin/main, one per machine, on $DEV_PORT
export async function healthy(url = DEV_URL): Promise<boolean> {
  return (await http('GET', `${url}/actuator/health`, { timeoutMs: 3000 })).code === 200
}

const RUNNING = /RUNNING|PENDING|QUEUED|PAUSED/
/** The executions of the dev server (any session). */
export async function executions(): Promise<Json[] | null> {
  const r = await http('GET', `${DEV_URL}/api/executions?limit=1000`, { headers: keyHeader(), timeoutMs: 5000 })
  const j = tryJson(r.body)
  if (r.code !== 200 || j === undefined) return null
  return Array.isArray(j) ? j : j.data ?? null
}

/** The number of executions not yet ended, on any session, except the `pickup` schedule. When it cannot be read it says 1: never stop a server we cannot see idle. */
export async function busy(): Promise<number> {
  const ex = await executions()
  return ex ? ex.filter(e => e.workflowId !== 'pickup' && RUNNING.test(e.status ?? '')).length : 1
}

export async function pickupAlive(): Promise<boolean> {
  const id = text(join(DEV_HOME, 'pickup.exec')).trim()
  if (!id) return false
  const ex = await executions()
  return !!ex?.some(e => e.executionId === id && RUNNING.test(e.status ?? ''))
}

/** Applies the library and starts `pickup` on session dogfood; prints the execution id and keeps it in pickup.exec. */
export function pickupStart(): string | null {
  const tmp = join(tmpdir(), `yk-pickup-${process.pid}`)
  mkdirp(tmp)
  try {
    renderSession(join(tmp, 'session.json'))
    const a = run([findCli(), 'session', 'apply', join(tmp, 'session.json')])
    if (a.code) { console.error(a.stdout + a.stderr); return null }
    const id = cliJson(['workflow', 'start', checkSession(), 'pickup', '--payload', '{}']).data?.executionId
    if (!id) return null
    mkdirp(DEV_HOME)
    writeFileSync(join(DEV_HOME, 'pickup.exec'), id + '\n')
    return id
  } finally { rmSync(tmp, { recursive: true, force: true }) }
}

/** After a server swap or start, start the schedule again when it was on (the execution dies with the old server). */
export async function pickupResume(): Promise<void> {
  if (text(join(DEV_HOME, 'pickup.exec')).trim() === '' || (await pickupAlive())) return
  process.env.YUKTA_SERVER_URL = DEV_URL
  if (pickupStart()) console.error('pickup: schedule started again')
  else console.error('WARN pickup: could not start the schedule again (yk pickup on)')
}

/** Brings the managed worktree $DEV_HOME/main to origin/main (or $YUKTA_DEV_MAIN_REF) and returns its commit. */
export function mainSync(): string {
  const wt = join(DEV_HOME, 'main')
  const ref = process.env.YUKTA_DEV_MAIN_REF ?? 'origin/main'
  if (!existsSync(join(wt, '.git'))) {
    if (git(['fetch', '-q', 'origin', 'main'], REPO_ROOT).code) console.error('warning: fetch failed; using the local origin/main')
    const r = git(['worktree', 'add', '-q', '--detach', wt, ref], REPO_ROOT)
    if (r.stderr) console.error(r.stderr.trim())
  } else {
    if (git(['fetch', '-q', 'origin', 'main'], wt).code) console.error('warning: fetch failed; using the local origin/main')
    const r = git(['checkout', '-q', '--force', '--detach', ref], wt)
    if (r.stderr) console.error(r.stderr.trim())
  }
  return git(['rev-parse', 'HEAD'], wt).stdout.trim()
}

/** Builds the server jar and the CLI of the main worktree into $DEV_HOME/builds/SHA, unless that build exists. Keeps the three newest builds. */
export function mainBuild(sha: string): void {
  const wt = join(DEV_HOME, 'main')
  const out = join(DEV_HOME, 'builds', sha)
  const log = join(DEV_HOME, 'build.log')
  if (isFile(join(out, 'yukta.jar')) && statSync(join(out, 'yukta.jar')).size > 0 && executable(join(out, 'yukta-cli'))) return
  // The ui build needs the node/pnpm toolchain gradle downloaded into ui/.gradle; reuse a checkout's copy.
  const common = dirname(git(['rev-parse', '--path-format=absolute', '--git-common-dir'], REPO_ROOT).stdout.trim())
  for (const src of [join(REPO_ROOT, 'ui/.gradle'), join(common, 'ui/.gradle')]) {
    if (isDir(join(src, 'pnpm'))) {
      mkdirp(join(wt, 'ui/.gradle'))
      for (const d of readdirSync(src)) if (!existsSync(join(wt, 'ui/.gradle', d))) run(['cp', '-r', '--reflink=auto', join(src, d), join(wt, 'ui/.gradle/')])
      break
    }
  }
  console.error(`building main@${sha.slice(0, 9)} (bootJar + CLI; minutes on a new commit, log: ${log}) ...`)
  const b = sh(`./gradlew :boot:bootJar :cli:goBuild -q --console=plain >${sq(log)} 2>&1`, wt)
  if (b.code) die(`build of main@${sha.slice(0, 9)} failed; see ${log}`)
  const libs = join(wt, 'boot/build/libs')
  const jar = isDir(libs) ? readdirSync(libs).filter(f => f.endsWith('.jar') && !f.includes('plain')).map(f => join(libs, f)).sort((a, c) => statSync(c).mtimeMs - statSync(a).mtimeMs)[0] : undefined
  if (!jar) die(`no boot jar after the build; see ${log}`)
  rmSync(`${out}.tmp`, { recursive: true, force: true })
  mkdirp(`${out}.tmp`)
  run(['cp', jar, join(`${out}.tmp`, 'yukta.jar')])
  run(['cp', join(wt, 'cli/build', `yukta-cli-${osArch()}`), join(`${out}.tmp`, 'yukta-cli')])
  renameSync(`${out}.tmp`, out)
  const dirs = readdirSync(join(DEV_HOME, 'builds')).map(d => join(DEV_HOME, 'builds', d)).filter(isDir).sort((a, c) => statSync(c).mtimeMs - statSync(a).mtimeMs)
  for (const d of dirs.slice(3)) rmSync(d, { recursive: true, force: true })
}

/** Starts the dev server in its own process group (so serverStop stops it with its children) and records it in $DEV_HOME/server.env. */
export async function serverLaunch(kind: string, id: string, label: string, rules: string, cmd: string[]): Promise<void> {
  mkdirp(join(DEV_HOME, 'sessions'))
  rmSync(join(DEV_HOME, 'server.pid'), { force: true })
  const par = process.env.YUKTA_DEV_GRADLE_PARALLEL ?? '2'
  // The key goes in a file, not the environment: the server's children (the gradle stages and their test JVMs) inherit its environment.
  writeFileSync(join(DEV_HOME, 'server.properties'),
    `yukta.api-key=${devKey()}\nyukta.api-keys[0].name=fixer\nyukta.api-keys[0].key=${fixerKey()}\nyukta.api-keys[0].role=VIEWER\nyukta.api-keys[0].sessions=*\n`, { mode: 0o600 })
  const logFd = openSync(join(DEV_HOME, 'server.log'), 'w')
  const child = spawn(cmd[0]!, [...cmd.slice(1), `--spring.config.additional-location=file:${join(DEV_HOME, 'server.properties')}`], {
    cwd: DEV_HOME, detached: true, stdio: ['ignore', logFd, logFd],
    env: {
      ...process.env, SERVER_PORT: DEV_PORT, YUKTA_SERVER_ADDRESS: '127.0.0.1', YUKTA_PROCESS_ALLOW_ANY: 'true', YUKTA_CONCURRENCY_GROUPS_GRADLE: par, YUKTA_CONCURRENCY_GROUPS_TOOL: par,
      YUKTA_DEV_HOME: DEV_HOME, YUKTA_SESSION_BASE_DIR: join(DEV_HOME, 'sessions'), YUKTA_SESSION_EXECUTION_TIMEOUT_SECONDS: '86400', YUKTA_SUPERVISOR_RULES_FILE: rules,
    },
  })
  child.unref()
  closeSync(logFd)
  writeFileSync(join(DEV_HOME, 'server.pid'), `${child.pid}\n`)
  let cli = join(DEV_HOME, 'builds', id, 'yukta-cli')
  if (!executable(cli)) cli = newestBuildCli()
  writeFileSync(join(DEV_HOME, 'server.env'), `YUKTA_DEV_KIND=${kind}\nYUKTA_DEV_SHA=${id}\nYUKTA_DEV_LABEL=${label}\nYUKTA_DEV_CLI=${cli}\nYUKTA_DEV_KEYED=2\nYUKTA_SERVER_URL=${DEV_URL}\n`)
  console.error(`waiting for Yukta at ${DEV_URL} (log: ${join(DEV_HOME, 'server.log')}) ...`)
  for (let i = 0; i < 60; i++) {
    if (!alive(child.pid!)) { rmSync(join(DEV_HOME, 'server.env'), { force: true }); die(`server exited early; see ${join(DEV_HOME, 'server.log')}`) }
    if (await healthy()) { console.log(`ready: ${DEV_URL} (${label})`); return }
    await sleep(2000)
  }
  die(`server did not become healthy in 120s; see ${join(DEV_HOME, 'server.log')}`)
}

export function alive(pid: number): boolean {
  try { process.kill(pid, 0); return true } catch (e) { return (e as NodeJS.ErrnoException).code === 'EPERM' }
}
const groupAlive = (pid: number): boolean => { try { process.kill(-pid, 0); return true } catch { return false } }

/** Stops the server yk started (whole process group). Never touches anything else. */
export async function serverStop(): Promise<void> {
  const pid = parseInt(text(join(DEV_HOME, 'server.pid')).trim(), 10)
  if (!Number.isNaN(pid)) {
    if (groupAlive(pid)) {
      try { process.kill(-pid, 'SIGTERM') } catch { /* gone */ }
      for (let i = 0; i < 20 && groupAlive(pid); i++) await sleep(1000)
      try { process.kill(-pid, 'SIGKILL') } catch { /* gone */ }
    }
    rmSync(join(DEV_HOME, 'server.pid'), { force: true })
    rmSync(join(DEV_HOME, 'server.env'), { force: true })
  }
}

/** The process on the dev port is the server yk started. */
export function ours(): boolean {
  const pid = parseInt(text(join(DEV_HOME, 'server.pid')).trim(), 10)
  return !Number.isNaN(pid) && alive(pid)
}

// ---- a lock that an exiting process cannot leave behind: a file with the pid of its holder
export function tryLock(path: string): (() => void) | null {
  mkdirp(dirname(path))
  for (let i = 0; i < 2; i++) {
    try {
      const fd = openSync(path, 'wx')
      writeFileSync(fd, `${process.pid}\n`)
      closeSync(fd)
      const release = (): void => { if (text(path).trim() === String(process.pid)) rmSync(path, { force: true }) }
      process.on('exit', release)
      return release
    } catch {
      const pid = parseInt(text(path).trim(), 10)
      if (!Number.isNaN(pid) && alive(pid)) return null
      rmSync(path, { force: true }) // a stale lock
    }
  }
  return null
}
export function lockHeld(path: string): boolean {
  const pid = parseInt(text(path).trim(), 10)
  return !Number.isNaN(pid) && alive(pid)
}
export async function lock(path: string, waitMs = 600000): Promise<() => void> {
  const end = Date.now() + waitMs
  for (;;) {
    const r = tryLock(path)
    if (r) return r
    if (Date.now() > end) die(`could not take ${path}`)
    await sleep(500)
  }
}

/** Makes the dev server run the current origin/main (or a release). A no-op when it already does; when it is stale and busy the swap is left for a later call. */
export async function serverUp(args: string[] = []): Promise<number> {
  let kind = 'main'
  let tag = ''
  if (args[0] === '--release') { kind = 'release'; tag = args[1] ?? '' }
  need('git')
  if (kind === 'main') need('java')
  mkdirp(DEV_HOME)
  const release = await lock(join(DEV_HOME, 'up.lock'))
  try {
    let id: string
    let label: string
    let cmd: string[]
    let rules: string
    if (kind === 'release') {
      const r = run(['bun', join(HERE, 'src/release.ts'), ...(tag ? ['--version', tag] : [])])
      if (r.code) { console.error(r.stderr); return 1 }
      const bin = r.stdout.trim().split('\n').pop()!
      tag = basename(dirname(dirname(bin)))
      id = `release-${tag}`; label = `release ${tag}`; cmd = [bin]; rules = join(REPO_ROOT, 'examples/11-dogfood/rules.json')
    } else {
      id = mainSync()
      mainBuild(id)
      label = `main@${id.slice(0, 9)}`
      cmd = ['java', '-jar', join(DEV_HOME, 'builds', id, 'yukta.jar')]
      rules = join(DEV_HOME, 'main/examples/11-dogfood/rules.json')
    }
    if (await healthy()) {
      if (!ours()) die(`port ${DEV_PORT} is used by a server yk did not start; stop it or set YUKTA_DEV_PORT`)
      // a server started before yk set an API key is replaced like a stale one
      if (serverEnv('YUKTA_DEV_SHA') === id && serverEnv('YUKTA_DEV_KEYED') === '2') { console.log(`ready: ${DEV_URL} (${label})`); return 0 }
      const n = await busy()
      if (n > 0) { console.log(`STALE: the server runs ${serverEnv('YUKTA_DEV_LABEL')}, ${label} is built; swap pending (${n} executions running)`); return 0 }
      console.error(`swapping ${serverEnv('YUKTA_DEV_LABEL')} for ${label}`)
      await serverStop()
    } else if (await portOpen(Number(DEV_PORT))) die(`port ${DEV_PORT} is in use by another program; free it or set YUKTA_DEV_PORT`)
    await serverLaunch(kind, id, label, rules, cmd)
    await pickupResume()
    return 0
  } finally { release() }
}

function portOpen(port: number): Promise<boolean> {
  return new Promise(r => {
    const s = createConnection({ port, host: '127.0.0.1' })
    s.on('connect', () => { s.destroy(); r(true) })
    s.on('error', () => r(false))
  })
}

/** Before a run: starts the dev server if it is down, and swaps an idle server for an already built newer main (no fetch, no build:
 * `yk up` and the SessionStart hook do those). YUKTA_SERVER_URL pointing elsewhere opts out: that server is used as it is. */
export async function ensureServer(): Promise<void> {
  if (process.env.YUKTA_SERVER_URL && process.env.YUKTA_SERVER_URL !== DEV_URL) return
  process.env.YUKTA_SERVER_URL = DEV_URL
  if (!(await healthy())) {
    console.error(`>> no dev server on ${DEV_URL}; starting one (yk up)`)
    const code = await serverUp()
    if (code) process.exit(code)
    return
  }
  if (!ours() || serverEnv('YUKTA_DEV_KIND') !== 'main') return
  const fetched = join(DEV_HOME, 'fetched')
  if (!isFile(fetched) || Date.now() - statSync(fetched).mtimeMs > 60000) {
    writeFileSync(fetched, '')
    utimesSync(fetched, new Date(), new Date())
    git(['fetch', '-q', 'origin', 'main'], join(DEV_HOME, 'main'))
  }
  const r = git(['rev-parse', process.env.YUKTA_DEV_MAIN_REF ?? 'origin/main'], join(DEV_HOME, 'main'))
  if (r.code) return
  const sha = r.stdout.trim()
  const built = isFile(join(DEV_HOME, 'builds', sha, 'yukta.jar')) && statSync(join(DEV_HOME, 'builds', sha, 'yukta.jar')).size > 0
  if (sha !== serverEnv('YUKTA_DEV_SHA') && !built) run([process.execPath, join(HERE, 'src/yk.ts'), 'up', '--background'])
  if ((sha !== serverEnv('YUKTA_DEV_SHA') || serverEnv('YUKTA_DEV_KEYED') !== '2') && built && (await busy()) === 0) await serverUp()
}

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
  (progress.tasks as Task[]).filter(t => !BAD_TIME(t.startTime) && !BAD_TIME(t.endTime)).map(t => ({ node: t.nodeId, s: secs(t.startTime!), e: secs(t.endTime!) }))

/** The `result` event of the monitoring log: how the run ended, its wall time, and the seconds each node took. Every run ends in exactly one result
 * with a terminal status. */
export function emitResult(session: string, exec: string, status: string, failed: string, healed: string, progress: Json): void {
  const events = healEventsFile()
  if (!events || RUNNING.test(status)) return
  try {
    if (isFile(events) && text(events).split('\n').some(l => l.includes(`"run":"${exec}"`) && l.includes('"event":"result"'))) return
    const project = process.env.YUKTA_PROJECT || REPO_ROOT
    mkdirp(dirname(events))
    const t = finished(progress)
    const dur: Record<string, number> = {}
    for (const x of t) dur[nodeStage(x.node)] = (dur[nodeStage(x.node)] ?? 0) + (x.e - x.s)
    const ev = {
      v: 1, ts: isoNow(), run: exec, session, project: basename(project), event: 'result', workflow: progress.workflowId ?? '', status, failed_node: failed, healed,
      scope: process.env.YUKTA_SCOPE ?? 'all', sha: git(['rev-parse', '--short=9', 'HEAD'], project).stdout.trim() || 'unknown',
      branch: git(['rev-parse', '--abbrev-ref', 'HEAD'], project).stdout.trim() || 'unknown',
      secs: t.length ? r1(Math.max(...t.map(x => x.e)) - Math.min(...t.map(x => x.s))) : null,
      durations: Object.fromEntries(Object.entries(dur).sort(([a], [b]) => (a < b ? -1 : 1)).map(([k, v]) => [k, r1(v)])),
    }
    appendFileSync(events, JSON.stringify(ev) + '\n')
  } catch { /* monitoring must not break a run */ }
}

/** The directory with the heal records of the last check run: YUKTA_HEAL_RECORDS when set, else the session's own. */
export const healRecords = (session: string): string => process.env.YUKTA_HEAL_RECORDS ?? join(STATE_DIR, 'heal', session)

const recordFiles = (dir: string): string[] => (isDir(dir) ? readdirSync(dir).filter(f => /-[0-9][^/]*\.json$/.test(f)).sort().map(f => join(dir, f)) : [])

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
  const t = finished({ tasks: (progress.tasks as Task[]).map(x => ({ ...x, startTime: x.startTime?.replace(/\.\d+/, ''), endTime: x.endTime?.replace(/\.\d+/, '') })) })
  return t.length ? String(Math.max(...t.map(x => x.e)) - Math.min(...t.map(x => x.s))) : ''
}

/** One tab separated row per run in $STATE_DIR/runs.tsv (yk stats reads it). */
export function recordRun(status: string, kind: string, wall: string, cost: string, bytes: number, healed: string): void {
  try {
    mkdirp(STATE_DIR)
    appendFileSync(join(STATE_DIR, 'runs.tsv'), [isoNow(), kind, status, wall, cost, bytes, healed].join('\t') + '\n')
  } catch { /* best effort */ }
}

/** The plan on stdin (yk plan's lines) without the stages not in ONLY (a comma list, "" = all) or in SKIP. A stage name matches with - or _ either way. */
export function filterPlan(plan: string, only: string, skip: string): string {
  const norm = (s: string): string => s.replace(/-/g, '_')
  const O = new Set(only.split(',').filter(Boolean).map(norm))
  const K = new Set(skip.split(',').filter(Boolean).map(norm))
  return plan.split('\n').filter(l => l.startsWith('#') || (l.length > 0 && (O.size === 0 || O.has(norm(l.split('\t')[0]!))) && !K.has(norm(l.split('\t')[0]!)))).join('\n')
}

/** The whole output of the failed stage (the stdout in the payload of the terminal that ended the run FAILED). */
export function failedOutput(session: string, exec: string): string {
  const r = run([findCli(), '-o', 'json', 'result', session, exec])
  const docs = r.stdout.split('\n').filter(l => l.trim()).map(tryJson).filter(Boolean)
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

// ---- the optional decision model (System One via Ollama), see decisions.json. Everything here fails open.
const dec = (): Json => readJson(join(HERE, 'decisions.json'))
export const decisionModel = (): string => process.env.YUKTA_DECISION_MODEL || dec().model || ''
export const decisionUrl = (): string => process.env.YUKTA_DECISION_URL || dec().url || ''

async function decisionCall(url: string, body: Json): Promise<{ code: string; ms: number; body: string }> {
  const t0 = Date.now()
  const r = await http('POST', `${url}/v1/systemone`, { headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body), timeoutMs: 60000 })
  return { code: r.code === 0 ? '000' : String(r.code), ms: r.code === 0 ? 0 : Date.now() - t0, body: r.body }
}
const decisionBody = (model: string, state: string, question: string, criteria: Json): Json => ({ model, state, questions: { q: { type: 'choice', instructions: question, criteria } }, keep_alive: '10m' })

/** shadow (default: ask, record, and let the caller's static path decide), on (use the answer) or off (do not ask). */
export function decisionMode(name: string): string {
  const v = process.env[`YUKTA_DECISION_${name.toUpperCase().replace(/-/g, '_')}`]
  return v || dec().decisions?.[name]?.mode || process.env.YUKTA_DECISION || 'shadow'
}
export function decisionLog(kind: string, name: string, hash: string, mode: string, label: string, prob: string, ms: string): void {
  try {
    mkdirp(STATE_DIR)
    appendFileSync(join(STATE_DIR, 'decisions.tsv'), [isoNow(), kind, name, hash, mode, label, prob, ms].join('\t') + '\n')
  } catch { /* best effort */ }
}
export const decisionHash = (s: string): string => sha1(s).slice(0, 12)
/** What really happened for a decision made on STATE: the report joins it to the decision by the hash of STATE. */
export const decisionOutcome = (name: string, state: string, label: string): void => decisionLog('outcome', name, decisionHash(state), '-', label, '-', '-')

function decisionReason(code: string, body: string, model: string): string {
  if (body.includes('no longer supported')) return `'${model}' was downloaded in a format that is no longer supported; run: ollama pull ${model}`
  if (body.includes('not found')) return `model '${model}' is not pulled; run: ollama pull ${model}`
  return code === '000' ? `Ollama is not reachable at ${decisionUrl()} (${body.slice(0, 120)}); run: ollama serve` : `HTTP ${code}: ${body.slice(0, 160)}`
}

/** [label, probability] for the model's choice, or null. Only in mode on does the caller use it: in shadow mode this asks, records the answer in
 * decisions.tsv and returns null (the caller's static path decides). Returns null, with a WARN on stderr, when there is no answer. */
export async function decide(name: string, state: string, question: string, criteria: Json): Promise<[string, string] | null> {
  const mode = decisionMode(name)
  if (mode === 'off') return null
  const model = decisionModel()
  const url = decisionUrl()
  const max = Number(process.env.YUKTA_DECISION_MAX_INPUT || dec().maxInput || 1024)
  if (!model) { console.error('WARN decision model unavailable: none configured (YUKTA_DECISION_MODEL)'); return null }
  if (state.length + question.length > max) { console.error(`WARN decision model unavailable: the digest is ${state.length + question.length} characters, over the cap of ${max}`); return null }
  const r = await decisionCall(url, decisionBody(model, state, question, criteria))
  if (r.code !== '200') { console.error(`WARN decision model unavailable: ${decisionReason(r.code, r.body, model)}`); return null }
  const q = tryJson(r.body)?.answers?.q
  if (!q || q.choice === undefined) { console.error('WARN decision model unavailable: unreadable answer'); return null }
  const label = String(q.choice)
  const prob = String(q.probabilities?.[q.choice])
  decisionLog('decision', name, decisionHash(state), mode, label, prob, String(r.ms))
  return mode === 'on' ? [label, prob] : null
}

/** One line "decision: ..." saying whether the decision model works; [line, ok]. Kept for probeCacheSeconds in $DEV_HOME/decision.cache. */
export async function decisionProbe(fresh = false): Promise<[string, boolean]> {
  if ((process.env.YUKTA_DECISION ?? 'shadow') === 'off') return ['decision: off (YUKTA_DECISION=off)', false]
  const model = decisionModel()
  const url = decisionUrl()
  const cache = join(DEV_HOME, 'decision.cache')
  const ttl = Number(dec().probeCacheSeconds || 600)
  if (!fresh && !process.env.YK_DECISION_FRESH && isFile(cache) && text(cache).split('\n')[0] === `${model} ${url}` && (Date.now() - statSync(cache).mtimeMs) / 1000 < ttl) {
    const l = text(cache).split('\n')[1] ?? ''
    return [l, l.startsWith('decision: ok')]
  }
  const crit = { ok: 'the probe', no: 'never' }
  let line: string
  const tags = await http('GET', `${url}/api/tags`, { timeoutMs: 3000 })
  const verRes = await http('GET', `${url}/api/version`, { timeoutMs: 3000 })
  const ver = tryJson(verRes.body)?.version as string | undefined
  if (!model) {
    const names = tryJson(tags.body)?.models?.map((m: Json) => m.name).join(', ')
    line = `decision: not configured; set YUKTA_DECISION_MODEL (installed: ${names ?? `Ollama not reachable at ${url}`})`
  } else if (!ver) line = `decision: unavailable, Ollama is not reachable at ${url}; run: ollama serve`
  else if (versionLess(ver, String(dec().minOllama))) line = `decision: unavailable, Ollama ${ver} is older than ${dec().minOllama}; update Ollama`
  else {
    const small = await decisionCall(url, decisionBody(model, 'Is this a probe? Yes.', 'Is this a probe?', crit))
    if (small.code !== '200') line = `decision: unavailable, ${decisionReason(small.code, small.body, model)}`
    else {
      const big = await decisionCall(url, decisionBody(model, 'x'.repeat(1000), 'Is this a probe?', crit))
      line = `decision: ok ${model} at ${url} (Ollama ${ver}), ${small.ms} ms for a small digest, ${Math.max(0, big.ms - small.ms)} ms per KB`
      if (small.ms > Number(dec().slowMs)) line += `; WARN slower than ${dec().slowMs} ms, keep it off the green path`
    }
  }
  try { mkdirp(DEV_HOME); writeFileSync(cache, `${model} ${url}\n${line}\n`) } catch { /* best effort */ }
  return [line, line.startsWith('decision: ok')]
}

function versionLess(a: string, b: string): boolean {
  const pa = a.split('.').map(Number)
  const pb = b.split('.').map(Number)
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    if ((pa[i] ?? 0) !== (pb[i] ?? 0)) return (pa[i] ?? 0) < (pb[i] ?? 0)
  }
  return false
}

export function parseArgs(argv: string[], spec: { value?: string[]; bool?: string[] }, usage: string): { pos: string[]; opt: Record<string, string>; flag: Set<string> } {
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
  const sorted = (x: Json): Json => (Array.isArray(x) ? x.map(sorted) : x && typeof x === 'object' ? Object.fromEntries(Object.keys(x).sort().map(k => [k, sorted(x[k])])) : x)
  const compact = (x: Json): string =>
    Array.isArray(x) ? '[' + x.map(compact).join(', ') + ']'
      : x && typeof x === 'object' ? '{' + Object.keys(x).filter(k => x[k] !== undefined).map(k => JSON.stringify(k) + ': ' + compact(x[k])).join(', ') + '}'
        : JSON.stringify(x) ?? 'null'
  const data = opts.sort ? sorted(v) : v
  const s = opts.indent ? JSON.stringify(data, null, opts.indent) : compact(data)
  return s.replace(/[\u0080-￿]/g, c => '\\u' + c.charCodeAt(0).toString(16).padStart(4, '0'))
}
