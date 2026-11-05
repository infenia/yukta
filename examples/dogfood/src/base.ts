// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// Dogfood helpers: process, file and path helpers, and where things live. `lib.ts` re-exports every module.
import { spawnSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { appendFileSync, existsSync, mkdirSync, readFileSync, readdirSync, renameSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { basename, dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

export type Json = any
export {
  existsSync,
  join,
  dirname,
  basename,
  resolve,
  readFileSync,
  writeFileSync,
  mkdirSync,
  readdirSync,
  rmSync,
  statSync,
  appendFileSync,
  renameSync,
}

/** examples/dogfood */
export const HERE = resolve(dirname(fileURLToPath(import.meta.url)), '..')
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

export function run(
  cmd: string[],
  opts: { cwd?: string; input?: string; env?: Record<string, string | undefined>; timeoutMs?: number } = {},
): Proc {
  const env = opts.env ? { ...process.env, ...opts.env } : process.env
  for (const k of Object.keys(env)) if (env[k] === undefined) delete env[k]
  try {
    checkProgram(cmd[0]!)
  } catch (e) {
    return { code: 126, stdout: '', stderr: (e as Error).message }
  }
  const r = spawnSync(cmd[0]!, cmd.slice(1), {
    cwd: opts.cwd,
    input: opts.input,
    encoding: 'utf8',
    env: env as NodeJS.ProcessEnv,
    maxBuffer: 1 << 28,
    timeout: opts.timeoutMs,
  })
  if (r.error) return { code: 127, stdout: '', stderr: String(r.error.message) }
  return { code: r.status ?? (r.signal ? 128 : 1), stdout: r.stdout ?? '', stderr: r.stderr ?? '' }
}

/** A line through the shell (the commands of the stages are shell lines). */
export const sh = (line: string, cwd?: string, env?: Record<string, string | undefined>): Proc => run(['bash', '-c', line], { cwd, env })

export function git(args: string[], cwd?: string): Proc {
  return run(['git', ...(cwd ? ['-C', cwd] : []), ...args])
}

export function die(msg: string, code = 1): never {
  console.error(`error: ${msg}`)
  process.exit(code)
}

export function onPath(cmd: string): string | null {
  for (const d of (process.env.PATH ?? '').split(':')) {
    const f = join(d || '.', cmd)
    try {
      if (statSync(f).isFile()) return f
    } catch {
      /* not here */
    }
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
  if (/[\n\t\r]/.test(s))
    return "$'" + s.replace(/\\/g, '\\\\').replace(/'/g, "\\'").replace(/\n/g, '\\n').replace(/\t/g, '\\t').replace(/\r/g, '\\r') + "'"
  return s.replace(/[^A-Za-z0-9_@%+=:,./-]/g, (c) => '\\' + c)
}

/** jq @sh: a single-quoted word. */
export const sq = (s: string): string => "'" + s.replace(/'/g, "'\\''") + "'"

export const text = (p: string, d = ''): string => {
  try {
    return readFileSync(p, 'utf8')
  } catch {
    return d
  }
}
export const readJson = (p: string): Json => JSON.parse(readFileSync(p, 'utf8'))
export function tryJson(s: string): Json | undefined {
  try {
    return JSON.parse(s)
  } catch {
    return undefined
  }
}
export const isFile = (p: string): boolean => {
  try {
    return statSync(p).isFile()
  } catch {
    return false
  }
}
export const isDir = (p: string): boolean => {
  try {
    return statSync(p).isDirectory()
  } catch {
    return false
  }
}
export const mkdirp = (p: string): void => void mkdirSync(p, { recursive: true })
export const sha1 = (s: string | Buffer): string => createHash('sha1').update(s).digest('hex')
export const isoNow = (): string => new Date().toISOString().replace(/\.\d+Z$/, 'Z')
export const sleep = (ms: number): Promise<void> => new Promise((r) => setTimeout(r, ms))

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
