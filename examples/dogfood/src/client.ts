// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// Dogfood helpers: keys, the CLI, sessions, notifying the Claude Code session, http. `lib.ts` re-exports every module.
import { randomBytes } from 'node:crypto'
import { chmodSync } from 'node:fs'
import { createConnection } from 'node:net'
import {
  DEV_HOME,
  DEV_URL,
  HERE,
  Json,
  REPO_ROOT,
  die,
  existsSync,
  git,
  isDir,
  isFile,
  join,
  mkdirp,
  onPath,
  osArch,
  readJson,
  readdirSync,
  run,
  sha1,
  statSync,
  text,
  tryJson,
  writeFileSync,
} from './base.ts'

// ---- keys
function devSecret(name: string): string {
  const file = join(DEV_HOME, name)
  if (!isFile(file) || statSync(file).size === 0) {
    mkdirp(DEV_HOME)
    writeFileSync(
      file,
      randomBytes(48)
        .toString('base64')
        .replace(/[^A-Za-z0-9]/g, '')
        .slice(0, 32) || randomBytes(16).toString('hex'),
      { mode: 0o600 },
    )
    chmodSync(file, 0o600)
  }
  return text(file)
}
/** The dev server's API key (generated once, readable only by this user). */
export const devKey = (): string => devSecret('api-key')
/** A second, named VIEWER key of the dev server for the headless fixers (they may read a run over MCP, never change it). */
export const fixerKey = (): string => devSecret('fixer-key')
export const sessionKey = (): string => devSecret('session-key')
/** Whether the server built from origin/main knows `agent` on an API key (a property it does not know stops it from starting). */
export const agentKeysSupported = (): boolean =>
  text(join(DEV_HOME, 'main/core/src/main/java/com/infenia/yukta/security/ApiKeys.java')).includes('boolean agent')
/** The key of the Yukta MCP: the Claude Code session of `yk pickup session` (YUKTA_MCP_KEY=session) gets an OPERATOR agent key limited to the dogfood session, which may
 * not answer a request of risk HIGH; every other session keeps the dev key. */
export const mcpKey = (): string => (process.env.YUKTA_MCP_KEY === 'session' && agentKeysSupported() ? sessionKey() : devKey())
/** The properties file of the dev server: its keys. */
export function serverProperties(): string {
  const keys = [
    'yukta.api-key=' + devKey(),
    'yukta.api-keys[0].name=fixer',
    'yukta.api-keys[0].key=' + fixerKey(),
    'yukta.api-keys[0].role=VIEWER',
    'yukta.api-keys[0].sessions=*',
  ]
  // a session of the dogfood plus the ticket workflows is over the 256 KB default body (`session apply` answers 413); loopback only
  keys.push('spring.http.codecs.max-in-memory-size=8MB') // Boot 4 name; spring.codec.* (application.yaml) is ignored
  if (agentKeysSupported())
    keys.push(
      'yukta.api-keys[1].name=claude-session',
      'yukta.api-keys[1].key=' + sessionKey(),
      'yukta.api-keys[1].role=OPERATOR',
      'yukta.api-keys[1].sessions=dogfood',
      'yukta.api-keys[1].agent=true',
    )
  // the route of the ticket gates (ticket-session.json): a route nobody configured is refused, so without it `yk ticket run` stops at its first question
  if (existsSync(join(DEV_HOME, 'main/core/src/main/java/com/infenia/yukta/service/input/route/RouteSettings.java'))) {
    const r = 'yukta.input.routes.ticket.'
    keys.push(
      r + 'tiers[0].name=supervisor',
      r + 'tiers[0].responders[0]=supervisor:*',
      r + 'tiers[0].timeout=20s',
      r + 'tiers[0].mode=FIRST',
      r + 'tiers[0].min-confidence=0.85',
      r + 'tiers[0].type=AUTO',
      r + 'tiers[1].name=session',
      r + 'tiers[1].responders[0]=role:OPERATOR',
      r + 'tiers[1].timeout=30m',
      r + 'tiers[1].type=AUTO',
      r + 'tiers[2].name=human',
      r + 'tiers[2].responders[0]=role:OPERATOR',
      r + 'tiers[2].timeout=24h',
      r + 'tiers[2].type=HUMAN',
      r + 'on-final-timeout=FAIL',
    )
  }
  return keys.join('\n') + '\n'
}
/** Hands the key to the yukta CLI (YUKTA_TOKEN) when it talks to the dev server. */
export function useDevKey(): void {
  if (!process.env.YUKTA_TOKEN && (process.env.YUKTA_SERVER_URL ?? DEV_URL) === DEV_URL) process.env.YUKTA_TOKEN = devKey()
}
/** A value from the running dev server's state file, or ''. */
export function serverEnv(key: string): string {
  const m = new RegExp(`^${key}=(.*)$`, 'm').exec(text(join(DEV_HOME, 'server.env')))
  return m ? m[1]! : ''
}

export function newestBuildCli(): string {
  const dir = join(DEV_HOME, 'builds')
  if (!isDir(dir)) return ''
  const f = readdirSync(dir)
    .map((d) => join(dir, d, 'yukta-cli'))
    .filter(isFile)
    .sort((a, b) => statSync(b).mtimeMs - statSync(a).mtimeMs)
  return f[0] ?? ''
}
export const executable = (p: string): boolean => {
  try {
    return statSync(p).isFile() && (statSync(p).mode & 0o111) !== 0
  } catch {
    return false
  }
}

/** Locate the yukta CLI: $YUKTA_BIN, the CLI built with the running server, the newest main build, PATH, then this checkout's cli/build. */
export function findCli(): string {
  if (process.env.YUKTA_BIN) return process.env.YUKTA_BIN
  let f = serverEnv('YUKTA_DEV_CLI')
  if (f && executable(f)) return f
  f = newestBuildCli()
  if (f && executable(f)) return f
  const onp = onPath('yukta-cli')
  if (onp) return onp
  for (const c of [join(REPO_ROOT, 'cli/build', `yukta-cli-${osArch()}`), join(REPO_ROOT, 'cli/build/yukta-cli')])
    if (executable(c)) return c
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
  let d = join(DEV_HOME, 'main/examples/dogfood')
  if (!isFile(join(d, 'session.json'))) d = HERE
  const project = d.replace(/\/examples\/dogfood$/, '')
  const sub = (v: Json): Json =>
    typeof v === 'string'
      ? v.split('__PROJECT_PATH__').join(project).split('__DOGFOOD__').join(d)
      : Array.isArray(v)
        ? v.map(sub)
        : v && typeof v === 'object'
          ? Object.fromEntries(Object.entries(v).map(([k, x]) => [k, sub(x)]))
          : v
  const s = sub(readJson(join(d, 'session.json')))
  s.sessionId = 'dogfood'
  s.name = 'dogfood'
  // the ticket pipeline's workflows (ticket-session.json) are part of the one session: every apply (yk check, gradle, tool, ticket run) sends the same set, so
  // none of them removes the others' workflows from a run in progress ("Workflow not found: finalize")
  let t = join(DEV_HOME, 'main/examples/dogfood')
  if (!isFile(join(t, 'ticket-session.json'))) t = HERE
  if (isFile(join(t, 'ticket-session.json'))) {
    const subT = (v: Json): Json =>
      typeof v === 'string'
        ? v.split('__TICKET__').join(t)
        : Array.isArray(v)
          ? v.map(subT)
          : v && typeof v === 'object'
            ? Object.fromEntries(Object.entries(v).map(([k, x]) => [k, subT(x)]))
            : v
    Object.assign(s.workflows, subT(readJson(join(t, 'ticket-session.json')).workflows))
  }
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
  await new Promise<void>((done) => {
    const s = createConnection({ path })
    s.setTimeout(5000, () => {
      s.destroy()
      done()
    })
    s.on('error', (e) => {
      console.error(`notify: ${e.message}`)
      done()
    })
    s.on('connect', () => s.end(lines.map((l) => JSON.stringify(l)).join('\n') + '\n', () => done()))
    s.on('close', () => done())
  })
}

/** The message for a finished run from what `yk watch` printed: its RESULT line, the problem lines before it, and how to read more. */
export function notifyText(session: string, exec: string, log: string): string {
  const ls = log.split('\n')
  const result = ls.filter((l) => l.startsWith('RESULT ')).pop()
  if (!result) return `[yk] run ${exec} ended without a RESULT line (stopped?). yk watch ${session} ${exec} shows where it is.`
  const problems = ls
    .filter((l) => /^(ERROR|FAILED|OUTPUT) /.test(l))
    .slice(-12)
    .map((l) => l.slice(0, 300))
    .join('\n')
  return `[yk] run finished: ${result}\n${problems}${problems ? '\n' : ''}\nMore: yk detail ${session} ${exec}\n`
}

// ---- http
export async function http(
  method: string,
  url: string,
  o: { headers?: Record<string, string>; body?: string; timeoutMs?: number } = {},
): Promise<{ code: number; body: string }> {
  try {
    const r = await fetch(url, { method, headers: o.headers, body: o.body, signal: AbortSignal.timeout(o.timeoutMs ?? 30000) })
    return { code: r.status, body: await r.text() }
  } catch (e) {
    return { code: 0, body: String((e as Error).message) }
  }
}
export const keyHeader = (): Record<string, string> => ({ 'X-API-Key': devKey() })
