// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// Offline test of the decision helpers of lib.ts (decide, decisionProbe) against the fake Ollama: a model that works, a stale and a missing one, an
// unreachable Ollama, a digest over the cap, and YUKTA_DECISION=off. Every failure is a WARN and no answer.
import { afterAll, beforeAll, expect, setDefaultTimeout, test } from 'bun:test'
import { join } from 'node:path'
import { HERE, ts, put, read, rm, startOllama, tmp, withLib } from './helpers.ts'

setDefaultTimeout(120_000)
let d: string, ollama: { port: number; kill: () => void }
const CRIT = JSON.stringify({ easy: 'a small fix', hard: 'needs investigation' })
beforeAll(async () => { d = tmp(); ollama = await startOllama(d) })
afterAll(() => { ollama.kill(); rm(d) })
const env = (extra: Record<string, string | undefined> = {}): Record<string, string | undefined> => ({
  YUKTA_DEV_HOME: join(d, 'home'), YK_DECISION_FRESH: '1', YUKTA_DEV_STATE_DIR: join(d, 'state'), YUKTA_DECISION: 'on', YUKTA_DECISION_URL: `http://127.0.0.1:${ollama.port}`, YUKTA_DECISION_MODEL: 'fake', ...extra,
})
const decide = (state: string, extra: Record<string, string | undefined> = {}, name = 't') => {
  const o = withLib(`const r = await l.decide(${JSON.stringify(name)}, ${JSON.stringify(state)}, 'How hard?', ${CRIT}); console.log(JSON.stringify(r))`, env(extra))
  return { answer: JSON.parse(o.out.trim() || 'null'), err: o.err }
}
const probe = (extra: Record<string, string | undefined> = {}): string => withLib('console.log((await l.decisionProbe(true))[0])', env(extra)).out.trim()

test('a working model answers; the probe says so', () => {
  expect(decide('difficulty: hard').answer).toEqual(['hard', '0.7'])
  expect(probe()).toMatch(/^decision: ok fake/)
})
test('a stale model, a missing model, no model', () => {
  const stale = decide('x', { YUKTA_DECISION_MODEL: 'stale' })
  expect(stale.answer).toBeNull(); expect(stale.err).toMatch(/WARN decision model unavailable.*ollama pull stale/)
  expect(probe({ YUKTA_DECISION_MODEL: 'stale' })).toContain('no longer supported; run: ollama pull stale')
  expect(probe({ YUKTA_DECISION_MODEL: 'missing' })).toContain('is not pulled; run: ollama pull missing')
  const none = decide('x', { YUKTA_DECISION_MODEL: '' })
  expect(none.answer).toBeNull(); expect(none.err).toContain('none configured')
  expect(probe({ YUKTA_DECISION_MODEL: '' })).toContain('not configured; set YUKTA_DECISION_MODEL')
})
test('an oversized digest, the cap raised by YUKTA_DECISION_MAX_INPUT, YUKTA_DECISION=off', () => {
  const big = 'x'.repeat(2000)
  const o = decide(big)
  expect(o.answer).toBeNull(); expect(o.err).toContain('over the cap of 1024')
  expect(decide(big, { YUKTA_DECISION_MAX_INPUT: '5000' }).answer, 'the cap is not raised by YUKTA_DECISION_MAX_INPUT').not.toBeNull()
  expect(decide('x', { YUKTA_DECISION: 'off' }).answer).toBeNull()
  expect(probe({ YUKTA_DECISION: 'off' })).toMatch(/^decision: off/)
})
test('an unreachable Ollama', () => {
  const o = decide('x', { YUKTA_DECISION_URL: 'http://127.0.0.1:1' })
  expect(o.answer).toBeNull(); expect(o.err).toContain('not reachable')
})
test('shadow: the model is asked and the answer recorded, but the caller decides; one decision can be on while the rest is off; the report', () => {
  const sh1 = decide('difficulty: hard', { YUKTA_DECISION: 'shadow' }, 'shadowed')
  expect(sh1.answer, 'shadow mode returned an answer').toBeNull()
  withLib(`l.decisionOutcome('shadowed', 'difficulty: hard', 'hard')`, env())
  decide('difficulty: easy', { YUKTA_DECISION: 'shadow' }, 'shadowed')
  withLib(`l.decisionOutcome('shadowed', 'difficulty: easy', 'hard')`, env())
  expect(decide('difficulty: hard', { YUKTA_DECISION: 'off', YUKTA_DECISION_SHADOWED: 'on' }, 'shadowed').answer, 'a decision set to on stayed off').not.toBeNull()
  const report = ts('src/decisions-report.ts', [join(d, 'state/decisions.tsv'), join(HERE, 'decisions.json')]).out
  expect(report).toContain('shadowed: 3 decisions, 3 with an outcome, agreement 2/3 = 67%')
  expect(report).toContain('stays in shadow')
})
test('the probe is kept: a second call within the TTL answers from the cache even when Ollama is gone', async () => {
  probe()
  ollama.kill()
  await Bun.sleep(300)
  const o = withLib('console.log((await l.decisionProbe(false))[0])', env({ YK_DECISION_FRESH: undefined }))
  expect(o.out).toMatch(/^decision: ok fake/)
  void put; void read
})
