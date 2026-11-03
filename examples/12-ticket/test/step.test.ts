// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// Offline test of pack-step, scope-check, regen, free-fix, digest and event (design 13.1 items 1-4, 7; 15.13 items 25, 26).
import { createHash } from 'node:crypto'
import { afterAll, beforeAll, beforeEach, expect, setDefaultTimeout, test } from 'bun:test'
import { appendFileSync, cpSync, mkdirSync, readFileSync, rmSync } from 'node:fs'
import { join } from 'node:path'
import { KIT, git, initRepo, json, kit, lines, put, read, rm, tmp } from './helpers.ts'

setDefaultTimeout(300_000)
let d: string, tpl: string, rtpl: string, base: string, W: string, R: string
const STEP = {
  id: 'S1', title: 'A', depends_on: [], covers: ['T1'], complexity: 'standard', write_files: ['core/src/main/java/A.java', 'core/src/test/java/ExtraTest.java'],
  read_files: [{ path: 'core/src/main/java/A.java', lines: '1-10' }], instructions: ['do'], contracts: [], constraints: [], non_goals: [],
  done_when: { commands: ['yk gradle :core:test --tests com.infenia.ATest'] }, limits: { max_diff_lines: 20, max_files: 3 },
}

beforeAll(() => {
  d = tmp(); tpl = join(d, 'tpl'); rtpl = join(d, 'rtpl'); W = join(d, 'w'); R = join(d, 'run')
  const locked = 'class LockedTest { void a() { assertThat(1).isEqualTo(1); } }\n'
  initRepo(tpl, {
    'core/src/main/java/A.java': lines(30), 'core/src/test/java/LockedTest.java': locked,
    'core/src/test/java/ExtraTest.java': 'class ExtraTest {\n void a() { assertThat(1).isEqualTo(1); }\n void b() { assertThat(2).isEqualTo(2); }\n}\n',
    'ui/openapi.json': '{"v":1}\n', 'ui/src/types/api.generated.ts': 'v1\n', 'core/build.gradle.kts': 'dependencies {}\n', '.claude/rules.md': 'rule\n',
  })
  base = git(tpl, 'rev-parse', 'HEAD').out.trim()
  put(join(rtpl, 'spec/spec.json'), {
    spec_version: 1, ticket_id: '7', objective: 'Limit', risk: 'low', assumptions: [], scope: { allowed_globs: ['core/**'], forbidden_globs: [], forbidden_imports: ['lodash'], allow_new_dependencies: false },
    acceptance_criteria: [], tests_mode: 'cases_to_code', locked_files: ['core/src/test/java/LockedTest.java'], scaffold_patch: 'spec/scaffold.patch',
    test_cases: [{ id: 'T1', title: 't', given: 'g', when: 'w', expect: 'e', kind: 'unit', critical: false }], steps: [STEP],
  })
  put(join(rtpl, 'spec/locked.json'), { 'core/src/test/java/LockedTest.java': createHash('sha256').update(locked).digest('hex') })
  put(join(rtpl, 'project.json'), {
    ...json(join(KIT, 'project.json')),
    generated: [{ glob: 'ui/openapi.json', trigger: '**/web/**/*Controller*.java', regen: 'echo {"v":2} > ui/openapi.json' }, { glob: 'ui/src/types/api.generated.ts', trigger: 'ui/openapi.json', regen: 'echo v2 > ui/src/types/api.generated.ts' }],
    free_fix: { java: 'echo java >> $FFLOG', ui: 'echo ui {files} >> $FFLOG', go: 'echo go >> $FFLOG' },
  })
  put(join(rtpl, 'ticket.json'), {}); put(join(rtpl, 'events.jsonl'), '')
})
afterAll(() => rm(d))
beforeEach(() => { rm(W); rm(R); cpSync(tpl, W, { recursive: true }); cpSync(rtpl, R, { recursive: true }) })
const w = (f: string): string => join(W, f)
const scope = (): string => kit(['scope-check', R, 'S1', '--base', base, '--worktree', '.'], { cwd: W }).all
const add = (f: string, s: string): void => appendFileSync(w(f), s)

test('scope-check', () => {
  add('core/src/main/java/A.java', '// ok\n'); expect(scope(), 'clean change').toContain('errors=0')
})
test.each([
  ['1 outside write_files', () => put(w('core/src/main/java/Other.java'), 'x\n'), 'scope_violation: core/src/main/java/Other.java is not in write_files'],
  ['2 locked test', () => add('core/src/test/java/LockedTest.java', '// edit\n'), 'locked_file_modified: core/src/test/java/LockedTest.java'],
  ['3 dependency file', () => put(w('core/build.gradle.kts'), 'dependencies { implementation("x") }\n'), 'scope_violation'],
  ['3 forbidden import', () => add('core/src/main/java/A.java', 'import lodash;\n'), 'forbidden import lodash'],
  ['4 too many lines', () => add('core/src/main/java/A.java', lines(40, '')), 'exceed limits.max_diff_lines'],
  ['25 suppression', () => add('core/src/main/java/A.java', '@SuppressWarnings("PMD")\n'), 'new suppression'],
  ['25 NOPMD', () => add('core/src/main/java/A.java', '// NOPMD\n'), 'new suppression'],
  ['25 eslint', () => add('core/src/main/java/A.java', '// eslint-disable-next-line\n'), 'new suppression'],
  ['removed assertion', () => put(w('core/src/test/java/ExtraTest.java'), 'class ExtraTest {\n void a() { assertThat(1).isEqualTo(1); }\n}\n'), 'assertions were removed'],
  ['26 hand-edited generated file', () => put(w('ui/openapi.json'), '{"v":9}\n'), 'ui/openapi.json is generated and was edited by hand'],
  ['guard path not in write_files', () => put(w('.claude/new.md'), 'x\n'), 'not in write_files'],
  ['too many files', () => { add('core/src/main/java/A.java', 'x\n'); add('core/src/test/java/ExtraTest.java', 'x\n'); put(w('core/src/main/java/B.java'), 'y\n'); put(w('core/src/main/java/C.java'), 'y\n') }, 'limits.max_files'],
])('scope-check: %s', (_name, edit, want) => { edit(); expect(scope()).toContain(want) })

test('regen: a controller change regenerates openapi.json, which triggers the types; generated lines do not count against the limit', () => {
  put(w('web/src/main/java/w/XController.java'), 'class XController {}\n')
  const spec = json(join(R, 'spec/spec.json'))
  spec.steps[0].write_files.push('web/src/main/java/w/XController.java')
  put(join(R, 'spec/spec.json'), spec)
  const o = kit(['regen', R, 'S1', '--base', base, '--worktree', '.'], { cwd: W }).all
  expect(o).toContain('ui/openapi.json'); expect(o).toContain('api.generated.ts')
  expect(read(w('ui/src/types/api.generated.ts')).trim(), 'the types were regenerated').toBe('v2')
  const s = scope()
  expect(s).not.toContain('generated and was edited by hand')
  expect(s).toContain('errors=0')
  put(w('ui/src/types/api.generated.ts'), 'hand\n')
  expect(scope(), 'an edit after regen is rejected').toContain('edited by hand')
})

test('free-fix: the formatters per ecosystem', () => {
  add('core/src/main/java/A.java', 'x\n'); put(w('ui/src/a.tsx'), 'x\n')
  kit(['free-fix', R, '--base', base, '--worktree', '.'], { cwd: W, env: { FFLOG: join(d, 'ff.log') } })
  const log = read(join(d, 'ff.log'))
  expect(log).toMatch(/^java/m); expect(log).toMatch(/^ui src\/a\.tsx/m); expect(log).not.toMatch(/^go/m)
})

test('pack-step: byte-stable, line ranges, only the covered cases, the deny list, a prefix that drifts is refused', () => {
  const pack = (): string => kit(['pack-step', R, 'S1', '--worktree', '.'], { cwd: W }).all
  expect(pack()).toContain('PACKED S1')
  const h1 = read(join(R, 'prefix.hash')), p1 = read(join(R, 'steps/S1/packet.json')), pre1 = read(join(R, 'prefix.txt'))
  pack()
  expect([read(join(R, 'steps/S1/packet.json')), read(join(R, 'prefix.txt')), read(join(R, 'prefix.hash'))], 'pack-step must be byte-stable').toEqual([p1, pre1, h1])
  const a = json(join(R, 'steps/S1/packet.json')).context_files.find((c: any) => c.path === 'core/src/main/java/A.java' && c.lines === '1-10')
  expect(a.content.split('\n')).toHaveLength(10)
  expect(json(join(R, 'steps/S1/packet.json')).cases).toHaveLength(1)
  expect(pre1).toContain('"allowed_globs"')
  expect(read(join(R, 'events.jsonl')), 'pack-step must log the prefix hash').toContain('"prefix_hash"')
  const spec = json(join(R, 'spec/spec.json')); spec.steps[0].read_files = [{ path: 'ui/openapi.json' }]; put(join(R, 'spec/spec.json'), spec)
  expect(pack(), 'deny list').toContain('on the deny list')
  spec.steps[0].read_files = []; spec.scope.forbidden_imports = ['moment']; put(join(R, 'spec/spec.json'), spec)
  expect(pack(), 'prefix drift').toContain('cache prefix changed')
})

test('digest: from JUnit, repo frames only, cut at 2 KB and flagged, carried by the next pack', () => {
  mkdirSync(join(d, 'j'), { recursive: true })
  put(join(d, 'j/TEST-a.xml'), `<testsuite><testcase classname="com.infenia.ATest" name="[T1] rejects"><failure message="expected: false but was: true">org.opentest4j.AssertionFailedError: x
\tat com.infenia.A.allow(A.java:42)
\tat org.junit.platform.Foo.run(Foo.java:9)</failure></testcase><testcase classname="com.infenia.ATest" name="ok"/></testsuite>\n`)
  put(join(d, 'empty.txt'), '')
  let o = JSON.parse(kit(['digest', R, 'S1', '--attempt', '2', '--stage', 'tests', '--junit', join(d, 'j'), '--input', join(d, 'empty.txt')]).out)
  expect([o.class, o.failing[0], o.frames[0]]).toEqual(['test_fail', 'com.infenia.ATest.[T1] rejects', 'com.infenia.A.allow(A.java:42)'])
  expect(o.frames, 'digest keeps repo frames only').toHaveLength(1)
  put(join(d, 'big.txt'), `OUTPUT style: ${'x'.repeat(6000)}\n`)
  const raw = kit(['digest', R, 'S1', '--attempt', '1', '--stage', 'style', '--input', join(d, 'big.txt')]).out.trim()
  o = JSON.parse(raw)
  expect(raw.length).toBeLessThanOrEqual(2048)
  expect([o.truncated, o.class]).toEqual([true, 'lint_fail'])
  expect(read(join(R, 'steps/S1/attempt-1/failure.json')).length).toBeGreaterThan(0)
  kit(['pack-step', R, 'S1', '--worktree', '.'], { cwd: W })
  expect(json(join(R, 'steps/S1/packet.json')).prior_attempts, 'pack-step must carry the earlier failure digests').toHaveLength(2)
})

test('event: tokens and cache fields from a claude result', () => {
  put(join(d, 'cr.json'), { usage: { input_tokens: 5200, output_tokens: 900, cache_read_input_tokens: 4100, cache_creation_input_tokens: 10 }, total_cost_usd: 0.004, duration_ms: 14200 })
  kit(['event', R, 'role=implementer', 'step_id=S1', 'attempt=1', 'model=haiku', '--claude-result', join(d, 'cr.json')])
  const e = JSON.parse(read(join(R, 'events.jsonl')).trim().split('\n').pop()!)
  expect([e.role, e.attempt, e.cache_read_tokens, e.cache_write_tokens, e.cost_usd, e.tokens_in]).toEqual(['implementer', 1, 4100, 10, 0.004, 5200])
  void readFileSync; void rmSync
})
