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
