import { expect, test } from 'claude-code/testing'

const PLAN_TEXT = [
  '# 3 changed files since abc1234',
  '# gradle: every stage, unscoped',
  'spotless\t.\t./gradlew spotlessCheck -q --console=plain\t./gradlew spotlessApply',
  'compile\t.\t./gradlew compileJava -q --console=plain\t',
].join('\n')

const pane = (requestId: 'yk-plan' | 'yk-check', surface: 'terminal' | 'desktop') =>
  ({
    plugin: 'yukta-dogfood',
    component: 'Pane',
    requestId,
    surface,
    props: { title: requestId, isFocused: false, bodyColumns: 80, placement: 'dock', scroll: { offset: 0, bodyRows: 30 }, view: {} },
  }) as const

test('/yk-plan draws the grouped stages, on every surface', async ($, on) => {
  on('command.register', () => ({ value: { command: 'yk-plan' } }))
  on('clock.every', () => ({ value: undefined }))
  on('clock.now', () => ({ value: 1_000 }))
  on('session.start', (_, e) => ({ cwd: e.cwd }))
  on('env.get', () => ({ value: undefined }))
  on('fs.exists', () => ({ value: true }))
  on('ui.open', () => ({ value: { isPlaced: true } }))
  on('ui.invalidate', () => ({ value: undefined }))
  on('process.run', (_, e) => ({
    value: { exitCode: 0, stdout: e.argv[0] === 'git' ? '/repo' : PLAN_TEXT, stderr: '', isStdoutTruncated: false, isStderrTruncated: false },
  }))

  await $.session.start({ cwd: '/repo', surface: 'terminal', isInteractive: true })
  // The command takes more than { command }: the kit's input type is wider than a test needs.
  await $.command.run({ command: 'yk-plan' } as never)

  // the command loads the plan in the background
  for (let i = 0; i < 50; i++) await Promise.resolve()

  for (const surface of ['terminal', 'desktop'] as const) {
    const ui = await $.ui.mount(pane('yk-plan', surface))
    expect(await ui.find({ type: 'Text', text: /3 changed files/ })).toBeTruthy()
    expect(await ui.find({ type: 'Text', text: /spotless/ })).toBeTruthy()
    expect(await ui.find({ type: 'Button', key: 'plan-run' })).toBeTruthy()
    await ui.unmount()
  }
})

test('/yk-check with no run yet says so', async ($) => {
  const ui = await $.ui.mount(pane('yk-check', 'terminal'))
  expect(await ui.find({ type: 'Text', text: /No check yet/ })).toBeTruthy()
  await ui.unmount()
})

// the gate of an agent team: a teammate's TaskCompleted runs `yk check --no-heal` on a dirty tree
const FAILED = [
  'ERROR style/pmd: core/src/A.java:12 UnusedLocalVariable',
  'RESULT status=FAILED failed_node=style healed=- execution=e1 session=dogfood wall=3 cost=$0 bytes=10',
].join('\n')

const gate = (opts: { dirty?: boolean; lastResult?: string; check?: string | Error; off?: boolean }) => async ($: any, on: any) => {
  const calls: string[] = []
  on('classic.TaskCompleted', () => ({})) // nothing beneath the plugin answers the event: the engine's own result, here empty
  on('env.get', (_: unknown, e: { name: string }) => ({ value: opts.off && e.name === 'YUKTA_DOGFOOD' ? 'off' : undefined }))
  on('fs.exists', () => ({ value: true }))
  on('fs.read', () => ({ value: opts.lastResult ?? '' }))
  on('process.run', (_: unknown, e: { argv: string[] }) => {
    const [a, b] = e.argv
    calls.push(e.argv.join(' '))
    const ok = (stdout: string) => ({ value: { exitCode: 0, stdout, stderr: '', isStdoutTruncated: false, isStderrTruncated: false } })
    if (a === 'git' && b === 'rev-parse') return ok('/repo')
    if (a === 'git' && b === 'status') return ok(opts.dirty === false ? '' : ' M core/A.java')
    if (b === 'sum') return ok('SUM1')
    if (b === 'check') {
      if (opts.check instanceof Error) throw opts.check
      return ok(opts.check ?? 'RESULT status=SUCCESS failed_node=-')
    }
    return ok('')
  })
  return calls
}

const complete = ($: any, teammate?: string) =>
  $.classic.TaskCompleted({ task_id: 't1', task_subject: 'Add retry', ...(teammate ? { teammate_name: teammate } : {}) })

test('a teammate cannot complete a task while the check fails', async ($, on) => {
  const calls = await gate({ check: FAILED })($, on)
  const out = await complete($, 'implementer')
  expect(out.block).toContain('Task "Add retry" cannot be completed yet')
  expect(out.block).toContain('ERROR style/pmd: core/src/A.java:12 UnusedLocalVariable')
  expect(calls.filter(c => c.includes(' check --no-heal'))).toHaveLength(1)
})

// each scenario is its own test: a test registers its hooks once, before it first calls $
for (const [name, check] of [
  ['passes', 'RESULT status=SUCCESS failed_node=-'],
  ['fails open without a RESULT line', 'ERROR x: stopped'],
  ['fails open on a timeout', new Error('timed out')],
] as const) {
  test(`the gate ${name}`, async ($, on) => {
    await gate({ check })($, on)
    expect((await complete($, 'implementer')).block).toBeUndefined()
  })
}

for (const [name, opts, teammate] of [
  ['the lead is not gated', { check: FAILED }, undefined],
  ['a clean tree is not gated', { check: FAILED, dirty: false }, 'implementer'],
  ['a tree already checked is not gated', { check: FAILED, lastResult: 'SUM1\tSUCCESS\tcheck\t-\t1' }, 'implementer'],
  ['YUKTA_DOGFOOD=off is not gated', { check: FAILED, off: true }, 'implementer'],
] as const) {
  test(name, async ($, on) => {
    const calls = await gate(opts)($, on)
    expect((await complete($, teammate)).block).toBeUndefined()
    expect(calls.filter(c => c.includes(' check --no-heal'))).toHaveLength(0)
  })
}
