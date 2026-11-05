// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// Offline test of ticketkit gate-tests with a fake test runner (design 13.1 items 6, 14, 15, 16, 17).
import { afterAll, beforeAll, beforeEach, expect, setDefaultTimeout, test } from 'bun:test'
import { cpSync, rmSync } from 'node:fs'
import { join } from 'node:path'
import { initRepo, json, kit, put, read, rm, tmp } from './helpers.ts'

setDefaultTimeout(300_000)
let d: string, tpl: string, rtpl: string, W: string, R: string
const ATEST = `class ATest {
  @Test @DisplayName("[T1] rejects the 101st request") void a() { assertThat(new A().allow()).isFalse(); }
  @Test @DisplayName("[T2] allows the first request") void b() { assertThat(new A().allow()).isTrue(); }
}
`
const xml = (name: string, status: string): string =>
  (
    ({
      fail: `<testcase name="${name}"><failure type="org.opentest4j.AssertionFailedError" message="expected: false but was: true"/></testcase>`,
      nyi: `<testcase name="${name}"><error type="java.lang.UnsupportedOperationException" message="not implemented"/></testcase>`,
      pass: `<testcase name="${name}"/>`,
      setup: `<testcase name="${name}"><error type="java.lang.NoClassDefFoundError" message="com/x/Missing"/></testcase>`,
    }) as Record<string, string>
  )[status]!
const report = (t1: string, t2: string): void =>
  put(
    join(d, 'report.xml'),
    `<testsuite>\n${xml('[T1] rejects the 101st request', t1)}\n${xml('[T2] allows the first request', t2)}\n</testsuite>\n`,
  )

beforeAll(() => {
  d = tmp()
  tpl = join(d, 'tpl')
  rtpl = join(d, 'rtpl')
  W = join(d, 'w')
  R = join(d, 'run')
  initRepo(tpl, { 'core/src/main/java/B.java': 'class B {}\n' })
  put(
    join(rtpl, 'spec/scaffold.patch'),
    'diff --git a/core/src/main/java/A.java b/core/src/main/java/A.java\nnew file mode 100644\n--- /dev/null\n+++ b/core/src/main/java/A.java\n@@ -0,0 +1 @@\n+class A { boolean allow() { throw new UnsupportedOperationException("not implemented"); } }\n',
  )
  put(join(rtpl, 'spec/spec.json'), {
    tests_mode: 'cases_to_code',
    locked_files: ['core/src/test/java/ATest.java'],
    scaffold_patch: 'spec/scaffold.patch',
    test_cases: [{ id: 'T1' }, { id: 'T2' }],
  })
  put(join(rtpl, 'routing.json'), {})
  // the fake test runner: copies the report into the worktree like a test task would, and fails like one
  put(
    join(d, 'fake.ts'),
    `#!/usr/bin/env bun\nimport { copyFileSync, existsSync, mkdirSync } from 'node:fs'\nif (!existsSync('${d}/report.xml')) process.exit(1)\nmkdirSync('build/out', { recursive: true })\ncopyFileSync('${d}/report.xml', 'build/out/TEST-x.xml')\nprocess.exit(1)\n`,
  )
})
afterAll(() => rm(d))
beforeEach(() => {
  rm(W)
  rm(R)
  rm(join(d, 'report.xml'))
  cpSync(tpl, W, { recursive: true })
  cpSync(rtpl, R, { recursive: true })
  put(join(W, 'core/src/test/java/ATest.java'), ATEST)
})
const gate = (runner = `${process.execPath} ${join(d, 'fake.ts')}`, rep = 'junit:build/out'): string =>
  kit(['gate-tests', R, '--worktree', '.', '--runner', runner, '--report', rep], { cwd: W }).all

test('a good red check: test map from the report, locked hashes, stubs applied once', () => {
  report('fail', 'nyi')
  expect(gate()).toContain('errors=0')
  expect(json(join(R, 'spec/test_map.json')).T1[0], 'test_map built from the report').toBe('[T1] rejects the 101st request')
  expect(json(join(R, 'spec/locked.json'))['core/src/test/java/ATest.java']).toHaveLength(64)
  expect(read(join(W, 'core/src/main/java/A.java')).length, 'stubs applied').toBeGreaterThan(0)
  expect(gate(), 'second run (stubs already applied) must pass').toContain('errors=0')
})
test('14. a case without a test, an unknown tag', () => {
  put(join(W, 'core/src/test/java/ATest.java'), ATEST.replace('T2', 'T9'))
  report('fail', 'fail')
  const o = gate()
  expect(o).toContain('case T2 has no tagged test')
  expect(o).toContain('tag [T9]')
})
test('15. passes before any implementation (not red)', () => {
  report('pass', 'fail')
  expect(gate()).toMatch(/T1.*not red/)
})
test('16. a setup error is not red', () => {
  report('setup', 'fail')
  expect(gate()).toContain('setup, import or syntax error')
})
test('6. no report = compile failure', () => {
  expect(gate()).toContain('compile_or_setup')
})
test('17. a source edit or a stray file is a scope violation', () => {
  report('fail', 'fail')
  put(join(W, 'core/src/main/java/B.java'), 'class B { /* edited */ }\n')
  expect(gate()).toContain('scope_violation: core/src/main/java/B.java')
  rm(W)
  cpSync(tpl, W, { recursive: true })
  put(join(W, 'core/src/test/java/ATest.java'), ATEST)
  put(join(W, 'core/src/main/java/Stray.java'), 'x\n')
  expect(gate()).toContain('scope_violation: core/src/main/java/Stray.java')
})
test('trivial assertion, skipped test, missing locked file', () => {
  report('fail', 'fail')
  put(join(W, 'core/src/test/java/ATest.java'), ATEST.replace('isFalse()', 'isFalse(); assertTrue(true)'))
  expect(gate()).toContain('trivial test (always-true')
  put(join(W, 'core/src/test/java/ATest.java'), ATEST.replace('@Test @Disp', '@Disabled @Test @Disp'))
  expect(gate()).toContain('trivial test (skipped')
  rmSync(join(W, 'core/src/test/java/ATest.java'))
  expect(gate()).toContain('does not exist')
})
test('implementer_writes skips the red check and locks nothing', () => {
  const spec = json(join(R, 'spec/spec.json'))
  spec.tests_mode = 'implementer_writes'
  put(join(R, 'spec/spec.json'), spec)
  expect(gate()).toContain('SKIP gate-tests')
  expect(read(join(R, 'spec/locked.json')), 'a skipped gate must not lock anything').toBe('')
})
test('the report parsers of the other ecosystems', () => {
  put(join(d, 'v.json'), {
    testResults: [
      {
        assertionResults: [
          { fullName: '[T1] a', status: 'failed', failureMessages: ['AssertionError: expected 1 to be 2'] },
          { fullName: '[T2] b', status: 'failed', failureMessages: ['SyntaxError: Unexpected token'] },
        ],
      },
    ],
  })
  let o = gate(`mkdir -p build && cp ${d}/v.json build/v.json`, 'vitest:build/v.json')
  expect(o, 'vitest: a syntax error is not red').toMatch(/case T2.*setup, import or syntax/)
  expect(o, 'vitest: an assertion failure must be accepted').not.toContain('case T1')
  put(
    join(d, 'g.json'),
    [
      { Action: 'output', Test: 'TestA/[T1]_x', Output: '    a_test.go:9: got 1 want 2\n' },
      { Action: 'fail', Test: 'TestA/[T1]_x' },
      { Action: 'output', Test: 'TestA/[T2]_y', Output: 'panic: runtime error\n' },
      { Action: 'fail', Test: 'TestA/[T2]_y' },
    ]
      .map((e) => JSON.stringify(e))
      .join('\n') + '\n',
  )
  o = gate(`cat ${d}/g.json`, 'gotest:stdout')
  expect(o, 'go: a panic is a setup error').toMatch(/case T2.*setup, import or syntax/)
  expect(o, 'go: a got/want failure must be accepted').not.toContain('case T1')
})
