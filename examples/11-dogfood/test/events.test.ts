// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// Offline test of the result events: only a finished run gets one, a run gets exactly one, and a stopped run is recorded.
import { afterAll, beforeAll, expect, test } from 'bun:test'
import { join } from 'node:path'
import { emitResult } from '../src/lib.ts'
import { read, rm, tmp } from './helpers.ts'

let d: string, ev: string
beforeAll(() => { d = tmp(); ev = join(d, 'events.jsonl'); process.env.YUKTA_HEAL_EVENTS = ev })
afterAll(() => { delete process.env.YUKTA_HEAL_EVENTS; rm(d) })
const results = (run: string): any[] => read(ev).split('\n').filter(Boolean).map(l => JSON.parse(l)).filter(e => e.event === 'result' && e.run === run)
const progress = { workflowId: 'check', tasks: [] }

test('a run that is still going has no result', () => {
  for (const s of ['RUNNING', 'PENDING', 'QUEUED', 'PAUSED']) emitResult('s', 'run-live', s, '-', '-', progress)
  expect(results('run-live')).toHaveLength(0)
})
test('the same run reported twice is one result, the first', () => {
  emitResult('s', 'run-1', 'SUCCESS', '-', '-', progress)
  emitResult('s', 'run-1', 'FAILED', 'tests', '-', progress)
  expect(results('run-1').map(e => e.status)).toEqual(['SUCCESS'])
})
test('another run is not affected; a stopped run is a result; an id that contains another does not count as seen', () => {
  emitResult('s', 'run-2', 'STOPPED', '-', '-', { tasks: [] })
  expect(results('run-2').map(e => e.status)).toEqual(['STOPPED'])
  emitResult('s', 'run-22', 'SUCCESS', '-', '-', progress)
  expect(results('run-22')).toHaveLength(1)
})
