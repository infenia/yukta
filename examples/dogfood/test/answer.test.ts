// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// Offline test of `yk approve|reject` (runs.ts cmdAnswer) against a fake server: the ticket gates are ASK_INPUT nodes, so with no node waiting for a signal
// the open input request of the run's tree is answered.
import { afterAll, beforeAll, expect, test } from 'bun:test'

const answers: { path: string; body: any; key: string | null }[] = []
let open: any[] = []
let srv: ReturnType<typeof Bun.serve>
// bun runs every test file in one process: put the environment back afterwards
const saved = { url: process.env.YUKTA_SERVER_URL, token: process.env.YUKTA_TOKEN }

beforeAll(() => {
  srv = Bun.serve({
    hostname: '127.0.0.1',
    port: 0,
    async fetch(req) {
      const u = new URL(req.url)
      const ok = (data: unknown) => Response.json({ data })
      if (u.pathname === '/api/executions') return ok([{ executionId: 'x-root', sessionId: 'dogfood' }])
      if (u.pathname === '/api/executions/x-root/tree') return ok([{ executionId: 'x-root' }, { executionId: 'x-sub' }])
      if (u.pathname === '/api/sessions/dogfood/approvals') return new Response('', { status: 404 })
      if (u.pathname === '/api/inputs') return ok(open)
      if (req.method === 'POST' && u.pathname.endsWith('/answer')) {
        answers.push({ path: u.pathname, body: await req.json(), key: req.headers.get('x-api-key') })
        return ok({})
      }
      return new Response('', { status: 404 })
    },
  })
  process.env.YUKTA_SERVER_URL = `http://127.0.0.1:${srv.port}`
  process.env.YUKTA_TOKEN = 'k'
})
afterAll(() => {
  srv.stop(true)
  for (const [k, v] of [
    ['YUKTA_SERVER_URL', saved.url],
    ['YUKTA_TOKEN', saved.token],
  ] as const)
    if (v === undefined) delete process.env[k]
    else process.env[k] = v
})

const { cmdAnswer } = await import('../src/runs.ts')

test('approve: the one open input request of the tree (a sub-workflow too) is answered, with the reason as rationale', async () => {
  // a short id goes through resolveExec's prefix match
  open = [
    { id: 'r1', executionId: 'x-sub', nodeId: 'approve_spec', kind: 'APPROVAL' },
    { id: 'r9', executionId: 'x-other', nodeId: 'approve_spec', kind: 'APPROVAL' },
  ]
  answers.length = 0
  expect(await cmdAnswer('approve', ['x-root', '--data', 'spec meets the criteria'])).toBe(0)
  expect(answers).toEqual([
    { path: '/api/executions/x-sub/inputs/r1/answer', body: { decision: 'approve', rationale: 'spec meets the criteria' }, key: 'k' },
  ])
})

test('reject: a TEXT request gets the words as text; a named node picks one of several', async () => {
  open = [
    { id: 'r1', executionId: 'x-root', nodeId: 'approve_merge', kind: 'APPROVAL' },
    { id: 'r2', executionId: 'x-sub', nodeId: 'ask_clarify1', kind: 'TEXT' },
  ]
  answers.length = 0
  expect(await cmdAnswer('reject', ['x-root', 'ask_clarify1', '--data', 'Q1: no'])).toBe(0)
  expect(answers[0]).toMatchObject({ path: '/api/executions/x-sub/inputs/r2/answer', body: { decision: 'reject', text: 'Q1: no' } })
})
