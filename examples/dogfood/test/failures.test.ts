// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// Offline test of failures.ts: fingerprints ignore ids and numbers, `yk report` groups, and file only drafts without --yes.
import { afterAll, beforeAll, expect, test } from 'bun:test'
import { join } from 'node:path'
import { put, rm, tmp, ts } from './helpers.ts'
import { fingerprint } from '../src/failures.ts'

let d: string, f: string
beforeAll(() => {
  d = tmp()
  f = join(d, 'failures.jsonl')
})
afterAll(() => rm(d))

test('a fingerprint ignores ids, numbers and paths but not the error', () => {
  const a = fingerprint('result', 'tests', 'FAILED 3 tests in /tmp/x1/Foo.java\nError: boom 12')
  expect(fingerprint('result', 'tests', 'FAILED 9 tests in /tmp/y2/Foo.java\nError: boom 99')).toBe(a)
  expect(fingerprint('result', 'tests', 'Error: other')).not.toBe(a)
})

test('report groups by fingerprint and file drafts without --yes', () => {
  const r = (fp: string, ts: string) =>
    JSON.stringify({ v: 1, ts, fp, kind: 'crash', where: 'check', evidence: 'Error: x', sha: 'abc', argv: 'check' })
  put(f, [r('aaa', '2026-11-04T10:00:00Z'), r('aaa', '2026-11-04T11:00:00Z'), r('bbb', '2026-11-04T12:00:00Z')].join('\n') + '\n')
  const env = { YUKTA_FAILURES: f }
  const list = ts('yk', ['report'], { env }).out
  expect(list).toMatch(/aaa +x2 .*unfiled/)
  expect(list).toMatch(/bbb +x1/)
  const dr = ts('yk', ['report', 'file'], { env })
  expect(dr.out).toContain('--- DRAFT aaa')
  expect(dr.out).toContain('dfpaaa')
})

test('sweep helpers: span and the ERROR lines of a log', async () => {
  const { span, logErrors } = await import('../src/sweep.ts')
  expect([span('90m'), span('6h'), span('2d'), span('junk')]).toEqual([5400, 21600, 172800, 86400])
  const e = logErrors('INFO a\nERROR boom\n  at x\nINFO b\nINFO c\nINFO d\nERROR second')
  expect(e).toHaveLength(2)
  expect(e[0]).toContain('at x')
})
