// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// Offline test of ticketkit init, route and validate-spec on a throwaway git repo (design 13.1 items 5, 18, 19, 22; 15.13 items 24, 29).
import { afterAll, beforeAll, expect, setDefaultTimeout, test } from 'bun:test'
import { existsSync, mkdirSync, mkdtempSync, readdirSync, symlinkSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { git, initRepo, json, kit, lines, put, read, rm, tmp } from './helpers.ts'

setDefaultTimeout(300_000)
let d: string,
  repo: string,
  state: string,
  run = ''
const BODY =
  '## Goal\nx\n\n## Acceptance criteria\n- [ ] tests pass\n- [ ] limiter rejects the 101st request\n\n## Notes\n- not a criterion'

const mkticket = (n: number, title: string, labels: string[], body: string): string => {
  const f = join(d, `t${n}.json`)
  put(f, { number: n, title, labels: labels.map((name) => ({ name })), body })
  return f
}
const init = (file: string, ...extra: string[]) =>
  kit(['init', '--ticket-file', file, '--root', repo, '--state-dir', state, '--base', 'HEAD', ...extra])

beforeAll(() => {
  d = tmp()
  repo = join(d, 'repo')
  state = join(d, 'state')
  initRepo(repo, {
    'core/src/main/java/B.java': lines(50),
    'core/build.gradle.kts': 'plugins { }\n',
    'web/build.gradle.kts': 'dependencies { implementation(project(":core")) }\n',
  })
})
afterAll(() => rm(d))

test('init', () => {
  const o = init(mkticket(7, 'feat(core): rate limit', ['core'], BODY), '--no-worktree')
  run = /^RUN_DIR=(.*)$/m.exec(o.out)![1]!
  for (const f of ['ticket.json', 'project.json', 'events.jsonl', 'spec', 'steps'])
    expect(existsSync(join(run, f)), `init: ${f}`).toBe(true)
  expect(json(join(run, 'ticket.json')).acceptance_criteria, 'only the two criteria under the heading count').toHaveLength(2)
  expect(json(join(run, 'ticket.json')).kind).toBe('feature')
  const skipped = init(mkticket(8, 'fix(core): bug', ['core'], 'no criteria here\n- a list item'), '--no-worktree')
  expect([skipped.code, skipped.out]).toEqual([3, expect.stringContaining('SKIPPED no acceptance criteria')])
  expect(readdirSync(join(state, 'ticket')), 'a skipped issue must not create a run dir').toHaveLength(1)
  init(mkticket(7, 'feat(core): rate limit', ['core'], BODY))
  expect(existsSync(join(d, 'yukta-issue-7')), 'init: worktree created').toBe(true)
  const again = init(mkticket(7, 'feat(core): rate limit', ['core'], BODY))
  expect([again.code, again.out]).toEqual([3, expect.stringContaining('SKIPPED branch')])
})

test('route', () => {
  kit(['route', run])
  const r = json(join(run, 'routing.json'))
  expect([r.complexity, r.risk, r.tests_mode, r.models.architect.model, r.models.implementer.model]).toEqual([
    'S',
    'low',
    'implementer_writes',
    'sonnet',
    'haiku',
  ])
  expect(r.models.test_writer, 'no test writer for implementer_writes').toBeNull()
  const routeOf = (title: string, labels: string[], body: string): string => {
    rm(join(d, 'rs'))
    const o = kit([
      'init',
      '--ticket-file',
      mkticket(90, title, labels, body),
      '--root',
      repo,
      '--state-dir',
      join(d, 'rs'),
      '--no-worktree',
    ])
    const rd = /^RUN_DIR=(.*)$/m.exec(o.out)![1]!
    kit(['route', rd])
    const x = json(join(rd, 'routing.json'))
    return [x.complexity, x.risk, x.tests_mode, x.models.architect.model].join(' ')
  }
  const six = '## Acceptance criteria\n- a\n- b\n- c\n- d\n- e\n- f'
  expect(routeOf('fix(core): x', ['core'], BODY), 'bug is repro_only').toBe('S low repro_only sonnet')
  expect(routeOf('docs: x', ['core'], BODY)).toBe('S low existing sonnet')
  expect(routeOf('feat(core): x', ['core'], six), 'six criteria is L, opus').toBe('L low cases_to_code opus')
  expect(routeOf('feat(core): x', ['core', 'risk'], BODY)).toBe('S high cases_to_code opus')
  expect(routeOf('feat: x', ['core', 'ui'], BODY), 'two modules is high').toBe('M high cases_to_code opus')
  expect(routeOf('feat: x', [], BODY), 'unknown module is medium').toBe('S medium cases_to_code sonnet')
})

// ---- validate-spec
const patch = (file: string): string =>
  `diff --git a/${file} b/${file}\nnew file mode 100644\n--- /dev/null\n+++ b/${file}\n@@ -0,0 +1 @@\n+class X {}\n`
const BASE = (): any => ({
  spec_version: 1,
  ticket_id: '7',
  objective: 'Limit',
  risk: 'low',
  assumptions: [],
  scope: {
    allowed_globs: ['core/src/**', 'web/**', 'config/**', 'plus/**'],
    forbidden_globs: [],
    forbidden_imports: [],
    allow_new_dependencies: false,
  },
  acceptance_criteria: [
    { id: 'AC1', text: 't', verification: { kind: 'command', command: 'yk gradle :core:test --tests com.infenia.ATest' } },
  ],
  tests_mode: 'implementer_writes',
  locked_files: [],
  scaffold_patch: 'spec/scaffold.patch',
  test_cases: [{ id: 'T1', title: 't', given: 'g', when: 'w', expect: 'e', kind: 'unit', critical: false }],
  steps: [
    {
      id: 'S1',
      title: 'A',
      depends_on: [],
      covers: ['T1'],
      complexity: 'standard',
      write_files: ['core/src/main/java/A.java'],
      read_files: [{ path: 'core/src/main/java/B.java', lines: '1-40' }],
      instructions: ['do'],
      contracts: [],
      constraints: [],
      non_goals: [],
      done_when: { commands: ['yk gradle :core:test --tests com.infenia.ATest'] },
      limits: { max_diff_lines: 100, max_files: 2 },
    },
  ],
})
const S2 = (): any => ({
  id: 'S2',
  title: 'B',
  depends_on: [],
  covers: [],
  complexity: 'trivial',
  write_files: ['web/W.java'],
  read_files: [],
  instructions: ['do'],
  contracts: [],
  constraints: [],
  non_goals: [],
  done_when: { commands: ['yk gradle :web:test --tests com.infenia.WTest'] },
  limits: { max_diff_lines: 100, max_files: 2 },
})
const check = (name: string, expectation: 'ok' | string, edit: (s: any) => void): void => {
  const s = BASE()
  edit(s)
  put(join(run, 'spec/spec.json'), s)
  const o = kit(['validate-spec', run, '--worktree', repo])
  if (expectation === 'ok') expect(o.code, `${name}: ${o.out}`).toBe(0)
  else {
    expect(o.code, name).toBe(1)
    expect(o.out, name).toContain(expectation)
  }
}

test('validate-spec', () => {
  put(join(run, 'spec/scaffold.patch'), patch('core/src/main/java/A.java'))
  check('base', 'ok', () => {})
  expect(read(join(run, 'spec/spec.hash')).length, 'a valid spec must get a spec.hash').toBeGreaterThan(0)
  check('5 missing read file', 'does not exist', (s) => {
    s.steps[0].read_files[0].path = 'core/src/main/java/Nope.java'
  })
  check('read lines out of range', 'not within 1-50', (s) => {
    s.steps[0].read_files[0].lines = '1-99'
  })
  check('read lines list and single line are valid', 'ok', (s) => {
    s.steps[0].read_files[0].lines = '1-5,10-20,30'
  })
  check('read lines bad list', 'not within 1-50', (s) => {
    s.steps[0].read_files[0].lines = '1-5,60'
  })
  check('&& chain of project commands is valid', 'ok', (s) => {
    s.steps[0].done_when.commands = ['yk gradle :core:test --tests com.infenia.ATest && yk gradle :core:compileJava :core:compileTestJava']
  })
  check('18 tests_mode differs', 'differs from routing', (s) => {
    s.tests_mode = 'cases_to_code'
  })
  check('19 unordered steps share a file', 'both write', (s) => {
    s.steps.push({
      ...S2(),
      write_files: ['core/src/main/java/A.java'],
      covers: ['T1'],
      done_when: { commands: ['yk gradle :core:test --tests com.infenia.ATest'] },
    })
  })
  check('22 adjacent tiny step', 'tiny step', (s) => {
    s.steps.push({
      ...S2(),
      depends_on: ['S1'],
      write_files: ['core/src/main/java/A.java'],
      covers: ['T1'],
      limits: { max_diff_lines: 10, max_files: 2 },
    })
  })
  check('24 guard path', 'guard path', (s) => {
    s.steps[0].write_files = ['config/checkstyle/checkstyle.xml']
  })
  // a policy conflict is a question to a person, not another architect attempt
  expect(json(join(run, 'spec/needs-clarification.json')).questions[0].question).toContain('guard path')
  rm(join(run, 'spec/needs-clarification.json'))
  check('only guards', 'every test case is a guard', (s) => {
    s.tests_mode = 'cases_to_code'
    s.test_cases = s.test_cases.map((c: any) => ({ ...c, guard: true }))
  })
  check('write outside scope', 'outside scope', (s) => {
    s.steps[0].write_files = ['boot/X.java']
  })
  expect(existsSync(join(run, 'spec/needs-clarification.json')), 'an error the architect can fix asks nobody').toBe(false)
  check('not a template', 'not a project command', (s) => {
    s.steps[0].done_when.commands = ['rm -rf /']
  })
  check('command injection', 'not a project command', (s) => {
    s.steps[0].done_when.commands = ['yk gradle :core:test --tests A; rm -rf /']
  })
  check('uncovered case', 'covered by no step', (s) => {
    s.steps[0].covers = []
    s.steps[0].done_when.commands = ['yk gradle :core:test --tests X']
    s.test_cases.push({ id: 'T2', title: 't', given: 'g', when: 'w', expect: 'e', kind: 'unit', critical: false })
  })
  check('not verifiable', 'not independently verifiable', (s) => {
    s.steps[0].covers = []
    s.test_cases = []
    s.steps[0].done_when.commands = ['yk gradle :core:compileJava :core:compileTestJava']
  })
  check('three steps for S', 'exceed the 2 allowed', (s) => {
    s.steps.push({ ...S2(), id: 'S3', depends_on: ['S1'] }, { ...S2(), id: 'S4', depends_on: ['S1'], write_files: ['web/Z.java'] })
  })
  check('cycle', 'cycle', (s) => {
    s.steps.push({ ...S2(), depends_on: ['S1'] })
    s.steps[0].depends_on = ['S2']
  })
  check('unknown dependency', 'unknown S9', (s) => {
    s.steps[0].depends_on = ['S9']
  })
  check('module order: consumer first is rejected', 'order the provider first', (s) => {
    s.steps.push(S2())
    s.steps[0].depends_on = ['S2']
  })
  check('module order: provider first is fine', 'ok', (s) => {
    s.steps.push({ ...S2(), depends_on: ['S1'] })
  })
  check('locked file written', 'locked file', (s) => {
    s.locked_files = ['core/src/main/java/A.java']
  })
  check('ticket id', 'ticket_id', (s) => {
    s.ticket_id = '99'
  })
  check('bad schema', 'schema', (s) => {
    delete s.steps
  })
  check('step touching two modules', 'more than one module', (s) => {
    s.steps[0].write_files.push('web/W.java')
  })
  put(join(run, 'spec/scaffold.patch'), 'bad patch\n')
  check('scaffold that does not apply', 'does not apply', () => {})
  put(join(run, 'spec/scaffold.patch'), patch('core/src/main/java/Z.java'))
  check('scaffold touching a file no step writes', 'stubs only', () => {})
  put(join(run, 'spec/scaffold.patch'), patch('core/src/main/java/A.java'))
  put(join(run, 'spec/empty.patch'), '')
  check('high-risk path raises risk', 'ok', (s) => {
    s.steps[0].write_files = ['plus/X.java']
    s.steps[0].read_files = []
    s.scaffold_patch = 'spec/empty.patch'
  })
  expect(json(join(run, 'routing.json')).risk, 'a write in a high-risk path must raise routing.risk').toBe('high')
  void git
})

test('roles in the ticket body override the routed models', () => {
  const body = `${BODY}\n\n## Pipeline roles\n- architect: opus/high\n- reviewer = haiku/low\n- implementer: gpt/high\n`
  const o = init(mkticket(8, 'feat(core): roles', ['core'], body), '--no-worktree')
  const dir = /^RUN_DIR=(.*)$/m.exec(o.out)![1]!
  expect(json(join(dir, 'ticket.json')).roles, 'the bad model is skipped').toEqual({
    architect: { model: 'opus', effort: 'high' },
    reviewer: { model: 'haiku', effort: 'low' },
  })
  kit(['route', dir])
  const m = json(join(dir, 'routing.json')).models
  expect(m.architect).toEqual({ model: 'opus', effort: 'high' })
  expect(m.reviewer).toEqual({ model: 'haiku', effort: 'low' })
  expect(m.implementer.model, 'untouched role keeps the table').toBe('haiku')
})

test('renderBody lists every role, defaults for the missing ones', async () => {
  const { renderBody, rolesOf } = await import('../../src/ticket/roles.ts')
  const body = renderBody('g', ['a', 'b', 'c'], { reviewer: { model: 'opus', effort: 'high' } })
  const r = rolesOf(body)
  expect(Object.keys(r).sort()).toEqual(['architect', 'implementer', 'implementer_tricky', 'reviewer', 'test_writer'])
  expect(r.reviewer, 'given role kept').toEqual({ model: 'opus', effort: 'high' })
  expect(r.architect, 'M/low default').toEqual({ model: 'sonnet', effort: 'high' })
  expect(renderBody('g', ['a'], {}, ['core'])).toContain('test_writer: none')
  const multi = rolesOf(renderBody('g', ['a', 'b', 'c'], {}, ['core', 'ui']))
  expect(multi.architect, 'two modules is high risk, as the router decides').toEqual({ model: 'opus', effort: 'high' })
  expect(multi.reviewer).toEqual({ model: 'opus', effort: 'medium' })
})

test('replaceSection swaps one section and keeps the rest', async () => {
  const { replaceSection, rolesOf } = await import('../../src/ticket/roles.ts')
  const b = '## Goal\nold\n\n## Acceptance criteria\n- [ ] a\n\n## Notes\nkeep me\n'
  const r = replaceSection(b, /acceptance criteria/i, 'Acceptance criteria', '- [ ] x\n- [ ] y')
  expect(r).toContain('- [ ] x\n- [ ] y')
  expect(r).not.toContain('- [ ] a')
  expect(r).toContain('## Goal\nold')
  expect(r).toContain('## Notes\nkeep me')
  expect(rolesOf(replaceSection(b, /pipeline roles/i, 'Pipeline roles', '- reviewer: opus/low')), 'appended when absent').toEqual({
    reviewer: { model: 'opus', effort: 'low' },
  })
})

test('roles in an issue not assigned to you are ignored; a criterion stays one line', async () => {
  const d = mkdtempSync(join(tmpdir(), 'roles-gh-'))
  mkdirSync(join(d, 'bin'))
  symlinkSync(join(import.meta.dir, 'fake/gh.ts'), join(d, 'bin/gh'))
  writeFileSync(join(d, 'status'), 'Ready\n')
  writeFileSync(join(d, 'body'), `${BODY}\n\n## Pipeline roles\n- architect: opus/high\n`)
  const run = (me: string): any => {
    const r = join(d, `r-${me}`) // its own repo: the other tests leave issue/7 and its worktree in the shared one
    initRepo(r, { 'a.txt': 'a\n' })
    const o = kit(['init', '7', '--root', r, '--state-dir', join(d, `s-${me}`), '--no-worktree', '--base', 'HEAD'], {
      env: { PATH: `${d}/bin:${process.env.PATH}`, FAKE_DIR: d, FAKE_ME: me },
    })
    if (!/RUN_DIR/.test(o.out)) throw new Error(o.all)
    return { o, roles: json(join(/^RUN_DIR=(.*)$/m.exec(o.out)![1]!, 'ticket.json')).roles }
  }
  expect(run('me').roles, 'assigned to the runner: honoured').toEqual({ architect: { model: 'opus', effort: 'high' } })
  const other = run('someone')
  expect(other.roles, 'not assigned to the runner: ignored').toEqual({})
  expect(other.o.err).toContain('ignored')
  const { ticketArgs } = await import('../../src/ticket/yk-ticket.ts')
  expect(ticketArgs(['--criterion', 'a\n## Pipeline roles\n- architect: opus/high']).criteria).toEqual([
    'a ## Pipeline roles - architect: opus/high',
  ])
})
