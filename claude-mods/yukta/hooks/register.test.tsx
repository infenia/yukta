import { expect, test } from 'claude-code/testing'

const PANE = {
  plugin: 'yukta',
  component: 'Pane',
  requestId: 'yukta-runs',
  props: { title: 'Yukta', isFocused: false, bodyColumns: 60, placement: 'dock', scroll: { offset: 0, bodyRows: 20 }, view: {} },
} as const

const reply = (data: unknown) => ({ value: { status: 200, ok: true, headers: {}, text: JSON.stringify({ data }) } })

test('the pane lists a running execution and a pending approval, on every surface', async ($, on) => {
  on('command.register', () => ({ value: { command: 'yukta' } }))
  on('clock.now', () => ({ value: 1_000 }))
  on('clock.every', () => ({ value: undefined }))
  on('session.start', (_, e) => ({ cwd: e.cwd }))
  on('http.fetch', (_, e) => {
    if (e.url.includes('/approvals')) {
      return reply([{ executionId: 'e1234567890', workflowId: 'wf', nodeId: 'gate', name: 'approve' }])
    }
    return reply([{ sessionId: 's1', executionId: 'e1234567890', workflowId: 'wf', status: 'RUNNING' }])
  })
  on('ui.toast', () => ({ value: undefined }))
  on('ui.status', () => ({ value: undefined }))
  on('ui.invalidate', () => ({ value: undefined }))
  on('ui.open', () => ({ value: { isPlaced: true } }))

  await $.session.start({ cwd: '/', surface: 'terminal', isInteractive: true })
  await $.command.run({ command: 'yukta' } as never)

  for (const surface of ['terminal', 'desktop'] as const) {
    const ui = await $.ui.mount({ ...PANE, surface })
    expect(await ui.find({ type: 'Text', text: /1 awaiting approval/ })).toBeTruthy()
    expect(await ui.find({ type: 'Button', key: 'stop-e1234567890' })).toBeTruthy()
    expect(await ui.find({ type: 'Text', text: /Insights/ })).toBeTruthy()
    expect(await ui.find({ type: 'Text', text: /Define a workflow/ })).toBeTruthy()
    expect(await ui.find({ type: 'Button', key: 'ok-e1234567890-gate' })).toBeTruthy()
    await ui.unmount()
  }
})
