// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// Offline test of heal-report (report, show) on a synthetic event log.
import { afterAll, beforeAll, expect, setDefaultTimeout, test } from 'bun:test'
import { join } from 'node:path'
import { put, rm, tmp, ts } from '../helpers.ts'

setDefaultTimeout(60_000)
let d: string, ev: string
const rep = (...args: string[]) =>
  ts('src/heal/heal-report.ts', [...args, '--events', ev], { env: { YUKTA_HEAL_LEDGER: join(d, 'none.json') } })
const evs: object[] = []
const e = (run: string, n: number, event: string, kw: object): void => {
  evs.push({
    v: 1,
    ts: `2026-10-2${/\d/.test(run[0]!) ? run[0] : '5'}T10:${String(n).padStart(2, '0')}:00Z`,
    run,
    session: 'check-x',
    project: 'yukta',
    event,
    ...kw,
  })
}

beforeAll(() => {
  d = tmp()
  ev = join(d, 'events.jsonl')
  // run 1: tests fail, triage says moderate (sonnet/low), sonnet/low does nothing, sonnet/medium fixes
  e('1aaaaaaaa', 1, 'stage', {
    stage: 'tests',
    secs: 120,
    exit: 1,
    ok: false,
    sig: 's1',
    head: 'OrderServiceTest > shouldApplyDiscount FAILED',
  })
  e('1aaaaaaaa', 2, 'triage', {
    stage: 'tests',
    route: 'level',
    label: 'moderate',
    p: 0.41,
    start: 'sonnet/low',
    model: 'kev:4b-q4_K_M',
    secs: 22.0,
    chars: 3900,
    fallback: null,
  })
  e('1aaaaaaaa', 3, 'fix', {
    stage: 'tests',
    attempt: 1,
    model: 'sonnet',
    effort: 'low',
    fixed: false,
    claimed: false,
    files: [],
    cost: 0.2,
    secs: 300,
    usage: { input: 10, cache_creation: 100, cache_read: 900, output: 20 },
    summary: 'cannot',
  })
  e('1aaaaaaaa', 4, 'recheck', { stage: 'tests', secs: 110, ok: false })
  e('1aaaaaaaa', 5, 'fix', {
    stage: 'tests',
    attempt: 2,
    model: 'sonnet',
    effort: 'medium',
    fixed: true,
    claimed: true,
    files: ['core/A.java'],
    cost: 0.5,
    secs: 400,
    usage: { input: 10, cache_creation: 100, cache_read: 900, output: 20 },
    summary: 'fixed',
  })
  e('1aaaaaaaa', 6, 'recheck', { stage: 'tests', secs: 115, ok: true })
  e('1aaaaaaaa', 7, 'verify', { secs: 600, ok: true })
  e('1aaaaaaaa', 8, 'result', {
    status: 'SUCCESS',
    failed_node: '-',
    healed: 'tests:sonnet/medium',
    secs: 1700,
    scope: 'core',
    durations: { tests: 800 },
  })
  // run 2: triage errors (fallback), haiku/low fixes a file outside the scoped module, run says healed=-
  e('2bbbbbbbb', 1, 'stage', { stage: 'compile', secs: 30, exit: 1, ok: false, sig: 's2', head: "error: ';' expected" })
  e('2bbbbbbbb', 2, 'triage', {
    stage: 'compile',
    route: 'error',
    start: 'sonnet/medium',
    model: 'kev:4b-q4_K_M',
    secs: 60.0,
    chars: 900,
    fallback: 'error',
    error: 'HTTP 500 after 60s',
  })
  e('2bbbbbbbb', 3, 'fix', {
    stage: 'compile',
    attempt: 1,
    model: 'sonnet',
    effort: 'medium',
    fixed: true,
    claimed: true,
    files: ['web/B.java'],
    cost: 0.3,
    secs: 90,
    guard: null,
    summary: 'ok',
  })
  e('2bbbbbbbb', 4, 'result', { status: 'SUCCESS', failed_node: '-', healed: '-', secs: 400, scope: 'core' })
  // run 3: gives up, guard hit; same failure as run 1 twice more
  for (const r of ['3ccccccc', '4ddddddd'])
    e(r, 1, 'stage', { stage: 'tests', secs: 100, exit: 1, ok: false, sig: 's1', head: 'OrderServiceTest > shouldApplyDiscount FAILED' })
  e('3ccccccc', 2, 'triage', {
    stage: 'tests',
    route: 'uncertain',
    p: 0.2,
    start: 'sonnet/medium',
    model: 'kev:4b-q4_K_M',
    secs: 20.0,
    chars: 4000,
    fallback: 'uncertain',
  })
  e('3ccccccc', 3, 'fix', {
    stage: 'tests',
    attempt: 1,
    model: 'opus',
    effort: 'high',
    fixed: false,
    claimed: true,
    files: ['config/pmd.xml'],
    cost: 1.5,
    secs: 900,
    guard: 'touched protected path: config/pmd.xml.',
    summary: 'x',
  })
  e('3ccccccc', 4, 'giveup', { stage: 'tests' })
  e('3ccccccc', 5, 'result', { status: 'FAILURE', failed_node: 'tests', healed: '-', secs: 2500, scope: 'all' })
  e('2bbbbbbbb', 5, 'result', { status: 'SUCCESS', failed_node: '-', healed: '-', secs: 400, scope: 'core' }) // written twice: counted once
  put(ev, evs.map((x) => JSON.stringify(x)).join('\n') + '\n')
})
afterAll(() => rm(d))

test('no log yet', () => {
  expect(ts('src/heal/heal-report.ts', ['report', '--events', join(d, 'missing.jsonl')]).out).toContain('no heal events')
})
test('report', () => {
  const out = rep('report').out
  expect(out).toContain('Runs: 4 with events, 3 finished')
  expect(out).toContain("'FAILURE': 1")
  expect(out).toMatch(/Heals: 3 started, 2 solved, 1 gave up/)
  expect(out).toContain('solved at: sonnet/medium x2')
  expect(out).toContain("Triage: 3 call(s), routes {'level': 1, 'error': 1, 'uncertain': 1}")
  expect(out).toContain("fallbacks {'error': 1, 'uncertain': 1}")
  expect(out).toMatch(/sonnet\/low +1 +0 +0\.20/)
  expect(out).toMatch(/opus\/high +1 +0 +1\.50/)
  expect(out).toContain('total $2.5')
  expect(out).toMatch(/3x in 3 run\(s\), tests, healed 1x: OrderServiceTest/)
  expect(out).toMatch(/tests +3 +3/)
})
test('filters and --json', () => {
  expect(rep('show', '--run', '2bbb').out.trim().split('\n')).toHaveLength(5)
  expect(rep('report', '--since', '2026-10-26').out).toContain('no heal events')
  expect(rep('report', '--project', 'other').out).toContain('no heal events')
  const j = JSON.parse(rep('report', '--json').out)
  expect(j.heals.solved).toBe(2)
  expect(j.findings.length).toBeGreaterThanOrEqual(7)
})
test('a clean log has no findings; events without a run id take the run of the next result of their session', () => {
  put(
    join(d, 'clean.jsonl'),
    JSON.stringify(evs.find((x: any) => x.run === '1aaaaaaaa' && ['stage', 'recheck', 'verify'].includes(x.event))) + '\n',
  )
  expect(ts('src/heal/heal-report.ts', ['report', '--events', join(d, 'clean.jsonl')]).out).not.toContain('Findings')
  put(
    join(d, 'stamp.jsonl'),
    [
      '{"v":1,"ts":"2026-10-25T10:01:00Z","run":"-","session":"s9","project":"yukta","event":"fix","stage":"tests","attempt":1,"model":"haiku","fixed":true,"files":[],"cost":0.1,"secs":5}',
      '{"v":1,"ts":"2026-10-25T10:02:00Z","run":"9eeeeeeee","session":"s9","project":"yukta","event":"result","status":"SUCCESS","failed_node":"-","healed":"tests:haiku","secs":10}',
    ].join('\n') + '\n',
  )
  const shown = ts('src/heal/heal-report.ts', ['show', '--events', join(d, 'stamp.jsonl')]).out
  expect(shown.split('\n').filter((l) => l.includes('"run": "9eeeeeeee"'))).toHaveLength(2)
})
