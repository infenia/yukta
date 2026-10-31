import type { EngineInterface as Engine, Register } from 'claude-code'

import { PlanPane, CheckPane } from './panes'
import { applyLine, newCheck, parsePlan, type Check, type Plan } from './plan'
import { rewrite } from './rewrite'

const PLAN_PANE = 'yk-plan'
const CHECK_PANE = 'yk-check'
const CODE = ['*.java', '*.kts', '*.gradle', '*.go', '*.ts', '*.tsx', '*.json']
const HINT =
  'Builds, tests and checks of this repo run through Yukta (yukta skill): yk gradle <args>, yk tool -C <ui|cli> <command>, yk check (gate; background it). Direct gradle, go and pnpm are blocked.'
const PROMPT = /(^|[^a-z])(test|tests|build|compile|check|gradle|lint|spotless|checkstyle|pmd|spotbugs|coverage|ci|failing|issue|epic)([^a-z]|$)/i

let root = ''
let yk = ''
let check: Check | undefined
let plan: Plan | undefined
let frame = 0
let nowMs = 0
let lastDraw = 0

// Finds the repo and its yk. Done on demand: a mod installed or reloaded mid-session never sees SessionStart.
const locate = async ($: Engine, cwd?: string) => {
  const top = await $.process.run(['git', 'rev-parse', '--show-toplevel'], cwd ? { cwd } : undefined)
  root = top.exitCode === 0 ? top.stdout.trim() : ''
  yk = root && (await $.fs.exists(`${root}/examples/11-dogfood/yk`)) ? `${root}/examples/11-dogfood/yk` : ''
}

const isOff = async ($: Engine) => {
  if ((await $.env.get('YUKTA_DOGFOOD')) === 'off' || (await $.env.get('YUKTA_HEAL_FIXER'))) return true
  if (!yk) await locate($)
  return !yk
}

const run = async ($: Engine, argv: string[]) => {
  const { stdout } = await $.process.run(argv, { cwd: root })
  return stdout.trim()
}

const changesSum = ($: Engine) => run($, [yk, 'sum'])

const startSession = async ($: Engine, sid: string, source: string | undefined, cwd: string) => {
  await locate($, cwd)
  if (await isOff($)) return undefined
  const state = `${root}/.yukta-dev`
  await $.process.run(['mkdir', '-p', state])
  await $.process.run(['find', state, '-maxdepth', '1', '-name', 'baseline-*', '-mtime', '+7', '-delete'])
  const baseline = `${state}/baseline-${sid}`
  // a resumed session keeps its first baseline
  if (!(await $.fs.exists(baseline))) await $.fs.write(baseline, `${await changesSum($)}\n`)
  if (source !== 'compact') await $.process.run([yk, 'up', '--background'], { cwd: root })
  await $.env.set('PATH', `${root}/examples/11-dogfood:${(await $.env.get('PATH')) ?? ''}`)
  const server = (await run($, ['timeout', '5', yk, 'status'])).split('\n')[0] || 'starting'
  return `Yukta dev server: ${server}. ${HINT.replace('Builds, tests', 'Build, test')} yk is examples/11-dogfood/yk.`
}

const stopNudge = async ($: Engine, sid: string) => {
  if (await isOff($)) return undefined
  const state = `${root}/.yukta-dev`
  if (!(await run($, ['git', 'status', '--porcelain', '--', ...CODE]))) return undefined
  const sum = await changesSum($)
  const read = async (name: string) => ((await $.fs.exists(`${state}/${name}`)) ? (await $.fs.read(`${state}/${name}`)).trim() : '')
  // nothing changed since the session began: the changes are someone else's
  if (sid && (await read(`baseline-${sid}`)) === sum) return undefined
  if ((await read('nudged')) === sum) return undefined
  // a check already passed on exactly these changes
  const [passedSum, status, scope] = (await read('last-result')).split(/\s+/)
  if (passedSum === sum && status === 'SUCCESS' && scope === 'check') return undefined
  await $.process.run(['mkdir', '-p', state])
  await $.fs.write(`${state}/nudged`, `${sum}\n`)
  return 'Uncommitted code changes: run the quality gate before finishing: yk check (yukta skill: start it in the background; it heals what it can, so review its healed= changes with git diff). YUKTA_DOGFOOD=off skips this.'
}

const loadPlan = async ($: Engine) => {
  plan = parsePlan(await run($, [yk, 'plan']))
  $.ui.invalidate('ui.render')
}

const startCheck = async ($: Engine) => {
  // plan again: the changes may differ from the last plan the pane showed
  await loadPlan($)
  const out = await $.process.run([yk, 'check', '--detach', '--notify'], { cwd: root })
  const m = /STARTED execution=(\S+) session=(\S+)/.exec(out.stdout)
  // yk reports why it refused (for example another healing check is running) on stderr
  if (!m) return (out.stderr || out.stdout).trim().split('\n').slice(-2).join(' ') || 'yk check did not start'
  const current = newCheck(m[1] ?? '', plan ?? { summary: '', notes: [], stages: [] }, await $.clock.now())
  check = current
  void (async () => {
    for await (const { text } of $.process.spawn({ argv: [yk, 'watch', m[2] ?? '', m[1] ?? '', '--stages'], cwd: root })) {
      for (const line of text.split('\n')) if (line.trim()) applyLine(current, line.trim())
      $.ui.invalidate('ui.render')
    }
    // the watch ended without a RESULT line: the run was stopped
    current.isDone = true
    $.ui.invalidate('ui.render')
  })()
  await $.ui.open({ id: CHECK_PANE, title: 'yk check', focus: true })
  return `yk check started (execution ${m[1]})`
}

const stopCheck = async ($: Engine) => {
  if (check) await run($, [yk, 'stop', check.execution, '--tree'])
}

// ponytail: redraws at ~8 fps only while one of the panes drew in the last 2 s
const animate = async ($: Engine) => {
  nowMs = await $.clock.now()
  if (nowMs - lastDraw > 2000) return
  frame += 1
  $.ui.invalidate('ui.render')
}

const OFF = 'yukta-dogfood is off (YUKTA_DOGFOOD=off, or no examples/11-dogfood/yk in this repo)'

export const register: Register = on => {
  on('classic.SessionStart', async ($, e, next) => {
    const context = await startSession($, e.session_id, e.source, e.cwd)
    const out = await next(e)
    return context ? { ...out, additionalContext: [context] } : out
  })

  on('classic.UserPromptSubmit', async ($, e, next) => {
    const out = await next(e)
    return !(await isOff($)) && PROMPT.test(e.prompt) ? { ...out, additionalContext: [HINT] } : out
  })

  on('tool.call', { tool: 'Bash' }, async ($, e, next) => {
    const use = (await isOff($)) ? undefined : rewrite(e.command)
    if (!use) return next(e)
    return {
      deny: `Builds and tests of this repo run through Yukta (yukta skill). Run instead: ${use}   (yk is examples/11-dogfood/yk; the run prints a RESULT line). To bypass for one session the user can set YUKTA_DOGFOOD=off.`,
    }
  })

  on('classic.Stop', async ($, e, next) => {
    const out = await next(e)
    if (e.stop_hook_active) return out
    const block = await stopNudge($, e.session_id)
    return block ? { ...out, block } : out
  })

  on('session.start', async ($, e, next) => {
    await $.command.register({ name: 'yk-check', description: 'Run the yk check gate and follow its stages in a pane' })
    await $.command.register({ name: 'yk-plan', description: 'Show the stages yk check would run for your changes' })
    $.clock.every(120, () => animate($))
    return next(e)
  })

  on('command.run', { command: 'yk-plan' }, async $ => {
    if (await isOff($)) return { text: OFF }
    plan = undefined
    nowMs = lastDraw = await $.clock.now()
    await $.ui.open({ id: PLAN_PANE, title: 'yk plan', focus: true })
    void loadPlan($)
    return { text: 'yk plan opened in a pane' }
  })
  on('command.run', { command: 'yk-check' }, async $ => {
    if (await isOff($)) return { text: OFF }
    nowMs = lastDraw = await $.clock.now()
    return { text: await startCheck($) }
  })

  on('ui.render', { component: 'Pane', requestId: PLAN_PANE }, async ($, e) => {
    lastDraw = nowMs
    const width = e.props.bodyColumns ?? 70
    return PlanPane($.ui.resolve(e), plan, width, frame, () => void startCheck($), () => void loadPlan($))
  })

  on('ui.render', { component: 'Pane', requestId: CHECK_PANE }, async ($, e) => {
    lastDraw = nowMs
    const width = e.props.bodyColumns ?? 70
    return CheckPane($.ui.resolve(e), check, width, frame, nowMs, () => void stopCheck($), () => void startCheck($))
  })
}
