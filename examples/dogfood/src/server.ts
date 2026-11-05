// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// Dogfood helpers: the dev server (built from origin/main), pickup start, locks, up/ensure. `lib.ts` re-exports every module.
import { spawn } from 'node:child_process'
import { closeSync, openSync, utimesSync } from 'node:fs'
import { createConnection } from 'node:net'
import { tmpdir } from 'node:os'
import {
  DEV_HOME,
  DEV_PORT,
  DEV_URL,
  HERE,
  Json,
  REPO_ROOT,
  basename,
  die,
  dirname,
  existsSync,
  git,
  isDir,
  isFile,
  join,
  mkdirp,
  need,
  osArch,
  readdirSync,
  renameSync,
  rmSync,
  run,
  sh,
  sleep,
  sq,
  statSync,
  text,
  tryJson,
  writeFileSync,
} from './base.ts'
import {
  checkSession,
  cliJson,
  executable,
  findCli,
  http,
  keyHeader,
  newestBuildCli,
  renderSession,
  serverEnv,
  serverProperties,
} from './client.ts'

// ---- the dev server: built from origin/main, one per machine, on $DEV_PORT
export async function healthy(url = DEV_URL): Promise<boolean> {
  return (await http('GET', `${url}/actuator/health`, { timeoutMs: 3000 })).code === 200
}

export const RUNNING = /RUNNING|PENDING|QUEUED|PAUSED/
/** The executions of the dev server (any session). */
export async function executions(): Promise<Json[] | null> {
  const r = await http('GET', `${DEV_URL}/api/executions?limit=1000`, { headers: keyHeader(), timeoutMs: 5000 })
  const j = tryJson(r.body)
  if (r.code !== 200 || j === undefined) return null
  return Array.isArray(j) ? j : (j.data ?? null)
}

/** The number of executions not yet ended, on any session, except the `pickup` schedule. When it cannot be read it says 1: never stop a server we cannot see idle. */
export async function busy(): Promise<number> {
  const ex = await executions()
  return ex ? ex.filter((e) => e.workflowId !== 'pickup' && RUNNING.test(e.status ?? '')).length : 1
}

export async function pickupAlive(): Promise<boolean> {
  const id = text(join(DEV_HOME, 'pickup.exec')).trim()
  if (!id) return false
  const ex = await executions()
  return !!ex?.some((e) => e.executionId === id && RUNNING.test(e.status ?? ''))
}

/** Applies the library and starts `pickup` on session dogfood; prints the execution id and keeps it in pickup.exec. */
export function pickupStart(): string | null {
  const tmp = join(tmpdir(), `yk-pickup-${process.pid}`)
  mkdirp(tmp)
  try {
    renderSession(join(tmp, 'session.json'))
    const a = run([findCli(), 'session', 'apply', join(tmp, 'session.json')])
    if (a.code) {
      console.error(a.stdout + a.stderr)
      return null
    }
    const id = cliJson(['workflow', 'start', checkSession(), 'pickup', '--payload', '{}']).data?.executionId
    if (!id) return null
    mkdirp(DEV_HOME)
    writeFileSync(join(DEV_HOME, 'pickup.exec'), id + '\n')
    return id
  } finally {
    rmSync(tmp, { recursive: true, force: true })
  }
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
      for (const d of readdirSync(src))
        if (!existsSync(join(wt, 'ui/.gradle', d))) run(['cp', '-r', '--reflink=auto', join(src, d), join(wt, 'ui/.gradle/')])
      break
    }
  }
  console.error(`building main@${sha.slice(0, 9)} (bootJar + CLI; minutes on a new commit, log: ${log}) ...`)
  const b = sh(`./gradlew :boot:bootJar :cli:goBuild -q --console=plain >${sq(log)} 2>&1`, wt)
  if (b.code) die(`build of main@${sha.slice(0, 9)} failed; see ${log}`)
  const libs = join(wt, 'boot/build/libs')
  const jar = isDir(libs)
    ? readdirSync(libs)
        .filter((f) => f.endsWith('.jar') && !f.includes('plain'))
        .map((f) => join(libs, f))
        .sort((a, c) => statSync(c).mtimeMs - statSync(a).mtimeMs)[0]
    : undefined
  if (!jar) die(`no boot jar after the build; see ${log}`)
  rmSync(`${out}.tmp`, { recursive: true, force: true })
  mkdirp(`${out}.tmp`)
  run(['cp', jar, join(`${out}.tmp`, 'yukta.jar')])
  run(['cp', join(wt, 'cli/build', `yukta-cli-${osArch()}`), join(`${out}.tmp`, 'yukta-cli')])
  renameSync(`${out}.tmp`, out)
  const dirs = readdirSync(join(DEV_HOME, 'builds'))
    .map((d) => join(DEV_HOME, 'builds', d))
    .filter(isDir)
    .sort((a, c) => statSync(c).mtimeMs - statSync(a).mtimeMs)
  for (const d of dirs.slice(3)) rmSync(d, { recursive: true, force: true })
}

/** Starts the dev server in its own process group (so serverStop stops it with its children) and records it in $DEV_HOME/server.env. */
export async function serverLaunch(kind: string, id: string, label: string, rules: string, cmd: string[]): Promise<void> {
  mkdirp(join(DEV_HOME, 'sessions'))
  rmSync(join(DEV_HOME, 'server.pid'), { force: true })
  const par = process.env.YUKTA_DEV_GRADLE_PARALLEL ?? '2'
  // The key goes in a file, not the environment: the server's children (the gradle stages and their test JVMs) inherit its environment.
  writeFileSync(join(DEV_HOME, 'server.properties'), serverProperties(), { mode: 0o600 })
  const logFd = openSync(join(DEV_HOME, 'server.log'), 'w')
  const child = spawn(cmd[0]!, [...cmd.slice(1), `--spring.config.additional-location=file:${join(DEV_HOME, 'server.properties')}`], {
    cwd: DEV_HOME,
    detached: true,
    stdio: ['ignore', logFd, logFd],
    env: {
      ...process.env,
      SERVER_PORT: DEV_PORT,
      YUKTA_SERVER_ADDRESS: '127.0.0.1',
      YUKTA_PROCESS_ALLOW_ANY: 'true',
      YUKTA_CONCURRENCY_GROUPS_GRADLE: par,
      YUKTA_CONCURRENCY_GROUPS_TOOL: par,
      YUKTA_DEV_HOME: DEV_HOME,
      YUKTA_SESSION_BASE_DIR: join(DEV_HOME, 'sessions'),
      YUKTA_SESSION_EXECUTION_TIMEOUT_SECONDS: '86400',
      YUKTA_SUPERVISOR_RULES_FILE: rules,
    },
  })
  child.unref()
  closeSync(logFd)
  writeFileSync(join(DEV_HOME, 'server.pid'), `${child.pid}\n`)
  let cli = join(DEV_HOME, 'builds', id, 'yukta-cli')
  if (!executable(cli)) cli = newestBuildCli()
  writeFileSync(
    join(DEV_HOME, 'server.env'),
    `YUKTA_DEV_KIND=${kind}\nYUKTA_DEV_SHA=${id}\nYUKTA_DEV_LABEL=${label}\nYUKTA_DEV_CLI=${cli}\nYUKTA_DEV_KEYED=2\nYUKTA_SERVER_URL=${DEV_URL}\n`,
  )
  console.error(`waiting for Yukta at ${DEV_URL} (log: ${join(DEV_HOME, 'server.log')}) ...`)
  for (let i = 0; i < 60; i++) {
    if (!alive(child.pid!)) {
      rmSync(join(DEV_HOME, 'server.env'), { force: true })
      die(`server exited early; see ${join(DEV_HOME, 'server.log')}`)
    }
    if (await healthy()) {
      console.log(`ready: ${DEV_URL} (${label})`)
      return
    }
    await sleep(2000)
  }
  die(`server did not become healthy in 120s; see ${join(DEV_HOME, 'server.log')}`)
}

export function alive(pid: number): boolean {
  try {
    process.kill(pid, 0)
    return true
  } catch (e) {
    return (e as NodeJS.ErrnoException).code === 'EPERM'
  }
}
const groupAlive = (pid: number): boolean => {
  try {
    process.kill(-pid, 0)
    return true
  } catch {
    return false
  }
}

/** Stops the server yk started (whole process group). Never touches anything else. */
export async function serverStop(): Promise<void> {
  const pid = parseInt(text(join(DEV_HOME, 'server.pid')).trim(), 10)
  if (!Number.isNaN(pid)) {
    if (groupAlive(pid)) {
      try {
        process.kill(-pid, 'SIGTERM')
      } catch {
        /* gone */
      }
      for (let i = 0; i < 20 && groupAlive(pid); i++) await sleep(1000)
      try {
        process.kill(-pid, 'SIGKILL')
      } catch {
        /* gone */
      }
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
      const release = (): void => {
        if (text(path).trim() === String(process.pid)) rmSync(path, { force: true })
      }
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
  if (args[0] === '--release') {
    kind = 'release'
    tag = args[1] ?? ''
  }
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
      if (r.code) {
        console.error(r.stderr)
        return 1
      }
      const bin = r.stdout.trim().split('\n').pop()!
      tag = basename(dirname(dirname(bin)))
      id = `release-${tag}`
      label = `release ${tag}`
      cmd = [bin]
      rules = join(REPO_ROOT, 'examples/dogfood/rules.json')
    } else {
      id = mainSync()
      mainBuild(id)
      label = `main@${id.slice(0, 9)}`
      cmd = ['java', '-jar', join(DEV_HOME, 'builds', id, 'yukta.jar')]
      rules = join(DEV_HOME, 'main/examples/dogfood/rules.json')
    }
    if (await healthy()) {
      if (!ours()) die(`port ${DEV_PORT} is used by a server yk did not start; stop it or set YUKTA_DEV_PORT`)
      // a server started before yk set an API key is replaced like a stale one
      if (serverEnv('YUKTA_DEV_SHA') === id && serverEnv('YUKTA_DEV_KEYED') === '2') {
        console.log(`ready: ${DEV_URL} (${label})`)
        return 0
      }
      const n = await busy()
      if (n > 0) {
        console.log(`STALE: the server runs ${serverEnv('YUKTA_DEV_LABEL')}, ${label} is built; swap pending (${n} executions running)`)
        return 0
      }
      console.error(`swapping ${serverEnv('YUKTA_DEV_LABEL')} for ${label}`)
      await serverStop()
    } else if (await portOpen(Number(DEV_PORT))) die(`port ${DEV_PORT} is in use by another program; free it or set YUKTA_DEV_PORT`)
    await serverLaunch(kind, id, label, rules, cmd)
    await pickupResume()
    return 0
  } finally {
    release()
  }
}

function portOpen(port: number): Promise<boolean> {
  return new Promise((r) => {
    const s = createConnection({ port, host: '127.0.0.1' })
    s.on('connect', () => {
      s.destroy()
      r(true)
    })
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
