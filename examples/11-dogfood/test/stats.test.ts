// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// Offline test of `yk stats`: it reads the heal events, and its run counts equal the `result` events of the log.
import { afterAll, beforeAll, expect, setDefaultTimeout, test } from 'bun:test'
import { join } from 'node:path'
import { ts, put, rm, tmp } from './helpers.ts'

setDefaultTimeout(60_000)
let d: string, out: string
beforeAll(() => {
  d = tmp()
  put(join(d, 'events.jsonl'), [
    { event: 'result', run: 'r1', workflow: 'check', status: 'SUCCESS', secs: 4 }, { event: 'result', run: 'r2', workflow: 'check', status: 'FAILED', secs: 10 },
    { event: 'result', run: 'r3', workflow: 'check', status: 'RUNNING', secs: 0 }, { event: 'result', run: 'r4', workflow: 'tool', status: 'SUCCESS', secs: 2 },
    { event: 'fix', run: 'r2', stage: 'tests', attempt: 1, model: 'sonnet', fixed: true, cost: 0.5 }, { event: 'fix', run: 'r2', stage: 'tests', attempt: 2, model: 'opus', fixed: false, cost: 0.25 },
    { event: 'fix', run: 'r3', stage: 'reuse', attempt: 1, model: 'auto', fixed: false, cost: 0 }, { event: 'stage', run: 'r1', stage: 'spotless', secs: 1 },
  ].map(e => JSON.stringify(e)).join('\n') + '\n')
  out = ts('yk', ['stats'], { env: { YUKTA_HEAL_EVENTS: join(d, 'events.jsonl'), YUKTA_DEV_HOME: join(d, 'home') } }).out
})
afterAll(() => rm(d))
const row = (k: string): string => out.split('\n').find(l => l.split(/\s+/)[0] === k)?.replace(/\s+/g, ' ') ?? ''

test('runs and failed per kind equal the result events; the median wall and the heal dollars', () => {
  expect(row('check')).toBe('check 3 2 4.0 0.75')
  expect(row('tool').split(' ').slice(0, 3)).toEqual(['tool', '1', '0'])
})
test('per stage and rung: attempts, fixed, rate, dollars', () => {
  expect(out.split('\n').map(l => l.replace(/\s+/g, ' ')).find(l => l.startsWith('tests sonnet'))).toBe('tests sonnet 1 1 100% 0.50')
  expect(out).toMatch(/^tests +opus +1 +0 +0% +0.25$/m)
  expect(out).toMatch(/^reuse +auto +1 +0 +0% +0.00$/m)
})
