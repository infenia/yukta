// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// Offline test of the questions of a ticket run (#1921, design/plans/ticket-input-routing.md 3.5): every gate is on the route `ticket` and names its issue and
// run, and a NEEDS_CLARIFICATION answer of the architect becomes one question whose reply reaches the next architect call.
import { afterAll, beforeAll, expect, test } from 'bun:test'
import { join } from 'node:path'
import { json, kit, put, read, rm, tmp } from './helpers.ts'

let d: string, run: string
beforeAll(() => { d = tmp(); run = join(d, 'run'); put(join(run, 'ticket.json'), { ticket_id: 7, title: 'feat(core): limit' }) })
afterAll(() => rm(d))

test('every ASK_INPUT of the ticket workflow is on the route ticket and names its issue, run and gate', () => {
  const wf = json(join(import.meta.dir, '..', 'session.json')).workflows
  const asks = Object.values<any>(wf).flatMap(w => w.nodes).filter((n: any) => n.type === 'ASK_INPUT')
  expect(asks.map((n: any) => n.nodeId)).toEqual(expect.arrayContaining(['approve_spec', 'approve_merge', 'ask_pause1', 'ask_clarify1', 'ask_clarify2']))
  for (const n of asks) expect(n.config, n.nodeId).toMatchObject({ route: 'ticket', context: { issue: '${start.issue}', run_id: '${start.runId}', gate: n.nodeId } })
  expect(asks.find((n: any) => n.nodeId === 'ask_clarify1').config.kind).toBe('TEXT')
})

test('clarification: no question, one prompt with every question, at most two rounds, the reply reaches the architect', () => {
  expect(kit(['ladder', 'clarification', run]).out).toContain('"ask": false')
  put(join(run, 'spec/needs-clarification.json'), { status: 'NEEDS_CLARIFICATION', questions: [{ id: 'Q1', question: 'Per user or global?', why_blocking: 'changes the key' }, { id: 'Q2', question: 'Which window?' }] })
  const o = kit(['ladder', 'clarification', run]).out
  expect(o).toContain('"ask": true')
  for (const t of ['Q1: Per user or global?', 'blocking: changes the key', 'Q2: Which window?', '#7']) expect(o).toContain(t)
  expect(kit(['ladder', 'apply-clarification', run, 'Q1: per user\nQ2: 60 s']).code).toBe(0)
  expect(read(join(run, 'spec/needs-clarification.json')), 'the question is cleared').toBe('')
  expect(json(join(run, 'spec/clarification-answers.json')).rounds.round1).toContain('Q2: 60 s')
  put(join(run, 'spec/needs-clarification.json'), { status: 'NEEDS_CLARIFICATION', questions: [{ id: 'Q3', question: 'again?' }] })
  kit(['ladder', 'apply-clarification', run, 'Q3: yes'])
  put(join(run, 'spec/needs-clarification.json'), { status: 'NEEDS_CLARIFICATION', questions: [{ id: 'Q4', question: 'a third time?' }] })
  expect(kit(['ladder', 'clarification', run]).out, 'a third round is not asked').toContain('"ask": false')
})
