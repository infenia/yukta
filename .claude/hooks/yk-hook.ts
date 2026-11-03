// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// Claude Code hooks that route builds, tests and checks of this repo through Yukta (`yk`, the yukta skill).
//
// Registered in .claude/settings.json as  bun "$(git rev-parse --show-toplevel)"/.claude/hooks/yk-hook.ts <event>
// so they work whatever folder Claude starts in. They live in settings, not only in the `yukta-dogfood` mod, because a mod
// works only after `/plugin install`; these work in every checkout, worktree and subagent of the repo.
//
//   session-start  remember the uncommitted changes the session began with, start the dev server, put yk on PATH, and say how to
//                  build (the full yukta skill comes from CLAUDE.md, which imports it, so it is there after /compact and in subagents)
//   prompt         every prompt gets one line: builds, tests and checks go through yk
//   pre-bash       deny ./gradlew, gradle, go test|vet|build|fmt, pnpm|npm|npx|yarn|bun and node_modules/.bin tools (also inside
//                  `bash -c '...'`), naming the yk command to use instead
//   stop           when the session changed code and no `yk check` passed on it, ask once to run it
//
// Off: YUKTA_DOGFOOD=off. Headless fixers (YUKTA_HEAL_FIXER) run gradle themselves and skip all of it.
// When the `yukta-dogfood` mod is installed it adds the same context; this script then only enforces (pre-bash, stop), which are idempotent.
import { spawnSync } from 'node:child_process'
import { accessSync, appendFileSync, constants, existsSync, mkdirSync, readFileSync, readdirSync, statSync, unlinkSync, writeFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { join } from 'node:path'

const CODE = ['*.java', '*.kts', '*.gradle', '*.go', '*.ts', '*.tsx', '*.json']
const HINT = 'Build, test and check this repo only through Yukta (yukta skill, imported by CLAUDE.md): `yk gradle <args>`, '
  + '`yk tool -C <ui|cli|examples/11-dogfood> <command>` (go, pnpm/npx, bun), `yk check` (the gate for your changes; background it). Direct gradle, go, pnpm and bun are blocked.'
const WRAPPERS = /^([A-Za-z_][A-Za-z0-9_]*=.*|env|exec|nice|time|command|timeout|sudo|[0-9]+[smh]?)$/
const NOISE = /^(-q|--quiet|--console=.*|--continue|--offline|--daemon|--no-daemon|--parallel|\d*>.*|<.*)$/
const JS = new Set(['pnpm', 'npm', 'npx', 'pnpx', 'yarn', 'bun', 'bunx'])
const JS_BIN = new Set(['tsc', 'vitest', 'eslint', 'prettier', 'vite', 'playwright'])
const SHELLS = new Set(['bash', 'sh', 'zsh', 'dash'])
const RUN_APP = /[\s:](bootRun|nativeRun)\s/

/** Splits on ; && || | & ( ) and newlines outside quotes. */
export function splitSegments(cmd: string): string[] {
  const out: string[] = []
  let cur = ''
  let quote = ''
  for (const c of cmd) {
    if (quote) { cur += c; if (c === quote) quote = '' }
    else if (c === "'" || c === '"') { quote = c; cur += c }
    else if (';|&()\n'.includes(c)) { out.push(cur); cur = '' }
    else cur += c
  }
  out.push(cur)
  return out
}

/** shlex.split: words with quotes and backslashes resolved; null when a quote is left open. */
export function shellWords(s: string): string[] | null {
  const words: string[] = []
  let cur = ''
  let has = false
  let quote = ''
  for (let i = 0; i < s.length; i++) {
    const c = s[i]!
    if (quote === "'") { if (c === "'") quote = ''; else cur += c; continue }
    if (quote === '"') {
      if (c === '"') quote = ''
      else if (c === '\\' && i + 1 < s.length && '"\\$`'.includes(s[i + 1]!)) cur += s[++i]
      else cur += c
      continue
    }
    if (c === "'" || c === '"') { quote = c; has = true }
    else if (c === '\\' && i + 1 < s.length) { cur += s[++i]; has = true }
    else if (/\s/.test(c)) { if (cur || has) { words.push(cur); cur = ''; has = false } }
    else { cur += c; has = true }
  }
  if (quote) return null
  if (cur || has) words.push(cur)
  return words
}

const wordsOf = (segment: string): string[] => shellWords(segment) ?? segment.split(/\s+/).filter(Boolean)
/** shlex.quote */
const quote = (a: string): string => (/^[A-Za-z0-9@%+=:,./-]+$/.test(a) ? a : a === '' ? "''" : `'${a.replaceAll("'", `'"'"'`)}'`)

/** The yk command to run instead of `cmd`, or null when `cmd` may run as it is. */
export function rewrite(cmd: string, depth = 0): string | null {
  for (const segment of splitSegments(cmd)) {
    const words = wordsOf(segment.trim())
    while (words.length && WRAPPERS.test(words[0]!)) words.shift()
    if (!words.length) continue
    const path = words[0]!
    const name = path.split('/').pop()!
    const args = words.slice(1)
    if (SHELLS.has(name) && depth < 3 && args.slice(0, 3).includes('-c')) {
      const i = args.indexOf('-c')
      if (i + 1 < args.length) {
        const inner = rewrite(args[i + 1]!, depth + 1)
        if (inner) return inner
      }
      continue
    }
    const isGradle = name === 'gradlew' || name === 'gradle'
    const isGo = name === 'go' && args.length > 0 && ['test', 'vet', 'build', 'fmt'].includes(args[0]!)
    const isJs = JS.has(name) || (JS_BIN.has(name) && path.includes('node_modules/.bin/'))
    if (!(isGradle || isGo || isJs)) continue
    const joined = words.join(' ')
    // running the app being developed is not a build or a check
    if (RUN_APP.test(' ' + (isGradle ? args.join(' ') : joined) + ' ')) return null
    if (isGradle) return 'yk gradle ' + (args.filter(a => !NOISE.test(a)).map(quote).join(' ') || '<tasks>')
    if (isGo) return 'yk tool -C cli ' + joined
    // a Bun script of the dogfood runs in its own package
    return (/^(bun|bunx)$/.test(name) && /examples\/1[12]-/.test(process.cwd() + ' ' + joined) ? 'yk tool -C examples/11-dogfood ' : 'yk tool -C ui ') + joined
  }
  return null
}

function sh(root: string, ...argv: string[]): string {
  const r = spawnSync('git', ['-C', root, ...argv], { encoding: 'utf8' })
  return r.status === 0 ? r.stdout.trim() : ''
}
const ykSum = (yk: string): string => (spawnSync(yk, ['sum'], { encoding: 'utf8' }).stdout ?? '').trim()

function modInstalled(): boolean {
  const base = process.env.CLAUDE_CONFIG_DIR || join(homedir(), '.claude')
  try {
    const plugins = JSON.parse(readFileSync(join(base, 'plugins', 'installed_plugins.json'), 'utf8')).plugins ?? {}
    return Object.keys(plugins).some(k => k.startsWith('yukta-dogfood@'))
  } catch { return false }
}

const emit = (event: string, fields: Record<string, unknown>): void => void console.log(JSON.stringify({ hookSpecificOutput: { hookEventName: event, ...fields } }))
const read = (p: string): string => { try { return readFileSync(p, 'utf8').trim() } catch { return '' } }
const executable = (p: string): boolean => { try { accessSync(p, constants.X_OK); return true } catch { return false } }

type Data = { session_id?: string; source?: string; stop_hook_active?: boolean; tool_input?: { command?: string } }

export function main(event: string, data: Data): void {
  if (process.env.YUKTA_DOGFOOD === 'off' || process.env.YUKTA_HEAL_FIXER) return
  const root = sh(process.cwd(), 'rev-parse', '--show-toplevel')
  const yk = `${root}/examples/11-dogfood/yk`
  if (!root || !executable(yk)) return
  const state = join(root, '.yukta-dev')

  if (event === 'session-start') {
    const sid = data.session_id ?? ''
    mkdirSync(state, { recursive: true })
    for (const f of readdirSync(state).filter(f => f.startsWith('baseline-'))) {
      if (statSync(join(state, f)).mtimeMs < Date.now() - 7 * 86400000) unlinkSync(join(state, f))
    }
    const baseline = join(state, `baseline-${sid}`)
    if (sid && !existsSync(baseline)) writeFileSync(baseline, ykSum(yk) + '\n') // a resumed session keeps its first baseline
    if (data.source !== 'compact') spawnSync(yk, ['up', '--background'], { timeout: 60000 })
    if (process.env.CLAUDE_ENV_FILE) appendFileSync(process.env.CLAUDE_ENV_FILE, `export PATH="${root}/examples/11-dogfood:$PATH"\n`)
    if (modInstalled()) return
    const status = (spawnSync(yk, ['status'], { encoding: 'utf8', timeout: 5000 }).stdout ?? '').split('\n')[0]
    emit('SessionStart', { additionalContext: `Yukta dev server: ${status || 'starting'}. ${HINT} yk is examples/11-dogfood/yk.` })
  } else if (event === 'prompt') {
    if (!modInstalled()) emit('UserPromptSubmit', { additionalContext: HINT })
  } else if (event === 'pre-bash') {
    const use = rewrite(data.tool_input?.command ?? '')
    if (use) {
      emit('PreToolUse', {
        permissionDecision: 'deny',
        permissionDecisionReason: `Builds and tests of this repo run through Yukta (yukta skill). Run instead: ${use}   (yk is examples/11-dogfood/yk; the run prints a RESULT line). To bypass for one session the user can set YUKTA_DOGFOOD=off.`,
      })
    }
  } else if (event === 'stop') {
    if (data.stop_hook_active || !sh(root, 'status', '--porcelain', '--', ...CODE)) return
    const sid = data.session_id ?? ''
    const total = ykSum(yk)
    // nothing changed since the session began: the changes are someone else's
    if (sid && read(join(state, `baseline-${sid}`)) === total) return
    if (read(join(state, 'nudged')) === total) return
    const last = read(join(state, 'last-result')).split(/\s+/)
    if (last[0] === total && last[1] === 'SUCCESS' && last[2] === 'check') return
    mkdirSync(state, { recursive: true })
    writeFileSync(join(state, 'nudged'), total + '\n')
    console.log(JSON.stringify({
      decision: 'block',
      reason: 'Uncommitted code changes: run the quality gate before finishing: yk check (yukta skill: start it in the background; it heals what it can, so review its healed= changes with git diff). YUKTA_DOGFOOD=off skips this.',
    }))
  }
}

if (import.meta.main) {
  let payload: Data = {}
  try { payload = JSON.parse(readFileSync(0, 'utf8')) } catch { /* no payload */ }
  try { main(process.argv[2] ?? '', payload) } catch (e) { console.error(`yk-hook: ${(e as Error).message}`) } // a hook must never break the session
  process.exit(0)
}
