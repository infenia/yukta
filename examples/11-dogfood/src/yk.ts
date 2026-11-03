// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// yk: develop Yukta with Yukta. Every Gradle run of this repo goes through the `tool` Yukta workflow on the dev
// server (built from origin/main, port 9858, started on demand).
//
//   yk gradle [--detach] <gradle args...>    run Gradle once, e.g. :core:test --tests X
//   yk check [--full] [--base REF] [--stage a,b] [--skip a,b] [--no-heal] [--detach]  the scoped, self-healing gate: yk plan's stages one after the other; a failure is
//                                            fixed (free fix, then a System One triage picks model and effort) or stops the run (--no-heal: at once)
//   yk plan [--full] [--base REF]            dry run: the stages a scoped check of your changes would run, nothing is executed
//   yk advise [--base REF] [--no-model]      recommends a test tier for your changes: a floor from the paths, which the decision model can only widen
//   yk stats [--decisions]                   (--decisions: the decision model in shadow mode, agreement and calibration per decision)
//   yk stats                                 runs, failed, median wall and heal dollars per kind of run; fix rate and dollars per stage and rung (from the heal events)
//   yk heal report|analyze|show [--since 7d] [--run ID] [--project NAME] [--json]
//                                            what heal did: where the time and money went, what looks wrong, the raw events
//   yk detail <session> <execution> [--grep P] [--tail N] [--head N]  the full output of the stage that failed a run (the MORE line says so)
//   yk bench [--runs N] [--max-ratio R] [--check] [args]  the same Gradle work direct and through yk: wall times and ratio (exit 1 above R and +3 s)
//   yk decision                              probe the optional decision model (decisions.json, YUKTA_DECISION_MODEL/_URL/off); yk status shows the same line
//   yk tool [--detach] [-C dir] <cmd...>     run any other command once, e.g. -C ui pnpm lint | -C cli go test ./...
//   (check, gradle, tool) --detach --notify  also post the RESULT into the Claude Code session that started the run when it ends (YUKTA_NOTIFY=off: never)
//   yk ticket run <issue> [--detach [--notify]] | status|resume|stop <issue|execution>  an issue with acceptance criteria to a tested change (examples/12-ticket)
//   yk pickup session [--name N]             open an interactive Claude Code session that receives issues through its yukta-pickup channel (several may be open)
//   yk pickup on|off|now [--to N]|status     the schedule that hands each idle session the next assigned, Ready issue every 30 min (now: one round at once)
//
// A run's last line is machine readable; exit 0 only on success:
//   RESULT status=<S> failed_node=<node|-> healed=<stage:step,..|-> execution=<id> session=<id> wall=<s> cost=$<heal spend> bytes=<shown to you>
//
// Rebuild step 1: the other commands still work but are not advertised. The full usage text of the previous implementation is in git history (see backup/README.md).
import { spawn } from 'bun'
import { closeSync, mkdtempSync, openSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { readFileSync as readSelf } from 'node:fs'
import { main as adviseMain } from './advise.ts'
import { main as decisionsReport } from './decisions-report.ts'
import { main as healReport } from './heal-report.ts'
import { plan, main as planMain } from './plan.ts'
import {
  api, cmdAnswer, cmdBreak, cmdDetail, cmdNodeControl, cmdNotifyRun, cmdRunControl, cmdSignals, cmdStep, cmdTree, cmdWatch, refreshActivity, startNotifier, watchOwned, printResult,
} from './runs.ts'
import {
  DEV_HOME, DEV_PORT, DEV_URL, HERE, type Json, REPO_ROOT, STATE_DIR, busy, changesSum, checkSession, cliJson, decisionProbe, devKey, die, ensureServer, existsSync, filterPlan, findCli,
  git, healEventsFile, healthy, isDir, isFile, join, lockHeld, mkdirp, need, onPath, ours, pickupAlive, pickupStart, readJson, renderSession, rmSync, run, serverEnv, serverStop, serverUp,
  shq, sleep, sq, statSync, text, triageConf, tryJson, tryLock, useDevKey, writeFileSync,
} from './lib.ts'

const YK = join(HERE, 'yk')
const lines = (s: string): string[] => (s.length ? s.replace(/\n$/, '').split('\n') : [])

export function usage(): string {
  const src = readSelf(new URL(import.meta.url).pathname, 'utf8').split('\n')
  return src.slice(2, src.findIndex(l => l.startsWith('// Rebuild step 1'))).map(l => l.replace(/^\/\/ ?/, '')).join('\n').replace(/\n+$/, '')
}

async function cmdUp(args: string[]): Promise<number> {
  const rest: string[] = []
  let bg = false
  for (let i = 0; i < args.length; i++) {
    const a = args[i]!
    if (a === '--background') bg = true
    else if (a === '--release') { rest.push('--release'); if (args[i + 1] && !args[i + 1]!.startsWith('--')) rest.push(args[++i]!) }
    else die(`unknown option '${a}' (yk up [--background] [--release [tag]])`)
  }
  if (bg) {
    mkdirp(DEV_HOME)
    if (lockHeld(join(DEV_HOME, 'up.lock'))) { console.log('yk up: already in progress'); return 0 }
    const fd = openSync(join(DEV_HOME, 'up.log'), 'w')
    spawn([process.execPath, YK, 'up', ...rest], { stdin: 'ignore', stdout: fd, stderr: fd, env: process.env as Record<string, string> }).unref()
    closeSync(fd)
    console.log(`yk up: running in the background (log: ${join(DEV_HOME, 'up.log')})`)
    return 0
  }
  return serverUp(rest)
}

async function cmdStatus(): Promise<number> {
  const main = git(['rev-parse', process.env.YUKTA_DEV_MAIN_REF ?? 'origin/main'], join(DEV_HOME, 'main')).stdout.trim()
  const building = existsSync(join(DEV_HOME, 'up.lock')) && lockHeld(join(DEV_HOME, 'up.lock')) ? ` (yk up in progress, log: ${join(DEV_HOME, 'up.log')})` : ''
  let rc = 0
  if (!(await healthy())) { console.log(`down: ${DEV_URL}${building}`); rc = 3 }
  else if (!ours()) console.log(`up: ${DEV_URL}, not started by yk`)
  else {
    const sha = serverEnv('YUKTA_DEV_SHA')
    let state = 'current'
    if (serverEnv('YUKTA_DEV_KIND') === 'main' && main && main !== sha) {
      state = `stale (origin/main is ${main.slice(0, 9)}${isFile(join(DEV_HOME, 'builds', main, 'yukta.jar')) && statSync(join(DEV_HOME, 'builds', main, 'yukta.jar')).size ? ', built; swaps when idle' : ''})`
    }
    console.log(`up: ${DEV_URL} ${serverEnv('YUKTA_DEV_LABEL')} ${state}, running=${await busy()}${building}`)
    console.log(`log: ${join(DEV_HOME, 'server.log')}`)
  }
  console.log((await decisionProbe())[0])
  return rc
}

/** One run of the `tool` workflow in this checkout's session: ARGS are the command (gradle: ./gradlew ARGS; tool: ARGS as they are), every argument shell-quoted. */
async function runTool(kind: 'gradle' | 'tool', mode: 'watch' | 'detach', notify: boolean, project: string, args: string[]): Promise<number> {
  await ensureServer()
  const cli = findCli()
  mkdirp(STATE_DIR)
  const session = checkSession()
  const tmp = mkdtempSync(join(tmpdir(), 'yk-run-'))
  try {
    const cmdline = kind === 'gradle' ? `./gradlew ${args.map(a => shq(a) + ' ').join('')}-q --console=plain` : args.map(a => shq(a) + ' ').join('')
    const label = kind === 'gradle' ? 'gradle' : args[0]!
    renderSession(join(tmp, 'session.json'))
    console.error(`>> server ${process.env.YUKTA_SERVER_URL}, session ${session}, project ${project}`)
    const apply = run([cli, 'session', 'apply', join(tmp, 'session.json')])
    if (apply.code) { console.error(apply.stdout + apply.stderr); die('session apply failed') }
    // a CLI built before 'session apply' existed prints the session help and exits 0
    if ((apply.stdout + apply.stderr).includes('Available Commands')) die('stale CLI (session apply printed help); run yk up')
    const execId = cliJson(['workflow', 'start', session, 'tool', '--payload', JSON.stringify({ project, command: cmdline, timeout: 3600, label })]).data?.executionId
    if (!execId) die('could not start workflow')
    console.log(`STARTED execution=${execId} session=${session}`)
    const scope = `args:${args.join(' ').slice(0, 60)}`
    process.env.YUKTA_PROJECT = project
    process.env.YUKTA_SCOPE = scope
    if (notify) startNotifier(session, execId, YK)
    if (mode === 'detach') return 0
    return await watchOwned(session, execId)
  } finally { rmSync(tmp, { recursive: true, force: true }) }
}

async function cmdGradle(argv: string[]): Promise<number> {
  let mode: 'watch' | 'detach' = 'watch'
  let notify = false
  let i = 0
  for (; i < argv.length; i++) {
    if (argv[i] === '--detach') mode = 'detach'
    else if (argv[i] === '--notify') notify = true
    else break
  }
  const rest = argv.slice(i)
  if (!rest.length) die('usage: yk gradle [--detach [--notify]] <gradle args...>')
  if (notify && mode !== 'detach') die('--notify needs --detach: a run you wait for reports to you anyway')
  return runTool('gradle', mode, notify, git(['rev-parse', '--show-toplevel']).stdout.trim(), rest)
}

async function cmdTool(argv: string[]): Promise<number> {
  let mode: 'watch' | 'detach' = 'watch'
  let notify = false
  const root = git(['rev-parse', '--show-toplevel']).stdout.trim()
  let dir = root
  let i = 0
  for (; i < argv.length; i++) {
    const a = argv[i]
    if (a === '--detach') mode = 'detach'
    else if (a === '--notify') notify = true
    else if (a === '-C') {
      const d = argv[++i] ?? die('-C needs a folder')
      const p = join(root, d)
      if (!isDir(p)) die(`no folder '${d}'`)
      dir = run(['sh', '-c', 'cd "$1" && pwd', '_', p]).stdout.trim()
    } else break
  }
  const rest = argv.slice(i)
  if (!rest.length) die('usage: yk tool [--detach [--notify]] [-C dir] <command> [args...]')
  if (notify && mode !== 'detach') die('--notify needs --detach: a run you wait for reports to you anyway')
  return runTool('tool', mode, notify, dir, rest)
}

/** yk check [--full] [--base REF] [--no-heal] [--models haiku,sonnet] [--triage off] [--detach [--notify]]
 * The scoped, self-healing gate. plan.ts turns the changes into stage commands; the `check` workflow (src/gen-session.ts) runs them one after the other
 * through `tool`. A failing stage goes to `heal`: its free fix, then the System One triage rates the failure and picks the model and effort to start with,
 * and fresh Claude sessions climb from there. --no-heal sends heal = "off": heal ends at once, so the first failing stage ends the run. */
async function cmdCheck(argv: string[]): Promise<number> {
  let mode: 'watch' | 'detach' = 'watch'
  let full = false, base = 'origin/main', heal = 'on', fast = 'on', models = '', triage = 'on', only = '', skip = '', notify = false
  const bad = 'yk check [--full] [--base REF] [--stage a,b] [--skip a,b] [--no-heal] [--no-fast] [--models ..] [--triage off] [--detach [--notify]]'
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i]!
    if (a === '--detach') mode = 'detach'
    else if (a === '--notify') notify = true
    else if (a === '--full') full = true
    else if (a === '--base') base = argv[++i] ?? die('--base needs a ref')
    else if (a === '--no-heal') heal = 'off'
    else if (a === '--no-fast') fast = 'off'
    else if (a === '--stage') only = argv[++i] ?? die('--stage needs stage names, e.g. tests,style')
    else if (a === '--skip') skip = argv[++i] ?? die('--skip needs stage names, e.g. security')
    else if (a === '--models') models = argv[++i] ?? die('--models needs models, e.g. haiku,sonnet')
    else if (a === '--triage') { if (argv[++i] !== 'off') die(`invalid --triage '${argv[i] ?? ''}' (off)`); triage = 'off' }
    else die(`unknown option '${a}' (${bad})`)
  }
  if (models && !/^(haiku|sonnet)(,(haiku|sonnet))*$/.test(models)) die(`invalid --models '${models}' (models from haiku, sonnet)`)
  if (notify && mode !== 'detach') die('--notify needs --detach: a run you wait for reports to you anyway')
  const root = git(['rev-parse', '--show-toplevel']).stdout.trim()
  const p = plan(full, base, root)
  if (p.code) die('yk plan failed')
  let planText = p.text.replace(/\n$/, '')
  if (only || skip) {
    // --stage keeps only the named stages of the plan, --skip drops them (a name matches with - or _ either way, as yk plan prints it)
    planText = filterPlan(planText, only, skip)
    if (!planText.split('\n').some(l => l && !l.startsWith('#'))) die('no stage of the plan is left after --stage/--skip (yk plan shows the stages)')
  }
  for (const l of planText.split('\n').filter(l => l.startsWith('#'))) console.error(l)
  const session = checkSession()
  const healdir = join(STATE_DIR, 'heal', session)
  const events = healEventsFile()
  // Per stage of the check workflow (fixed in session.json), "" unless planned: cmd (its command), auto (its free fix, "-" for none) and fmt (what formats
  // the code after a fixer edited it: the formatter of the stage's ecosystem). A planned stage the workflow lacks is a bug in plan or gen-session.
  const wf = readJson(join(HERE, 'session.json')).workflows.check
  const names: string[] = wf.nodes.filter((n: Json) => n.type === 'SUB_WORKFLOW' && n.config.workflowId === 'tool' && n.nodeId !== 'verify').map((n: Json) => n.nodeId)
  const rows = planText.split('\n').filter(l => l.length && !l.startsWith('#')).map(l => { const f = l.split('\t'); return { stage: f[0]!.replace(/[.-]/g, '_'), dir: f[1]!, cmd: f[2]!, auto: f[3] ?? '' } })
  const unknown = rows.map(r => r.stage).filter(s => !names.includes(s))
  if (unknown.length) die(`cannot build the stage commands: stage not in the check workflow: ${unknown.join(', ')}`)
  const full_ = (r: { dir: string }, c: string): string => (r.dir === '.' ? c : `cd ${sq(r.dir)} && ${c}`)
  const cmd: Record<string, string> = Object.fromEntries(names.map(n => [n, '']))
  for (const r of rows) cmd[r.stage] = full_(r, r.cmd)
  const autoOf: Record<string, string> = {}
  for (const r of rows.filter(r => r.auto !== '')) autoOf[r.stage] = full_(r, r.auto)
  const fmtOf = (n: string): string => (n.startsWith('cli_') ? autoOf.cli_fmt_check ?? '-' : n.startsWith('ui_') ? autoOf.ui_prettier ?? '-' : ['reuse', 'actionlint', 'docs'].includes(n) ? '-' : autoOf.spotless ?? '-')
  const auto = Object.fromEntries(names.map(n => [n, autoOf[n] ?? '-']))
  const fmt = Object.fromEntries(names.map(n => [n, fmtOf(n)]))
  const verifyTasks = [...new Set(rows.filter(r => r.cmd.startsWith('./gradlew ')).flatMap(r => r.cmd.split(' ').slice(1).filter(x => !x.startsWith('-'))))].sort().join(' ')
  if (!Object.values(cmd).some(c => c !== '')) { console.log('nothing to check'); return 0 }
  await ensureServer()
  const cli = findCli()
  const modules = /^# modules: (.*)$/m.exec(planText)?.[1] ?? ''
  mkdirp(STATE_DIR)
  // only a healing check edits the checkout, so only it excludes another one (a --no-heal check and yk gradle queue on the server instead)
  if (heal === 'on' && !tryLock(join(STATE_DIR, 'check.lock'))) {
    die(`another healing check is running in this checkout (execution ${text(join(STATE_DIR, 'check.holder')).trim() || 'unknown'}); wait for it (yk watch) or stop it, or use --no-heal`)
  }
  rmSync(`${healdir}.prev`, { recursive: true, force: true })
  if (isDir(healdir)) run(['mv', healdir, `${healdir}.prev`])
  mkdirp(healdir)
  const tmp = mkdtempSync(join(tmpdir(), 'yk-check-'))
  try {
    renderSession(join(tmp, 'session.json'))
    console.error(`>> server ${process.env.YUKTA_SERVER_URL}, session ${session}, project ${root}`)
    const apply = run([cli, 'session', 'apply', join(tmp, 'session.json')])
    if (apply.code) { console.error(apply.stdout + apply.stderr); die('session apply failed') }
    const gradleStages = Object.values(cmd).filter(c => c.startsWith('./gradlew '))
    const verifyCommand = verifyTasks === '' ? 'true' : `./gradlew --continue ${verifyTasks} -q --console=plain`
    const payload = {
      project: root, timeout: 3600, healDir: healdir, events: events || '-', modules: modules || '-', models: models || '-', heal, triage, rules: join(HERE, 'fixer-rules.md'),
      claudeBin: process.env.YUKTA_CLAUDE_BIN || '-', budgetCheck: process.env.YUKTA_BUDGET_CHECK || String(readJson(join(HERE, 'heal.json')).checkBudget), triageModel: triageConf('model'), triageUrl: triageConf('url'),
      verifyCommand, cmd, auto, fmt,
      // fast path: all Gradle tasks in one invocation (two or more Gradle stages planned); its pass leaves a marker that turns the Gradle stages of the chain into no-ops
      fast: fast === 'on' && gradleStages.length > 1 ? `./gradlew --continue ${verifyTasks} -q --console=plain && touch ${sq(healdir + '/fast.ok')}` : '',
      chain: Object.fromEntries(Object.entries(cmd).map(([k, c]) => [k, c.startsWith('./gradlew ') ? `test -f ${sq(healdir + '/fast.ok')} || ${c}` : c])),
    }
    const execId = cliJson(['workflow', 'start', session, 'check', '--payload', JSON.stringify(payload)]).data?.executionId
    if (!execId) die('could not start the check')
    if (heal === 'on') writeFileSync(join(STATE_DIR, 'check.holder'), execId + '\n')
    console.log(`STARTED execution=${execId} session=${session}`)
    process.env.YUKTA_FAILED_OUTPUT = '1'
    process.env.YUKTA_PROJECT = root
    process.env.YUKTA_SCOPE = 'check'
    if (notify) startNotifier(session, execId, YK)
    if (mode === 'detach') return 0
    return await watchOwned(session, execId)
  } finally { rmSync(tmp, { recursive: true, force: true }) }
}

async function cmdPickup(argv: string[]): Promise<number> {
  const sub = argv[0] ?? ''
  const rest = argv.slice(1)
  switch (sub) {
    case 'session': {
      let name = 'yukta-pickup'
      for (let i = 0; i < rest.length; i++) { if (rest[i] === '--name') name = rest[++i] ?? die('--name needs a session name'); else die('usage: yk pickup session [--name NAME]') }
      need('claude'); need('bun')
      if (!isDir(join(HERE, 'channel/node_modules'))) {
        const r = run(['bun', 'install', '--frozen-lockfile'], { cwd: join(HERE, 'channel') })
        if (r.code) { console.error(r.stderr); die('could not install the channel dependencies (bun install in examples/11-dogfood/channel)') }
      }
      mkdirp(DEV_HOME)
      if (!isFile(join(DEV_HOME, 'pickup-token')) || !statSync(join(DEV_HOME, 'pickup-token')).size) {
        writeFileSync(join(DEV_HOME, 'pickup-token'), Buffer.from(crypto.getRandomValues(new Uint8Array(24))).toString('base64').replace(/[/+=\n]/g, ''), { mode: 0o600 })
      }
      // the channel inherits only a filtered environment from claude, so what it needs goes in its config: where the token and the registry are, its name and,
      // when you fixed one, its port (default: any free port; it registers itself, see channel/src/main.ts)
      const env: Record<string, string> = { YUKTA_CHANNEL_PROFILE: 'pickup', YUKTA_DEV_HOME: DEV_HOME, YUKTA_PICKUP_NAME: name }
      if (process.env.YUKTA_PICKUP_PORT) env.YUKTA_PICKUP_PORT = process.env.YUKTA_PICKUP_PORT
      const cfg = JSON.stringify({ mcpServers: { 'yukta-pickup': { command: 'bun', args: ['run', join(HERE, 'channel/src/main.ts')], env } } })
      const p = Bun.spawnSync(['claude', '--name', name, '--mcp-config', cfg, '--dangerously-load-development-channels', 'server:yukta-pickup'], { cwd: REPO_ROOT, stdio: ['inherit', 'inherit', 'inherit'] })
      return p.exitCode ?? 1
    }
    case 'on': {
      await ensureServer()
      if (await pickupAlive()) { console.log(`pickup: already on (execution ${text(join(DEV_HOME, 'pickup.exec')).trim()})`); return 0 }
      const id = pickupStart() ?? die('could not start the pickup workflow')
      console.log(`pickup: on (execution ${id})`)
      return 0
    }
    case 'off':
      if (await pickupAlive()) { try { await api('POST', `/api/executions/${text(join(DEV_HOME, 'pickup.exec')).trim()}/stop?tree=true`) } catch { /* best effort */ } }
      rmSync(join(DEV_HOME, 'pickup.exec'), { force: true })
      console.log('pickup: off')
      return 0
    case 'now': {
      const a = [...rest]
      if (a[0] === '--to') { process.env.YUKTA_PICKUP_ONLY = a[1] ?? die('--to needs a session name'); a.splice(0, 2) }
      if (a.length) die('usage: yk pickup now [--to NAME]')
      return (await import('./pickup.ts')).main(['tick'])
    }
    case 'status': {
      let e: string
      if (await pickupAlive()) e = `on (execution ${text(join(DEV_HOME, 'pickup.exec')).trim()})`
      else if (text(join(DEV_HOME, 'pickup.exec')).trim()) e = 'on, but not running (the next yk call restarts it)'
      else e = 'off'
      console.log(`schedule: ${e}`)
      let rows = 0
      const dir = join(DEV_HOME, 'pickup')
      const files = isDir(dir) ? (await import('node:fs')).readdirSync(dir).filter(f => f.endsWith('.json')).sort().map(f => join(dir, f)) : []
      for (const f of files) {
        const j = tryJson(text(f))
        if (!j) continue
        try { process.kill(j.pid, 0) } catch { continue }
        const r = await fetch(`http://127.0.0.1:${j.port}/v1/status`, { signal: AbortSignal.timeout(3000) }).then(x => x.json() as Promise<Json>).catch(() => null)
        const st = r ? (r.busy ? `busy with #${r.meta?.issue ?? r.event_id}, since ${new Date(r.since * 1000).toISOString().replace(/\.\d+Z$/, 'Z')}` : 'idle') : 'not answering'
        console.log(`session: ${j.name}  :${j.port}  ${st}`)
        rows++
      }
      if (!rows) console.log('sessions: none (yk pickup session)')
      if (text(join(DEV_HOME, 'pickup.jsonl')).length) { console.log('last:'); console.log(lines(text(join(DEV_HOME, 'pickup.jsonl'))).slice(-5).join('\n')) }
      return 0
    }
    default:
      return die('usage: yk pickup session [--name NAME]|on|off|now [--to NAME]|status')
  }
}

// ---- stats
const med = (xs: number[]): number => { if (!xs.length) return 0; const s = [...xs].sort((a, b) => a - b); const m = s.length >> 1; return s.length % 2 ? s[m]! : (s[m - 1]! + s[m]!) / 2 }
const lp = (s: string | number, n: number): string => String(s).padEnd(n)
const rp = (s: string | number, n: number): string => String(s).padStart(n)

function statsFromEvents(file: string): void {
  const ev: Json[] = lines(text(file)).map(tryJson).filter(Boolean)
  console.log(`${lp('kind', 14)} ${rp('runs', 5)} ${rp('failed', 8)} ${rp('median_wall', 11)} ${rp('cost', 8)}`)
  const cost = new Map<string, number>()
  for (const e of ev.filter(e => e.event === 'fix')) cost.set(e.run, (cost.get(e.run) ?? 0) + (e.cost ?? 0))
  const byWf = new Map<string, Json[]>()
  for (const e of ev.filter(e => e.event === 'result')) byWf.set(e.workflow, [...(byWf.get(e.workflow) ?? []), e])
  for (const [wf, rs] of [...byWf].sort(([a], [b]) => (a < b ? -1 : 1))) {
    const uniq = [...new Map(rs.map(r => [r.run, r])).values()]
    const c = uniq.reduce((a, r) => a + (cost.get(r.run) ?? 0), 0)
    console.log(`${lp(wf, 14)} ${rp(rs.length, 5)} ${rp(rs.filter(r => r.status !== 'SUCCESS').length, 8)} ${rp(med(rs.map(r => r.secs ?? 0)).toFixed(1), 11)} ${rp(c.toFixed(2), 8)}`)
  }
  console.log()
  console.log(`${lp('stage', 14)} ${lp('rung', 8)} ${rp('attempts', 9)} ${rp('fixed', 6)} ${rp('fix_rate', 9)} ${rp('cost', 8)}`)
  const groups = new Map<string, Json[]>()
  for (const e of ev.filter(e => e.event === 'fix')) { const k = `${e.stage}\0${e.model ?? '-'}`; groups.set(k, [...(groups.get(k) ?? []), e]) }
  for (const [k, g] of [...groups].sort(([a], [b]) => (a < b ? -1 : 1))) {
    const [stage, model] = k.split('\0') as [string, string]
    const fixed = g.filter(e => e.fixed === true).length
    console.log(`${lp(stage, 14)} ${lp(model, 8)} ${rp(g.length, 9)} ${rp(fixed, 6)} ${rp((g.length ? (100 * fixed) / g.length : 0).toFixed(0) + '%', 9)} ${rp(g.reduce((a, e) => a + (e.cost ?? 0), 0).toFixed(2), 8)}`)
  }
}

async function cmdStats(argv: string[]): Promise<number> {
  if (argv[0] === '--decisions') return decisionsReport([join(STATE_DIR, 'decisions.tsv'), join(HERE, 'decisions.json')])
  const ev = healEventsFile()
  if (ev && isFile(ev) && statSync(ev).size > 0) { statsFromEvents(ev); return 0 }
  const f = join(STATE_DIR, 'runs.tsv')
  if (!isFile(f) || !statSync(f).size) { console.log(`no runs recorded yet (${ev}, ${f})`); return 0 }
  console.log(`${lp('kind', 14)} ${rp('runs', 5)} ${rp('failed', 8)} ${rp('median_bytes', 12)} ${rp('median_wall', 10)} ${rp('cost', 8)}`)
  const rows = lines(text(f)).map(l => l.split('\t')).sort((a, b) => (a[1]! < b[1]! ? -1 : a[1]! > b[1]! ? 1 : 0))
  const kinds = new Map<string, string[][]>()
  for (const r of rows) kinds.set(r[1]!, [...(kinds.get(r[1]!) ?? []), r])
  for (const [k, rs] of kinds) {
    console.log(`${lp(k, 14)} ${rp(rs.length, 5)} ${rp(rs.filter(r => r[2] !== 'SUCCESS').length, 8)} ${rp(Math.trunc(med(rs.map(r => Number(r[5]))) ), 12)} ${rp(med(rs.map(r => Number(r[3]))).toFixed(1), 10)} ${rp(rs.reduce((a, r) => a + Number(r[4]), 0).toFixed(2), 8)}`)
  }
  return 0
}

/** yk bench [--runs N] [--max-ratio R] [--check] [gradle args...]: the same Gradle work run directly and through yk, wall times (median of N, after one warm-up
 * of each) and their ratio. Default task: :plugin-api:compileJava (up to date: start-up and loop overhead only). --check: all the Gradle tasks of the plan in
 * one direct invocation against `yk check --no-heal`. Exit 1 when yk is slower than R times direct (default 1.5) and by more than 3 s. */
async function cmdBench(argv: string[]): Promise<number> {
  let runs = 3, max = 1.5, check = false
  const args: string[] = []
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--runs') runs = Number(argv[++i] ?? die('--runs needs a number'))
    else if (argv[i] === '--max-ratio') max = Number(argv[++i] ?? die('--max-ratio needs a number'))
    else if (argv[i] === '--check') check = true
    else args.push(argv[i]!)
  }
  if (!Number.isInteger(runs) || runs < 1) die('--runs must be a positive number')
  const root = git(['rev-parse', '--show-toplevel']).stdout.trim()
  let direct: string[]
  let via: string[]
  if (check) {
    const tasks = [...new Set(plan(false, 'origin/main', root).text.split('\n').map(l => l.split('\t')[2]).filter((c): c is string => !!c && c.startsWith('./gradlew '))
      .flatMap(c => c.replace(/^\.\/gradlew /, '').replace(/ -q --console=plain$/, '').split(' ').filter(x => !x.startsWith('-'))))].sort()
    if (!tasks.length) die('yk bench --check: the plan has no Gradle stage (change something under a module)')
    direct = ['bash', '-c', `cd '${root}' && ./gradlew --continue ${tasks.join(' ')} -q --console=plain`]
    via = [process.execPath, YK, 'check', '--no-heal']
    console.error(`bench: yk check --no-heal against one direct Gradle run of: ${tasks.join(' ')}`)
  } else {
    const a = args.length ? args : [':plugin-api:compileJava']
    direct = ['bash', '-c', `cd '${root}' && ./gradlew ${a.map(x => shq(x) + ' ').join('')}-q --console=plain`]
    via = [process.execPath, YK, 'gradle', ...a]
    console.error(`bench: yk gradle ${a.join(' ')} against ./gradlew ${a.join(' ')}`)
  }
  const time = (c: string[]): number => { const t = Date.now(); run(c); return (Date.now() - t) / 1000 }
  run(direct); run(via)
  const dt: number[] = [], yt: number[] = []
  for (let i = 0; i < runs; i++) { dt.push(time(direct)); yt.push(time(via)) }
  const pick = (xs: number[]): number => [...xs].sort((a, b) => a - b)[Math.floor((xs.length + 1) / 2) - 1]!
  const d = pick(dt), y = pick(yt)
  const r = d > 0 ? y / d : 0
  console.log(`BENCH direct=${d.toFixed(2)}s yk=${y.toFixed(2)}s ratio=${r.toFixed(2)} overhead=${(y - d).toFixed(2)}s runs=${runs}`)
  return r > max && y - d > 3 ? 1 : 0
}

/** One line for Claude Code's status line, from cached files only (no network). */
function cmdStatusline(): number {
  let mark = '✗'
  const pid = parseInt(text(join(DEV_HOME, 'server.pid')).trim(), 10)
  if (!Number.isNaN(pid)) { try { process.kill(pid, 0); mark = '✓' } catch { /* down */ } }
  const label = serverEnv('YUKTA_DEV_LABEL')
  let line = `yukta :${DEV_PORT} ${label || 'down'} ${mark}`
  const act = join(DEV_HOME, 'activity')
  if (isFile(act) && statSync(act).size) line += ` | ${text(act).trim()}`
  if ((!isFile(act) || Date.now() - statSync(act).mtimeMs > 10000) && mark === '✓') {
    spawn([process.execPath, YK, '_refresh-activity'], { stdin: 'ignore', stdout: 'ignore', stderr: 'ignore', env: process.env as Record<string, string> }).unref()
  }
  const last = join(STATE_DIR, 'last-result')
  if (isFile(last) && statSync(last).size) {
    const [, status, scope, healed] = text(last).split('\t')
    line += ` | last: ${scope} ${status}`
    if (healed && healed !== '-') line += ` (healed ${healed})`
  }
  console.log(line)
  return 0
}

export async function main(argv: string[]): Promise<number> {
  useDevKey()
  const cmd = argv[0] ?? 'help'
  const rest = argv.slice(1)
  switch (cmd) {
    case 'up': return cmdUp(rest)
    case 'down': if (ours()) { await serverStop(); console.log('stopped the dev server') } else console.log('no dev server started by yk'); return 0
    case 'status': return cmdStatus()
    case 'decision': { const [line, ok] = await decisionProbe(true); console.log(line); return ok ? 0 : 1 }
    case 'check': return cmdCheck(rest)
    case 'gradle': return cmdGradle(rest)
    case 'tool': return cmdTool(rest)
    case 'pickup': return cmdPickup(rest)
    case 'ticket': return (await import('../../12-ticket/src/yk-ticket.ts')).cmdTicket(rest, { ykPath: YK })
    case 'plan': return planMain(rest)
    case 'advise': return adviseMain(rest)
    case 'heal': return healReport(rest)
    case 'watch': {
      const session = rest[0] ?? die('usage: yk watch <session> <execution> [--all|--stages]')
      const target = rest[1] ?? die('usage: yk watch <session> <execution> [--all|--stages]')
      return cmdWatch(session, target, rest[2])
    }
    case '_notify': return cmdNotifyRun(rest[0] ?? die('session'), rest[1] ?? die('execution'))
    case '_refresh-activity': await refreshActivity(); return 0
    case 'tree': return cmdTree(rest[0])
    case 'pause': case 'resume': case 'stop': return cmdRunControl(cmd, rest)
    case 'skip': case 'unskip': case 'retry': return cmdNodeControl(cmd, rest)
    case 'break': return cmdBreak(rest)
    case 'step': return cmdStep(rest)
    case 'signals': return cmdSignals(rest[0])
    case 'approve': case 'reject': return cmdAnswer(cmd, rest)
    case 'stats': return cmdStats(rest)
    case 'detail': return cmdDetail(rest)
    case 'statusline': return cmdStatusline()
    case 'bench': return cmdBench(rest)
    case 'mcp-headers': console.log(JSON.stringify({ 'X-API-Key': devKey() }, null, 2)); return 0
    case 'sum': console.log(changesSum(REPO_ROOT)); return 0
    case 'result': {
      process.env.YUKTA_SERVER_URL ??= DEV_URL
      return printResult(rest[0] ?? die('usage: yk result <session> <execution>'), rest[1] ?? die('usage: yk result <session> <execution>'))
    }
    case 'cli': { process.env.YUKTA_SERVER_URL ??= DEV_URL; const p = Bun.spawnSync([findCli(), ...rest], { stdio: ['inherit', 'inherit', 'inherit'] }); return p.exitCode ?? 1 }
    case 'help': case '-h': case '--help': console.log(usage()); return 0
    default: console.error(usage()); return die(`unknown command '${cmd}'`)
  }
}

void sleep
void onPath
