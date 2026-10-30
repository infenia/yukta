import type { EngineInterface as Engine, Register } from 'claude-code'

import { rewrite } from './rewrite'

const PANE = 'yk-check'
const CODE = ['*.java', '*.kts', '*.gradle', '*.go', '*.ts', '*.tsx', '*.json']
const HINT =
  'Builds, tests and checks of this repo run through Yukta (yukta skill): yk gradle <args>, yk tool -C <ui|cli> <command>, yk check (gate; background it). Direct gradle, go and pnpm are blocked.'
const PROMPT = /(^|[^a-z])(test|tests|build|compile|check|gradle|lint|spotless|checkstyle|pmd|spotbugs|coverage|ci|failing|issue|epic)([^a-z]|$)/i

let root = ''
let yk = ''
let check: { execution: string; lines: string[]; isDone: boolean } | undefined

const isOff = async ($: Engine) =>
  (await $.env.get('YUKTA_DOGFOOD')) === 'off' || !!(await $.env.get('YUKTA_HEAL_FIXER')) || !yk

const run = async ($: Engine, argv: string[]) => {
  const { stdout } = await $.process.run(argv, { cwd: root })
  return stdout.trim()
}

const changesSum = ($: Engine) => run($, [yk, 'sum'])

const startSession = async ($: Engine, sid: string, source: string | undefined, cwd: string) => {
  const top = await $.process.run(['git', 'rev-parse', '--show-toplevel'], { cwd })
  root = top.exitCode === 0 ? top.stdout.trim() : ''
  yk = root && (await $.fs.exists(`${root}/examples/11-dogfood/yk`)) ? `${root}/examples/11-dogfood/yk` : ''
  if (await isOff($)) return undefined
  const baseline = `${root}/.yukta-dev/baseline-${sid}`
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
  await $.fs.write(`${state}/nudged`, `${sum}\n`)
  return 'Uncommitted code changes: run the quality gate before finishing: yk check (yukta skill: start it in the background; it heals what it can, so review its healed= changes with git diff). YUKTA_DOGFOOD=off skips this.'
}

const followCheck = async ($: Engine) => {
  const started = await run($, [yk, 'check', '--detach'])
  const m = /STARTED execution=(\S+) session=(\S+)/.exec(started)
  if (!m) return started || 'yk check did not start'
  check = { execution: m[1] ?? '', lines: [], isDone: false }
  void (async () => {
    for await (const { text } of $.process.spawn({ argv: [yk, 'watch', m[2] ?? '', m[1] ?? '', '--stages'], cwd: root })) {
      if (check) check.lines.push(...text.split('\n').filter(Boolean))
      $.ui.invalidate('ui.render')
    }
    if (check) check.isDone = true
    $.ui.invalidate('ui.render')
  })()
  await $.ui.open({ id: PANE, title: 'yk check' })
  return `yk check started (execution ${m[1]})`
}

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
    $.clock.every(10000, () => statusLine($))
    return next(e)
  })

  on('command.run', { command: 'yk-plan' }, async $ => ({ text: (await isOff($)) ? 'yukta-dogfood is off' : await run($, [yk, 'plan']) }))
  on('command.run', { command: 'yk-check' }, async $ => ({ text: (await isOff($)) ? 'yukta-dogfood is off' : await followCheck($) }))

  on('ui.render', { component: 'Pane', requestId: PANE }, async ($, e) => {
    const { Box, Button, Text } = $.ui.resolve(e)
    const room = Math.max(1, (e.viewport?.rows ?? 24) - 5)
    return (
      <Box flexDirection="column">
        {!check && <Text dimColor>No check running. Use /yk-check.</Text>}
        {check?.lines.slice(-room).map(line => <Text>{line}</Text>)}
        {check && !check.isDone && (
          <Button key="stop" label="Stop" onPress={() => run($, [yk, 'stop', check?.execution ?? '', '--tree'])} />
        )}
      </Box>
    )
  })
}

async function statusLine($: Engine) {
  if (!(await isOff($))) $.ui.status(await run($, [yk, 'statusline']))
}
