// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// Offline test of the decision model in the ticket pipeline (#1883; design 3.2, 15.8; 13.1 item 9, 15.13 item 34) against the fake Ollama: shadow mode logs and
// the static rule decides, an unreachable or broken model falls back with one WARN line, `on` is a config change only, the digests hold facts and respect
// the cap, and the cause decision feeds the ladder.
import { afterAll, beforeAll, expect, setDefaultTimeout, test } from 'bun:test'
import { cpSync, mkdirSync, rmSync } from 'node:fs'
import { join } from 'node:path'
import { report } from '../../11-dogfood/src/decisions-report.ts'
import { triageConf } from '../../11-dogfood/src/lib.ts'
import { DOGFOOD, KIT, git, initRepo, json, kit, put, read, tmp, rm } from './helpers.ts'
import { inputs } from '../src/decision.ts'

setDefaultTimeout(300_000)
let d: string, repo: string, proc: ReturnType<typeof Bun.spawn>, run = '', port = 0
const tsv = (): string => join(d, 'state/decisions.tsv')
const hint = (v: string | null): void => (v === null ? rm(join(d, 'port.hint')) : put(join(d, 'port.hint'), v + '\n'))
const base = (): Record<string, string | undefined> => ({
  YUKTA_DEV_STATE_DIR: join(d, 'state'), YUKTA_DEV_HOME: join(d, 'home'), YUKTA_DECISION_MODEL: 'fake', YUKTA_DECISION_URL: `http://127.0.0.1:${port}`, YUKTA_DECISION: undefined,
})

beforeAll(async () => {
  d = tmp()
  // the fake Ollama runs in its own process: the tests wait for ticketkit with a blocking call
  proc = Bun.spawn([process.execPath, join(DOGFOOD, 'src/fake-ollama.ts'), join(d, 'port')], { stdout: 'ignore', stderr: 'ignore' })
  for (let i = 0; i < 80 && !read(join(d, 'port')); i++) await Bun.sleep(100)
  port = Number(read(join(d, 'port')))
  initRepo(repo = join(d, 'repo'), { 'core/src/main/java/B.java': 'class B {}\n' })
  git(repo, 'update-ref', 'refs/remotes/origin/main', 'HEAD')
  put(join(d, 't.json'), { number: 7, title: 'feat(core): rate limit', labels: [{ name: 'core' }], body: '## Acceptance criteria\n- [ ] limiter rejects the 101st request\n- [ ] tests pass\n' })
  Object.assign(process.env, base()); delete process.env.YUKTA_DECISION
})
afterAll(() => { proc.kill(); rm(d) })

function newrun(): void {
  rm(join(d, 'state/ticket'))
  const out = kit(['init', '--ticket-file', join(d, 't.json'), '--root', repo, '--state-dir', join(d, 'state'), '--no-worktree'], { env: base() }).out
  run = /^RUN_DIR=(.*)$/m.exec(out)![1]!
  kit(['route', run], { env: base() })
}
const decision = (args: string[], env: Record<string, string | undefined> = {}) => kit(['decision', ...args], { env: { ...base(), ...env } })

test('all four decisions start in shadow, with a bar', () => {
  for (const n of ['triage_complexity', 'triage_risk', 'cause', 'merge_risk']) {
    const c = json(join(DOGFOOD, 'decisions.json')).decisions[n]
    expect([c.mode, c.bar.minN, c.bar.minAgreement], n).toEqual(['shadow', 50, 0.9])
  }
})

test('shadow: the static rule decides, the model is logged, with the static label as the outcome', () => {
  newrun(); hint('L')
  const r = decision(['triage', run])
  expect(JSON.parse(r.out.split('\n')[0]!)).toMatchObject({ label: 'S', source: 'static' })
  const routing = json(join(run, 'routing.json'))
  expect([routing.complexity, routing.risk, routing.decided_by]).toEqual(['S', 'low', 'static'])
  const t = read(tsv())
  expect(t.split('\n').filter(l => l.includes('\tdecision\t'))).toHaveLength(2)
  expect(t.split('\n').filter(l => l.includes('\toutcome\t'))).toHaveLength(2)
  expect(t).toMatch(/\tdecision\ttriage_complexity\t\w+\tshadow\tL\t/)
  const rep = report(tsv(), join(DOGFOOD, 'decisions.json')).join('\n')
  expect(rep).toContain('triage_complexity: 1 decisions, 1 with an outcome, agreement 0/1')
  expect(rep).toContain('triage_risk: 1 decisions')
})

test('mode on is a config change only: the model\'s label is used', () => {
  newrun()
  const r = decision(['triage', run], { YUKTA_DECISION_TRIAGE_COMPLEXITY: 'on' }).out.split('\n')[0]!
  const routing = json(join(run, 'routing.json'))
  expect(JSON.parse(r).source).toBe('model')
  expect([routing.complexity, routing.decided_by, routing.models.architect.model]).toEqual(['L', 'model', 'opus'])
})

test('unreachable, broken or oversized: the static rule, one WARN line per decision, the run goes on', () => {
  newrun()
  const a = decision(['triage', run], { YUKTA_DECISION_URL: 'http://127.0.0.1:1' })
  expect(a.code).toBe(0)
  expect(a.err.split('\n').filter(l => l.includes('WARN decision model unavailable'))).toHaveLength(2)
  expect(json(join(run, 'routing.json')).complexity).toBe('S')
  expect(JSON.parse(a.out.split('\n')[0]!).source).toBe('static')
  newrun()
  const b = decision(['triage', run], { YUKTA_DECISION_MODEL: 'stale', YUKTA_DECISION_TRIAGE_COMPLEXITY: 'on' })
  expect(b.code).toBe(0)
  expect(b.err).toContain('WARN decision model unavailable')
  expect(json(join(run, 'routing.json')).complexity).toBe('S')
  newrun()
  expect(decision(['triage', run], { YUKTA_DECISION_MAX_INPUT: '10' }).err).toContain('over the cap of 10')
  // the digests hold facts, never a diff
  expect(inputs('triage_risk', run, undefined).state).not.toMatch(/^(diff|\+\+\+|---|@@)/)
  expect(inputs('triage_complexity', run, undefined).state).toBe('kind=feature criteria=2 modules=core ecosystems=java')
})

test('cause: tests and compile failures from attempt 2, through the ladder', () => {
  newrun(); hint(null)
  let cr = ''
  const mkrun = (): void => {
    cr = join(d, 'cr'); rmSync(cr, { recursive: true, force: true })
    for (const x of ['spec', 'steps/S1/attempt-1', 'steps/S1/attempt-2']) mkdirSync(join(cr, x), { recursive: true })
    for (const f of ['project.json', 'routing.json', 'ticket.json']) cpSync(join(run, f), join(cr, f))
    put(join(cr, 'events.jsonl'), '')
    put(join(cr, 'spec/spec.json'), { spec_version: 1, steps: [{ id: 'S1', depends_on: [], write_files: ['core/src/main/java/A.java'] }] })
    put(join(cr, 'steps/S1/base'), git(repo, 'rev-parse', 'HEAD').out)
    for (const n of [1, 2]) put(join(cr, `steps/S1/attempt-${n}/failure.json`), { step_id: 'S1', attempt: n, stage: 'tests', class: 'test_fail', exit_code: 1, message: 'expected false got true', failing: ['T1'], frames: [] })
  }
  const act = (env: Record<string, string> = {}): string => JSON.parse(kit(['ladder', 'decide', cr, 'S1', '--worktree', repo], { env: { ...base(), ...env } }).out.trim().split('\n').pop()!).action
  mkrun(); hint('spec_gap'); expect(act(), 'cause in shadow: the ladder goes on').toBe('tier_up')
  expect(read(tsv())).toMatch(/\tdecision\tcause\t/)
  mkrun(); expect(act({ YUKTA_DECISION_CAUSE: 'on' }), 'spec_gap (mode on): the architect revises now').toBe('revise')
  mkrun(); hint('spec_conflict'); expect(act({ YUKTA_DECISION_CAUSE: 'on' })).toBe('revise')
  mkrun(); hint('flaky'); expect(act({ YUKTA_DECISION_CAUSE: 'on' }), 'flaky: rerun once unchanged').toBe('retry')
  expect(read(join(cr, 'events.jsonl'))).toContain('"flaky": 1')
  mkdirSync(join(cr, 'steps/S1/attempt-3'), { recursive: true }); cpSync(join(cr, 'steps/S1/attempt-2/failure.json'), join(cr, 'steps/S1/attempt-3/failure.json'))
  expect(act({ YUKTA_DECISION_CAUSE: 'on' }), 'flaky reruns only once').not.toBe('retry')
  mkrun(); hint('impl_bug'); expect(act({ YUKTA_DECISION_CAUSE: 'on' })).toBe('tier_up')
  mkrun(); put(join(cr, 'steps/S1/attempt-2/failure.json'), { ...json(join(cr, 'steps/S1/attempt-2/failure.json')), stage: 'style' })
  put(tsv(), ''); act()
  expect(read(tsv())).not.toContain('\tcause\t')
})

test('merge risk: logged in shadow; in mode on a high score asks for the merge gate', () => {
  newrun(); put(tsv(), ''); hint('9')
  const ask = (env: Record<string, string> = {}): boolean => JSON.parse(kit(['ladder', 'need-approval', run, 'merge', '--worktree', repo], { env: { ...base(), ...env } }).out.trim().split('\n').pop()!).ask
  expect(ask(), 'merge_risk in shadow changes no gate').toBe(false)
  expect(read(tsv())).toMatch(/\tdecision\tmerge_risk\t\w+\tshadow\t9\t/)
  expect(ask({ YUKTA_DECISION_MERGE_RISK: 'on' })).toBe(true)
})

test('the triage URL is a per-machine override', () => {
  const home = join(d, 'home')
  mkdirSync(home, { recursive: true })
  expect(triageConf('url')).toBe(json(join(DOGFOOD, 'heal.json')).triage.url)
  put(join(home, 'triage.json'), { url: 'http://my-lan:11434' })
  expect(triageConf('url')).toBe('http://my-lan:11434')
  process.env.YUKTA_TRIAGE_URL = 'http://env:1'
  expect(triageConf('url')).toBe('http://env:1')
  delete process.env.YUKTA_TRIAGE_URL
  void KIT
})
