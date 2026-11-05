// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// The process helper launches only the programs of the tooling: anything else is refused before it runs.
import { expect, test } from 'bun:test'
import { run } from '../../src/lib.ts'

test('a program of the tooling runs', () => {
  expect(run(['git', '--version']).code).toBe(0)
  expect(run([process.execPath, '--version']).code).toBe(0)
})
test('any other program is refused (code 126), whatever its path', () => {
  for (const p of ['curl', '/usr/bin/curl', 'rm', './git2', '/tmp/evil/git-not']) {
    const r = run([p, '--version'])
    expect([p, r.code, r.stderr]).toEqual([p, 126, expect.stringContaining('refusing to run')])
  }
})
