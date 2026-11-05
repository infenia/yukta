// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// Offline test of the steps of heal: the decision-model gate of `judge` (fake Ollama), and the no-progress check of `report`.
import { afterAll, beforeAll, expect, setDefaultTimeout, test } from 'bun:test'
import { join } from 'node:path'
import { ts, initRepo, json, put, read, rm, tmp } from '../helpers.ts'

setDefaultTimeout(120_000)
let d: string, proj: string
beforeAll(() => {
  d = tmp()
  proj = join(d, 'proj')
})
afterAll(() => rm(d))

test('judge: a clean change passes and lists the files outside the module', () => {
  initRepo(proj, { 'core/A.java': 'int a;\n', 'web/W.java': 'int w;\n' })
  const heal = join(d, 'heal')
  const env = {
    YUKTA_HEAL_DIR: heal,
    YUKTA_STAGE: 'compile',
    YUKTA_MODEL: 'haiku',
    YUKTA_EFFORT: 'low',
    YUKTA_MODULE: 'core',
    YUKTA_DEV_STATE_DIR: join(d, 'state'),
  }
  put(join(heal, 'compile.attempt'), '1\n')
  put(join(heal, 'compile-1.claude'), '{"claimed": true}\n')
  put(join(heal, 'compile-1.before'), '')
  put(join(heal, 'compile-1.base'), 'HEAD\n')
  put(join(proj, 'core/A.java'), 'int a = 1;\n')
  put(join(proj, 'web/W.java'), 'int w = 1;\n') // the attempt's change: one file in core, one outside
  expect(
    ts('src/heal/heal.ts', ['judge'], { cwd: proj, env: { ...env, YUKTA_PROBLEMS: 'difficulty: unrelated' } }).code,
    'a clean change was rejected',
  ).toBe(0)
  const v: any = json(join(heal, 'compile-1.verdict'))
  expect(v.violations).toBe('')
  expect(v.outside.join(',')).toBe('web/W.java')
})

test('no progress: an attempt after which the stage fails exactly as before (numbers aside) says same=true', () => {
  const dir = join(d, 'np')
  initRepo(dir)
  const env = { YUKTA_HEAL_DIR: join(dir, 'heal'), YUKTA_STAGE: 'compile', YUKTA_MODEL: 'haiku', YUKTA_EFFORT: 'low' }
  const report = (n: number, stdout: string): boolean => {
    put(join(env.YUKTA_HEAL_DIR, 'compile.attempt'), `${n}\n`)
    put(join(env.YUKTA_HEAL_DIR, `compile-${n}.claude`), '{"cost": 0, "summary": "tried", "claimed": true}\n')
    const o = ts('src/heal/heal.ts', ['report'], {
      cwd: dir,
      env,
      input: JSON.stringify({ results: [{ input: { label: 'compile' }, success: false, stdout }] }),
    })
    return JSON.parse(o.out.trim().split('\n').pop()!).same
  }
  expect(report(1, 'src/A.java:20: error: boom'), 'the first attempt has nothing to repeat').toBe(false)
  expect(report(2, 'src/A.java:21: error: boom'), 'the same failure (line number aside) was not seen').toBe(true)
  expect(report(3, 'src/B.java:5: error: other'), 'a different failure counted as the same').toBe(false)
  expect(report(4, 'src/B.java:5: error: other'), 'the repeat of a new failure was not seen').toBe(true)
})
