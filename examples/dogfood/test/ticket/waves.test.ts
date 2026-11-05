// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// Offline test of parallel waves, the mutation gate and the report (#1884; design 13.1 items 13, 20, 21): the generated workflows walked against a
// throwaway repo, a fake gh and a fake claude, plus the tools called directly.
import { afterAll, beforeAll, expect, setDefaultTimeout, test } from 'bun:test'
import { cpSync, existsSync, mkdirSync, rmSync } from 'node:fs'
import { join } from 'node:path'
import { World, bash, git, json, kit, put, read } from './helpers.ts'

setDefaultTimeout(1_800_000)
let w: World
beforeAll(() => {
  w = new World()
})
afterAll(() => w.cleanup())
const wt = (): string => join(w.d, 'yukta-issue-7')

test('a wave of two independent steps: separate worktrees, merged in step order, a post-wave check', async () => {
  await w.walk('parallel', 'true')
  expect(w.rc, w.out + w.err.slice(-300)).toBe(0)
  for (const n of ['NODE pair1 success', 'NODE merge success', 'NODE wave_check success']) expect(w.out).toContain(n)
  expect(json(join(w.run, 'steps/S1/status.json')).status).toBe('DONE')
  expect(json(join(w.run, 'steps/S2/status.json')).status).toBe('DONE')
  expect(existsSync(join(w.d, 'yukta-issue-7-S2')), 'the sibling worktree is removed after the merge').toBe(false)
  expect(git(wt(), 'log', '--format=%s', 'issue/7').out).toContain('merge S2 into the run branch')
  expect(git(wt(), 'ls-tree', '-r', '--name-only', 'issue/7').out).toMatch(/A\.java[\s\S]*C\.java|C\.java[\s\S]*A\.java/) // the worktree is released (detached at main) once the PR is open
  expect(w.events().some((e) => e.role === 'implementer' && e.step_id === 'S1')).toBe(true)
})

test('two parallel steps finish in less wall-clock time than in sequence', async () => {
  const time = async (mode: string): Promise<number> => {
    const t0 = Date.now()
    await w.walk(mode, 'sleep 3')
    expect(w.rc, `${mode} run failed`).toBe(0)
    return (Date.now() - t0) / 1000
  }
  const par = await time('parallel')
  const seq = await time('chain')
  console.log(`MEASURED parallel=${par.toFixed(0)}s sequential=${seq.toFixed(0)}s (two steps, a 3 s gate each, fake claude)`)
  expect(par).toBeLessThan(seq)
})

test('20. merged steps pass individually but fail the post-wave check: an escalation attributed to the later step', async () => {
  await w.walk('parallel', 'true', { gateWave: 'false' })
  expect(w.out).toContain('NODE wave_check failure')
  expect(w.events().find((e) => e.role === 'wave' && e.escalated === 1).step_id).toBe('S2')
  expect(json(join(w.run, 'steps/S1/status.json')).status).toBe('DONE')
  expect(json(join(w.run, 'steps/S2/status.json')).status).toBe('DONE')
})

test('13. a merge conflict is an escalation, never resolved silently', () => {
  git(wt(), 'worktree', 'prune')
  rmSync(join(w.d, 'cw'), { recursive: true, force: true })
  rmSync(join(w.d, 'cwt'), { recursive: true, force: true })
  git(w.repo, 'worktree', 'prune')
  git(w.repo, 'branch', '-D', 'cw-a')
  git(w.repo, 'branch', '-D', 'cw-b')
  git(w.repo, 'worktree', 'add', '-q', '-b', 'cw-a', join(w.d, 'cwt'), 'main')
  put(join(w.d, 'cwt/Shared.txt'), 'one\n')
  git(join(w.d, 'cwt'), 'add', '-A')
  git(join(w.d, 'cwt'), 'commit', '-q', '-m', 'a')
  git(w.repo, 'worktree', 'add', '-q', '-b', 'cw-b', join(w.d, 'cw'), 'main')
  put(join(w.d, 'cw/Shared.txt'), 'two\n')
  git(join(w.d, 'cw'), 'add', '-A')
  git(join(w.d, 'cw'), 'commit', '-q', '-m', 'b')
  const r = join(w.d, 'cwrun')
  rmSync(r, { recursive: true, force: true })
  for (const d of ['spec', 'steps/S1', 'steps/S2']) mkdirSync(join(r, d), { recursive: true })
  for (const f of ['project.json', 'routing.json', 'ticket.json']) cpSync(join(w.run, f), join(r, f))
  put(join(r, 'events.jsonl'), '')
  put(join(r, 'steps/S1/status.json'), '{"status":"DONE"}\n')
  put(join(r, 'steps/S2/status.json'), '{"status":"DONE"}\n')
  put(join(r, 'wave.json'), {
    base: git(w.repo, 'rev-parse', 'main').out.trim(),
    steps: ['S1', 'S2'],
    worktrees: { S1: 'x', S2: 'x' },
    branch: 'cw-b',
  })
  expect(kit(['waves', 'merge', r, '--worktree', join(w.d, 'cwt')]).code, 'a merge conflict must fail').not.toBe(0)
  const esc = json(join(r, 'escalation.json'))
  expect([esc.step, esc.trigger]).toEqual(['S2', 'step_failed'])
  expect(git(join(w.d, 'cwt'), 'status', '--porcelain').out).toBe('')
  expect(read(join(w.d, 'cwt/Shared.txt')).trim()).toBe('one')
})

test('eligibility: disjoint files pair up; package.json and the lockfile run alone; max_parallel 1 serializes', () => {
  const mkspec = (steps: { id: string; files: string[]; dep?: string[] }[], maxParallel = 2): string => {
    const r = join(w.d, 'er')
    rmSync(r, { recursive: true, force: true })
    mkdirSync(join(r, 'spec'), { recursive: true })
    mkdirSync(join(r, 'steps'), { recursive: true })
    put(join(r, 'project.json'), { ...json(join(w.run, 'project.json')), max_parallel: maxParallel })
    put(join(r, 'spec/spec.json'), { steps: steps.map((s) => ({ id: s.id, depends_on: s.dep ?? [], write_files: s.files })) })
    return r
  }
  const wave = (r: string): string => {
    const x = JSON.parse(kit(['waves', 'next-wave', r]).out)
    return `${x.mode} ${x.steps.join(',')}`
  }
  expect(
    wave(
      mkspec([
        { id: 'S1', files: ['core/A.java'] },
        { id: 'S2', files: ['core/C.java'] },
      ]),
    ),
  ).toBe('pair S1,S2')
  expect(
    wave(
      mkspec([
        { id: 'S1', files: ['core/A.java'] },
        { id: 'S2', files: ['core/C.java'], dep: ['S1'] },
      ]),
    ),
  ).toBe('single S1')
  expect(
    wave(
      mkspec([
        { id: 'S1', files: ['ui/package.json'] },
        { id: 'S2', files: ['core/C.java'] },
      ]),
    ),
  ).toBe('single S1')
  expect(
    wave(
      mkspec([
        { id: 'S1', files: ['core/A.java'] },
        { id: 'S2', files: ['ui/pnpm-lock.yaml'] },
      ]),
    ),
  ).toBe('single S1')
  const r = mkspec(
    [
      { id: 'S1', files: ['core/A.java'] },
      { id: 'S2', files: ['core/C.java'] },
    ],
    1,
  )
  expect(wave(r)).toBe('single S1')
  mkdirSync(join(r, 'steps/S1'), { recursive: true })
  put(join(r, 'steps/S1/status.json'), '{"status":"DONE"}\n')
  expect(wave(r), 'a DONE step is not run again').toBe('single S2')
})

test("warm before fan-out: the sibling waits for the first step's first implementer call", () => {
  const r = join(w.d, 'wr')
  rmSync(r, { recursive: true, force: true })
  mkdirSync(r, { recursive: true })
  put(join(r, 'wave.json'), { steps: ['S1', 'S2'] })
  put(join(r, 'events.jsonl'), '')
  const env = { TICKET_WARM_TIMEOUT: '1' }
  expect(kit(['waves', 'warm-wait', r, 'S2'], { env }).out).toContain('never warmed')
  put(join(r, 'events.jsonl'), '{"role": "implementer", "step_id": "S1"}\n')
  expect(kit(['waves', 'warm-wait', r, 'S2'], { env }).out).toBe('')
  expect(kit(['waves', 'warm-wait', r, 'S1']).out).toBe('')
})

test('the mutation gate: medium or high risk, never :boot, :plus:* or :test-support; survivors above the threshold go to the reviewer', async () => {
  const pit = join(w.d, 'pit.ts')
  put(
    pit,
    '#!/usr/bin/env bun\nimport { mkdirSync, writeFileSync } from \'node:fs\'\nmkdirSync(\'core/build/reports/pitest/x\', { recursive: true })\nwriteFileSync(\'core/build/reports/pitest/x/mutations.xml\', \'<mutations><mutation status="SURVIVED"/><mutation status="SURVIVED"/><mutation status="SURVIVED"/><mutation status="KILLED"/></mutations>\')\n',
  )
  const env = { TICKET_MUTATION_CMD: `${process.execPath} ${pit}`, FAKE_PROMPTS: '1', TEST_ASK: 'approve' }
  await w.walk('high', 'true', { env })
  expect(w.rc, w.out).toBe(0)
  const m = json(join(w.run, 'review/mutation.json'))
  expect([m.exceeds, m.survivors, m.total, m.modules]).toEqual([true, 3, 4, ['core']])
  const prompts = read(join(w.d, 'prompts.log'))
  expect(prompts).toContain('<mutation_finding>')
  expect(prompts).toContain('3 of 4 mutants survived')
  await w.walk('happy', 'true', { env })
  expect(Object.keys(json(join(w.run, 'review/mutation.json')))).toContain('skipped')
  // :boot is never mutated
  const mr = join(w.d, 'mr')
  rmSync(mr, { recursive: true, force: true })
  mkdirSync(join(mr, 'review'), { recursive: true })
  mkdirSync(join(mr, 'steps/S1'), { recursive: true })
  for (const f of ['project.json', 'ticket.json']) cpSync(join(w.run, f), join(mr, f))
  put(join(mr, 'routing.json'), { ...json(join(w.run, 'routing.json')), risk: 'high' })
  put(join(mr, 'steps/S1/base'), git(wt(), 'rev-parse', 'HEAD~1').out)
  put(join(wt(), 'boot/src/main/java/Boot.java'), 'class Boot {}\n')
  git(wt(), 'add', '-A')
  git(wt(), 'commit', '-q', '-m', 'boot')
  put(join(mr, 'steps/S1/base'), git(wt(), 'rev-parse', 'HEAD~1').out)
  kit(['flow', 'mutation', mr, '--worktree', wt()], { env: { TICKET_MUTATION_CMD: 'exit 9' } })
  expect(Object.keys(json(join(mr, 'review/mutation.json'))), ':boot is never mutated').toContain('skipped')
})

test('the report: 10 sample runs, cache hit ratio per role, prefix mismatches, BLOCKED rate', async () => {
  rmSync(join(w.d, 'events.jsonl'), { force: true })
  let i = 0
  for (const m of ['happy', 'happy', 'happy', 'happy', 'happy', 'parallel', 'parallel', 'twostep', 'chain', 'happy']) {
    i++
    await w.walk(m, 'true', { runId: `7-2026010${i % 10}T00000${i}Z` })
    expect(w.rc, `sample run ${i} (${m}) failed: ${w.out}`).toBe(0)
  }
  const ev = join(w.d, 'events.jsonl')
  const r = kit(['report', '--events', ev]).out
  expect(r).toContain('ticket report: 10 runs')
  expect(r).toMatch(/cache hit ratio by role: .*implementer 89%/)
  expect(r).toMatch(/architect .*%/)
  expect(r).toContain('cost share by role')
  expect(r).toContain('prefix_hash mismatches: none')
  expect(r).toMatch(/haiku\/medium\/standard: [0-9]+ steps, first try/)
  expect(JSON.parse(kit(['report', '--events', ev, '--json']).out).blocked_rate).toBeGreaterThan(0)
  // 21. two calls in one run with different prefix_hash and no revision between them: flagged
  const ev2 = join(w.d, 'events2.jsonl')
  put(
    ev2,
    read(ev) +
      '{"run_id":"bad-run","role":"pack","step_id":"S1","prefix_hash":"sha256:aaa"}\n{"run_id":"bad-run","role":"pack","step_id":"S2","prefix_hash":"sha256:bbb"}\n',
  )
  expect(kit(['report', '--events', ev2]).out).toContain('prefix_hash mismatches: bad-run (2 prefixes, 0 revisions)')
  // the BLOCKED rate above 15% is called out
  put(
    join(w.d, 'b.jsonl'),
    '{"run_id":"b","role":"decide","step_id":"S1","attempt":1,"fail_class":"blocked_reported","model":"haiku","effort":"medium","complexity":"standard"}\n',
  )
  expect(kit(['report', '--events', join(w.d, 'b.jsonl')]).out).toContain('the steps are too small')
  void bash
})
