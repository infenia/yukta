// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// Offline test of the dogfood supervisor of the ticket questions (#1923): `ticketkit advice` on a throwaway run directory, the shape of supervisor/*.json,
// and `yk ticket supervisor` against a fake server.
import { afterAll, beforeAll, expect, test } from 'bun:test'
import { join } from 'node:path'
import { json, kit, put, rm, tmp } from './helpers.ts'

process.env.YUKTA_DEV_HOME = join(tmp(), 'home')
let d: string, state: string
const advice = (run: string, gate: string, extra: string[] = []) =>
  kit(['advice', run, gate, ...extra], { env: { YUKTA_DEV_STATE_DIR: state } })
const mkrun = (id: string, files: Record<string, unknown> = {}): void => {
  put(join(state, 'ticket', id, 'ticket.json'), { ticket_id: 7, title: 't' })
  for (const [f, v] of Object.entries(files)) put(join(state, 'ticket', id, f), v as object)
}
beforeAll(() => {
  d = tmp()
  state = join(d, 'state')
})
afterAll(() => rm(d))

test('spec gate: counts and uncovered cases; never approve', () => {
  mkrun('r1', {
    'spec/spec.json': {
      spec_version: 1,
      risk: 'high',
      acceptance_criteria: [{}, {}],
      test_cases: [{ id: 'T1' }, { id: 'T2' }],
      steps: [{ covers: ['T1'] }],
    },
    'routing.json': { risk: 'high' },
  })
  const o = JSON.parse(advice('r1', 'approve_spec', ['--request', 'req-9']).out)
  expect(o).toMatchObject({ gate: 'approve_spec', recommendation: 'hold', request_id: 'req-9' })
  expect(o.advice).toContain('Cases no step covers: T2')
  mkrun('r2', { 'spec/spec.json': { spec_version: 2, acceptance_criteria: [{}], test_cases: [{ id: 'T1' }], steps: [{ covers: ['T1'] }] } })
  expect(JSON.parse(advice('r2', 'approve_spec').out)).toMatchObject({ recommendation: 'review' })
})

test('merge gate: no review holds, a clean review with a result approves, a blocker holds; confidence stays under 0.9', () => {
  mkrun('m1')
  expect(JSON.parse(advice('m1', 'approve_merge').out).recommendation).toBe('hold')
  mkrun('m2', {
    'review/result.json': { verdict: 'APPROVE', criteria_results: [{ id: 'AC1', met: true }], findings: [] },
    'final-result.txt': 'RESULT status=SUCCESS',
  })
  const ok = JSON.parse(advice('m2', 'approve_merge').out)
  expect(ok.recommendation).toBe('approve')
  expect(ok.confidence).toBeLessThanOrEqual(0.9)
  mkrun('m3', {
    'review/result.json': {
      verdict: 'APPROVE',
      criteria_results: [{ id: 'TESTS_FAITHFUL', met: false }],
      findings: [{ severity: 'major' }],
    },
  })
  expect(JSON.parse(advice('m3', 'approve_merge').out)).toMatchObject({ recommendation: 'hold' })
})

test('pause, clarification and an unknown gate', () => {
  mkrun('p1', {
    'escalation.json': { reason: 'cost budget', step: 'S2' },
    'spec/needs-clarification.json': { questions: [{ id: 'Q1' }, { id: 'Q2' }] },
  })
  expect(JSON.parse(advice('p1', 'ask_pause2').out).advice).toContain('cost budget')
  expect(JSON.parse(advice('p1', 'ask_clarify1').out)).toMatchObject({ recommendation: 'ask-person' })
  expect(JSON.parse(advice('p1', 'whatever').out).confidence).toBe(0.2)
})

test('the run id is request data: a path, a missing run and an empty id are refused', () => {
  for (const bad of ['../etc', 'a/b', '..', '']) expect(advice(bad, 'approve_spec').code, bad).not.toBe(0)
  const o = advice('nope', 'approve_spec')
  expect(o.code).not.toBe(0)
  expect(o.all).toContain('no run nope')
})

test('supervisor/*.json: the profile answers nothing, matches only the route ticket, the workflow edges are closed', () => {
  const dir = join(import.meta.dir, '..', '..', 'supervisor')
  const p = json(join(dir, 'supervisor.json')),
    b = json(join(dir, 'assignments.json')),
    s = json(join(dir, 'session.json'))
  expect(p).toMatchObject({
    type: 'workflow',
    settings: {
      when: "request.spec.routeName == 'ticket'",
      allowedCommands: ['EscalateInputCommand'],
      canAnswer: false,
      sessionId: 'supervisors',
      workflowId: 'dogfood-ticket-advice',
    },
  })
  expect(b).toEqual([{ target: 'session:dogfood' }])
  expect(s.tags['yukta.internal']).toBe('true')
  const wf = s.workflows['dogfood-ticket-advice']
  const ids = new Set<string>(wf.nodes.map((n: any) => n.nodeId))
  for (const e of wf.edges) {
    expect(ids.has(e.source), e.source).toBe(true)
    expect(ids.has(e.target), e.target).toBe(true)
  }
  expect(JSON.stringify(wf)).toContain('__TICKET__')
})

test('yk ticket supervisor: session, supervisor, attachment in that order with the paths filled in; an existing one is left; a refusal stops', async () => {
  const { installSupervisor } = await import('../../src/ticket/yk-ticket.ts')
  const seen: { path: string; body: string }[] = []
  let status = 201
  const srv = Bun.serve({
    hostname: '127.0.0.1',
    port: 0,
    async fetch(req) {
      seen.push({ path: new URL(req.url).pathname, body: await req.text() })
      return new Response('{}', { status })
    },
  })
  try {
    const out = await installSupervisor(`http://127.0.0.1:${srv.port}`, join(import.meta.dir, '..', '..', 'supervisor'), '/state/x')
    expect(out).toEqual(['session.json: installed', 'supervisor.json: installed', 'assignments.json: installed'])
    expect(seen.map((s) => s.path)).toEqual(['/api/sessions', '/api/supervisors', '/api/supervisors/dogfood-ticket-advice/assignments'])
    expect(seen[0]!.body).toContain('/state/x')
    expect(seen[0]!.body).not.toContain('__TICKET__')
    expect(seen[0]!.body).not.toContain('__STATE__')
    status = 409
    expect((await installSupervisor(`http://127.0.0.1:${srv.port}`, join(import.meta.dir, '..', '..', 'supervisor'), '/s'))[0]).toBe(
      'session.json: exists',
    )
  } finally {
    srv.stop(true)
  }
})
