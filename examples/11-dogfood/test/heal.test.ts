// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// Offline test of the steps of heal: the decision-model gate of `judge` (fake Ollama), and the no-progress check of `report`.
import { afterAll, beforeAll, expect, setDefaultTimeout, test } from 'bun:test'
import { join } from 'node:path'
import { ts, initRepo, json, put, read, rm, startOllama, tmp } from './helpers.ts'

setDefaultTimeout(120_000)
let d: string, proj: string, ollama: { port: number; kill: () => void }
beforeAll(async () => { d = tmp(); ollama = await startOllama(d); proj = join(d, 'proj') })
afterAll(() => { ollama.kill(); rm(d) })

test('the decision-model gate of judge: rejects only in mode on; shadow and an unreachable model leave the verdict; files outside the module are listed', () => {
  initRepo(proj, { 'core/A.java': 'int a;\n', 'web/W.java': 'int w;\n' })
  const heal = join(d, 'heal')
  const base = { YUKTA_HEAL_DIR: heal, YUKTA_STAGE: 'compile', YUKTA_MODEL: 'haiku', YUKTA_EFFORT: 'low', YUKTA_MODULE: 'core', YUKTA_DEV_STATE_DIR: join(d, 'state'), YUKTA_DECISION_URL: `http://127.0.0.1:${ollama.port}`, YUKTA_DECISION_MODEL: 'fake' }
  put(join(heal, 'compile.attempt'), '1\n'); put(join(heal, 'compile-1.claude'), '{"claimed": true}\n'); put(join(heal, 'compile-1.before'), ''); put(join(heal, 'compile-1.base'), 'HEAD\n')
  put(join(proj, 'core/A.java'), 'int a = 1;\n'); put(join(proj, 'web/W.java'), 'int w = 1;\n') // the attempt's change: one file in core, one outside
  const judge = (problems: string, extra: Record<string, string | undefined> = {}) => ts('src/heal.ts', ['judge'], { cwd: proj, env: { ...base, YUKTA_PROBLEMS: problems, ...extra } })
  const verdict = (): any => json(join(heal, 'compile-1.verdict'))
  expect(judge('difficulty: unrelated').code, 'shadow mode rejected a clean change').toBe(0)
  expect(verdict().violations).toBe('')
  expect(verdict().outside.join(',')).toBe('web/W.java')
  expect(read(join(d, 'state/decisions.tsv')), 'shadow mode recorded nothing').toContain('guard')
  expect(judge('difficulty: unrelated', { YUKTA_DECISION_GUARD: 'on' }).code, 'an unrelated change passed with the gate on').not.toBe(0)
  expect(verdict().violations).toContain('model: the change looks unrelated')
  expect(judge('difficulty: fine', { YUKTA_DECISION_GUARD: 'on' }).code, 'a fine change was rejected').toBe(0)
  expect(judge('difficulty: unrelated', { YUKTA_DECISION_GUARD: 'on', YUKTA_DECISION_URL: 'http://127.0.0.1:1' }).code, 'an unreachable model rejected a change').toBe(0)
  expect(judge('difficulty: unrelated', { YUKTA_DECISION_GUARD: 'on', YUKTA_DECISION_MODEL: '' }).code, 'no model rejected a change').toBe(0)
})

test('no progress: an attempt after which the stage fails exactly as before (numbers aside) says same=true', () => {
  const dir = join(d, 'np')
  initRepo(dir)
  const env = { YUKTA_HEAL_DIR: join(dir, 'heal'), YUKTA_STAGE: 'compile', YUKTA_MODEL: 'haiku', YUKTA_EFFORT: 'low' }
  const report = (n: number, stdout: string): boolean => {
    put(join(env.YUKTA_HEAL_DIR, 'compile.attempt'), `${n}\n`)
    put(join(env.YUKTA_HEAL_DIR, `compile-${n}.claude`), '{"cost": 0, "summary": "tried", "claimed": true}\n')
    const o = ts('src/heal.ts', ['report'], { cwd: dir, env, input: JSON.stringify({ results: [{ input: { label: 'compile' }, success: false, stdout }] }) })
    return JSON.parse(o.out.trim().split('\n').pop()!).same
  }
  expect(report(1, 'src/A.java:20: error: boom'), 'the first attempt has nothing to repeat').toBe(false)
  expect(report(2, 'src/A.java:21: error: boom'), 'the same failure (line number aside) was not seen').toBe(true)
  expect(report(3, 'src/B.java:5: error: other'), 'a different failure counted as the same').toBe(false)
  expect(report(4, 'src/B.java:5: error: other'), 'the repeat of a new failure was not seen').toBe(true)
})
