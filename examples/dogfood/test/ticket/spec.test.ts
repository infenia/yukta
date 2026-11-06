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
  // bare role lines filed before the (router) rule hold the defaults of the lower risk: a raise uses the table of the raised risk anyway
  const tk = json(join(run, 'ticket.json'))
  put(join(run, 'ticket.json'), {
    ...tk,
    roles: { architect: { model: 'haiku', effort: 'low' }, reviewer: { model: 'haiku', effort: 'low' } },
  })
  check('high-risk path raises risk', 'ok', (s) => {
    s.steps[0].write_files = ['plus/X.java']
    s.steps[0].read_files = []
    s.scaffold_patch = 'spec/empty.patch'
  })
  const raised = json(join(run, 'routing.json'))
  expect(raised.risk, 'a write in a high-risk path must raise routing.risk').toBe('high')
  expect([raised.models.architect.model, raised.models.reviewer.model], 'the high-risk row, not the cheap body roles').toEqual([
    'opus',
    'opus',
  ])
  put(join(run, 'ticket.json'), tk)
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

test('renderBody lists every role; only the given ones bind, the router marks its own', async () => {
  const { renderBody, rolesOf } = await import('../../src/ticket/roles.ts')
  const body = renderBody('g', ['a', 'b', 'c'], { reviewer: { model: 'opus', effort: 'high' } })
  expect(rolesOf(body), 'only the given role is binding').toEqual({ reviewer: { model: 'opus', effort: 'high' } })
  expect(body).toContain('- reviewer: opus/high\n')
  expect(body, 'M/low default shown, not binding').toContain('- architect: sonnet/high (router)')
  expect(renderBody('g', ['a'], {}, ['core'])).toContain('test_writer: none (router')
  const multi = renderBody('g', ['a', 'b', 'c'], {}, ['core', 'ui'])
  expect(multi, 'two modules is high risk, as the router decides').toContain('- architect: opus/high (router)')
  expect(multi).toContain('- reviewer: opus/medium (router)')
  expect(body, 'no sizing given').toContain('## Sizing\n- none given')
  const sized = renderBody('g', ['a'], {}, ['core'], { files: ['core/A.java'], change: ['rename'], open_decisions: 1 })
  expect(sized).toContain('## Sizing\n- files: core/A.java\n- change: rename\n- open_decisions: 1')
  expect(sized, 'an open decision puts the S architect one effort up').toContain('- architect: sonnet/high (router)')
})

test('sizing raises the route, never lowers it; the reasons and a route event are recorded', () => {
  const routeOf = (title: string, labels: string[], body: string, n = 91): any => {
    rm(join(d, 'rz'))
    const o = kit([
      'init',
      '--ticket-file',
      mkticket(n, title, labels, body),
      '--root',
      repo,
      '--state-dir',
      join(d, 'rz'),
      '--no-worktree',
      '--base',
      'HEAD',
    ])
    const rd = /^RUN_DIR=(.*)$/m.exec(o.out)![1]!
    const out = kit(['route', rd]).out
    return { r: json(join(rd, 'routing.json')), t: json(join(rd, 'ticket.json')), out, ev: read(join(rd, 'events.jsonl')), err: o.err }
  }
  const SZ = (s: string): string => `${BODY}\n\n## Sizing\n${s}\n`
  // core/ and web/ are two modules: high risk, as two module labels would be
  let x = routeOf('feat(core): x', ['core'], SZ('- files: core/src/main/java/B.java, web/build.gradle.kts\n- change: rewire'))
  expect([x.r.complexity, x.r.risk, x.r.models.architect.model]).toEqual(['S', 'high', 'opus'])
  expect(x.r.basis.static).toEqual({ complexity: 'S', risk: 'low' })
  expect(x.r.basis.sizing.reasons.join(' ')).toContain('more than one module (core, web)')
  expect(x.out).toContain('static=S/low sizing=S/high')
  expect(JSON.parse(x.ev.trim().split('\n').pop()).role).toBe('route')
  expect(x.t.sizing.paths, 'globs and paths checked against the base tree').toEqual(['core/src/main/java/B.java', 'web/build.gradle.kts'])
  // a directory is everything under it; a path not in the tree is a new file; a high-risk path is high risk before the spec exists
  x = routeOf('feat(core): x', ['core'], SZ('- files: core, plus/New.java'))
  expect(x.t.sizing.new_files).toEqual(['plus/New.java'])
  expect(x.t.sizing.file_count).toBe(3)
  expect(x.r.risk).toBe('high')
  // a heavy change kind is at least M; a glob that matches nothing is noted
  x = routeOf('feat(core): x', ['core'], SZ('- files: core/**/*.kt\n- change: api_contract'))
  expect([x.r.complexity, x.r.risk]).toEqual(['M', 'low'])
  expect(x.err).toContain('match nothing')
  // never lower: six criteria is L whatever the sizing says
  const six = '## Acceptance criteria\n- a\n- b\n- c\n- d\n- e\n- f\n\n## Sizing\n- files: core/build.gradle.kts\n- change: rename'
  x = routeOf('feat(core): x', ['core', 'risk'], six)
  expect([x.r.complexity, x.r.risk], 'sizing cannot undo the criteria count or a risk label').toEqual(['L', 'high'])
  // open decisions: the architect one effort up, unless the ticket chose the architect itself
  x = routeOf('feat(core): x', ['core'], SZ('- files: core/build.gradle.kts\n- open_decisions: 1'))
  expect(x.r.models.architect).toEqual({ model: 'sonnet', effort: 'high' })
  x = routeOf(
    'feat(core): x',
    ['core'],
    SZ('- files: core/build.gradle.kts\n- open_decisions: 1') + '\n## Pipeline roles\n- architect: haiku/low\n',
  )
  expect(x.r.models.architect, 'the ticket role wins').toEqual({ model: 'haiku', effort: 'low' })
  // a bad item is dropped with a note; no sizing at all routes as before
  x = routeOf('feat(core): x', ['core'], SZ('- change: rewrite\n- files: /etc/passwd'))
  expect(x.t.sizing).toBeNull()
  expect(x.err).toContain("change 'rewrite' is not one of")
  expect(x.r.basis.sizing).toBeNull()
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
  writeFileSync(join(d, 'body'), `${BODY}\n\n## Sizing\n- files: a.txt\n\n## Pipeline roles\n- architect: opus/high\n`)
  const run = (me: string): any => {
    const r = join(d, `r-${me}`) // its own repo: the other tests leave issue/7 and its worktree in the shared one
    initRepo(r, { 'a.txt': 'a\n' })
    const o = kit(['init', '7', '--root', r, '--state-dir', join(d, `s-${me}`), '--no-worktree', '--base', 'HEAD'], {
      env: { PATH: `${d}/bin:${process.env.PATH}`, FAKE_DIR: d, FAKE_ME: me },
    })
    if (!/RUN_DIR/.test(o.out)) throw new Error(o.all)
    const t = json(join(/^RUN_DIR=(.*)$/m.exec(o.out)![1]!, 'ticket.json'))
    return { o, roles: t.roles, sizing: t.sizing }
  }
  const mine = run('me')
  expect(mine.roles, 'assigned to the runner: honoured').toEqual({ architect: { model: 'opus', effort: 'high' } })
  expect(mine.sizing.paths).toEqual(['a.txt'])
  const other = run('someone')
  expect(other.roles, 'not assigned to the runner: ignored').toEqual({})
  expect(other.sizing, 'sizing too').toBeNull()
  expect(other.o.err).toContain('ignored')
  const { ticketArgs } = await import('../../src/ticket/yk-ticket.ts')
  const a = ticketArgs([
    '--role',
    'reviewer=auto',
    '--role',
    'architect=opus/low',
    '--file',
    'ui/a.ts, ui/b/**',
    '--change',
    'delete',
    '--open-decisions',
    '2',
  ])
  expect([a.auto, a.roles, a.sizing]).toEqual([
    ['reviewer'],
    { architect: { model: 'opus', effort: 'low' } },
    ['files: ui/a.ts, ui/b/**', 'change: delete', 'open_decisions: 2'],
  ])
  expect(ticketArgs(['--role', 'all=auto']).auto).toHaveLength(5)
  expect(ticketArgs(['--criterion', 'a\n## Pipeline roles\n- architect: opus/high']).criteria).toEqual([
    'a ## Pipeline roles - architect: opus/high',
  ])
})
