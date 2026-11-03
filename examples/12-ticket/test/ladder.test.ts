// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// Offline test of the retry ladder, heal, revision, budgets and approvals (#1882; design 13.1 items 8, 10, 11, 12, 23 and 15.13 items 27, 33): generated
// workflows walked against a throwaway repo, a fake gh and a fake claude, plus the decision rules called directly.
import { afterAll, beforeAll, expect, setDefaultTimeout, test } from 'bun:test'
import { cpSync, existsSync, mkdirSync, readdirSync, rmSync } from 'node:fs'
import { join } from 'node:path'
import { World, git, json, kit, put, read } from './helpers.ts'

setDefaultTimeout(900_000)
let w: World
beforeAll(() => { w = new World() })
afterAll(() => w.cleanup())
const ev = (role?: string): any[] => w.events().filter(e => !role || e.role === role)

test('8. BLOCKED -> architect revision: completed steps untouched, spec v2, the run finishes', async () => {
  await w.walk('twostep', 'true')
  expect(w.rc, w.out + w.err.slice(-300)).toBe(0)
  expect(existsSync(join(w.run, 'spec/spec.v2.json'))).toBe(true)
  expect(json(join(w.run, 'spec/spec.json')).spec_version).toBe(2)
  expect(ev('implementer').filter(e => e.step_id === 'S1')).toHaveLength(1)
  expect(ev('architect_revise').length).toBeGreaterThanOrEqual(1)
  expect(json(join(w.run, 'steps/S1/status.json')).status).toBe('DONE')
  expect(json(join(w.run, 'steps/S2/status.json')).status).toBe('DONE')
  expect(git(join(w.d, 'yukta-issue-7'), 'log', '--format=%s').out.split('\n').filter(l => l.includes('(#7 S1)'))).toHaveLength(1)
  // a revision may not replace a completed step
  const copy = join(w.d, 'run2')
  rmSync(copy, { recursive: true, force: true })
  cpSync(w.run, copy, { recursive: true })
  put(join(copy, 'spec/revision-result.json'), { ...json(join(w.run, 'spec/revision-result.json')), replace_steps: [{ id: 'S1' }] })
  expect(kit(['ladder', 'apply-revision', copy]).code).not.toBe(0)
})

test('10. three failed attempts: attempt 2 on the same model, attempt 3 one tier up, then the architect revises, then a person', async () => {
  await w.walk('happy', 'false')
  expect(w.rc).not.toBe(0)
  expect(ev('implementer').slice(0, 3).map(e => e.model).join(' ')).toBe('haiku haiku sonnet')
  expect(w.out).toContain('NODE escalate1 success')
  expect(json(join(w.run, 'revisions.json')).count).toBe(2)
  expect(w.out).toContain('NODE human')
  expect(read(join(w.d, 'gh.log')).split('\n').filter(l => l.includes('item-edit'))).toHaveLength(2)
})

test('11. a budget over its limit pauses the run with a human packet; an approval continues it, a rejection stops it', async () => {
  const gate = join(w.d, 'gate-once.ts')
  put(gate, "#!/usr/bin/env bun\nimport { existsSync, writeFileSync } from 'node:fs'\nif (existsSync(process.env.GATE_FLAG!)) process.exit(0)\nwriteFileSync(process.env.GATE_FLAG!, '')\nprocess.exit(1)\n")
  const flag = join(w.d, 'gate.flag')
  const g = `${process.execPath} ${gate}`
  rmSync(flag, { force: true })
  await w.walk('happy', g, { env: { TEST_ASK: 'approve', FAKE_COST: '1.5', GATE_FLAG: flag } })
  expect(w.rc, w.out).toBe(0)
  expect(w.err).toContain('ASK ask_pause1')
  expect(read(join(w.run, 'human-packet.md')).length).toBeGreaterThan(0)
  expect(read(join(w.run, 'human-packet.md'))).toContain('over the')
  expect(json(join(w.run, 'extension.json')).factor).toBe(2)
  rmSync(flag, { force: true })
  await w.walk('happy', g, { env: { TEST_ASK: 'reject', FAKE_COST: '1.5', GATE_FLAG: flag } })
  expect(w.rc).not.toBe(0)
  expect(w.out).toContain('NODE paused_rejected1')
  expect(read(join(w.d, 'gh.log')).split('\n').filter(l => l.includes('item-edit'))).toHaveLength(1) // a pause is not a failure: the issue stays claimed
})

test('heal: a mechanical stage is healed (yk check), then the step passes without another attempt', async () => {
  const gate = join(w.d, 'gate-style.ts')
  put(gate, "#!/usr/bin/env bun\nimport { existsSync, writeFileSync } from 'node:fs'\nif (existsSync(process.env.GATE_FLAG!)) process.exit(0)\nwriteFileSync(process.env.GATE_FLAG!, '')\nconsole.log('RESULT status=FAILED failed_node=style healed=- execution=e session=s wall=1 cost=$0 bytes=1')\nprocess.exit(1)\n")
  const flag = join(w.d, 'gate.flag')
  rmSync(flag, { force: true })
  await w.walk('happy', `${process.execPath} ${gate}`, { env: { GATE_FLAG: flag } })
  expect(w.rc, w.out).toBe(0)
  expect(w.out).toContain('NODE heal1 success')
  expect(w.out).toContain('NODE validate_heal1 success')
  expect(readdirSync(join(w.run, 'steps/S1')).filter(d => d.startsWith('attempt-'))).toHaveLength(1) // a heal is not an attempt
  // 33. a stage that also fails on clean origin/main is a baseline failure, not the implementer's
  rmSync(flag, { force: true })
  await w.walk('happy', `${process.execPath} ${gate.replace('style', 'style')}`, { baseline: 'false', env: { GATE_FLAG: flag } })
  expect(w.rc, w.out).toBe(0)
  expect(json(join(w.run, 'steps/S1/status.json')).baseline_failure).toBe(true)
  expect(readdirSync(join(w.run, 'steps/S1')).filter(d => d.startsWith('attempt-'))).toHaveLength(1)
})

// ---- decision rules, called directly: a hand-built run
let n = 0
function mk(stage: string, cls: string, result?: object): string {
  const r = join(w.d, `r${++n}`)
  for (const d of ['spec', 'steps/S1/attempt-1', 'review']) mkdirSync(join(r, d), { recursive: true })
  for (const f of ['project.json', 'routing.json', 'ticket.json']) cpSync(join(w.run, f), join(r, f))
  put(join(r, 'events.jsonl'), '')
  cpSync(join(w.run, 'spec/spec.json'), join(r, 'spec/spec.json'))
  put(join(r, 'steps/S1/base'), git(join(w.d, 'yukta-issue-7'), 'rev-parse', 'HEAD').out)
  put(join(r, 'steps/S1/attempt-1/failure.json'), { step_id: 'S1', attempt: 1, stage, class: cls, exit_code: 1, message: 'm', failing: [], frames: [] })
  if (result) put(join(r, 'steps/S1/attempt-1/result.json'), result)
  return r
}
const act = (r: string, ...extra: string[]): any => JSON.parse(kit(['ladder', 'decide', r, 'S1', '--worktree', join(w.d, 'yukta-issue-7'), ...extra]).out.trim().split('\n').pop()!)
const copyFailure = (r: string, to: number): void => { mkdirSync(join(r, `steps/S1/attempt-${to}`), { recursive: true }); cpSync(join(r, 'steps/S1/attempt-1/failure.json'), join(r, `steps/S1/attempt-${to}/failure.json`)) }

test('the decision rules', async () => {
  await w.walk('happy', 'true')
  let r = mk('licenses', 'license_fail'); expect(act(r).action, 'licenses go to a person').toBe('human')
  r = mk('security', 'test_fail'); expect(act(r).action).toBe('human')
  r = mk('compile', 'typecheck_fail'); expect(act(r).action, 'attempt 1 retries on the same model').toBe('retry')
  copyFailure(r, 2); expect(act(r).action, 'attempt 2 failing: attempt 3 one tier up').toBe('tier_up')
  expect(json(join(r, 'steps/S1/model.json')).model).toBe('sonnet')
  copyFailure(r, 3); expect(act(r).action, 'attempt 3 failing: revise').toBe('revise')
  r = mk('timeout', 'timeout'); expect(act(r).action, 'a timeout is rerun once').toBe('retry')
  copyFailure(r, 2); expect(act(r).action, 'a second timeout goes to a person').toBe('human')
  r = mk('style', 'lint_fail'); expect(act(r).action, 'style is healed').toBe('heal'); expect(act(r, '--after-heal').action, 'still failing after heal: revise').toBe('revise')
  r = mk('spotless', 'format_fail'); expect(act(r).action).toBe('retry')
  r = mk('x', 'blocked_reported', { step_id: 'S1', status: 'BLOCKED', blocked: { category: 'spec_conflict', detail: 'd' }, notes: '' }); expect(act(r).action).toBe('revise')
  r = mk('x', 'blocked_reported', { step_id: 'S1', status: 'BLOCKED', blocked: { category: 'missing_context', detail: 'd' }, notes: '' }); expect(act(r).trigger).toBe('step_blocked')
})

test('27. heal edits a locked test and a file outside write_files: reverted and recorded', async () => {
  const r = mk('style', 'lint_fail')
  put(join(r, 'spec/locked.json'), { 'core/src/test/java/LockedTest.json': 'x', 'core/src/test/java/LockedTest.java': 'x' })
  const wt = join(w.d, 'yukta-issue-7')
  put(join(wt, 'core/src/test/java/LockedTest.java'), 'class LockedTest {}\n')
  git(wt, 'add', '-A'); git(wt, 'commit', '-q', '-m', 'locked')
  put(join(r, 'steps/S1/base'), git(wt, 'rev-parse', 'HEAD').out)
  const o = kit(['ladder', 'heal', r, 'S1', '--worktree', wt], { env: { TICKET_HEAL_CMD: 'echo changed >core/src/test/java/LockedTest.java; echo x >core/src/main/java/Other.java; echo ok >core/src/main/java/A.java' } })
  expect(existsSync(join(wt, 'core/src/main/java/Other.java')), 'the outside file is reverted').toBe(false)
  expect(read(join(wt, 'core/src/test/java/LockedTest.java'))).toBe('class LockedTest {}\n')
  expect(read(join(wt, 'core/src/main/java/A.java')).trim(), 'an edit inside write_files stays').toBe('ok')
  expect(JSON.parse(o.out.trim().split('\n').pop()!).reverted.map((x: any) => x.why).sort()).toEqual(['locked test', 'outside write_files'])
  expect(read(join(r, 'steps/S1/scope-violations.jsonl')).length).toBeGreaterThan(0)
  expect(read(join(r, 'events.jsonl'))).toContain('"scope_violation": 1')
})

test('12 and 23. the verdict: code overrides the reviewer', () => {
  const verdict = (result: object): string => {
    const rv = join(w.d, 'rv')
    rmSync(rv, { recursive: true, force: true })
    mkdirSync(join(rv, 'review'), { recursive: true }); mkdirSync(join(rv, 'spec'), { recursive: true })
    cpSync(join(w.run, 'spec/spec.json'), join(rv, 'spec/spec.json'))
    put(join(rv, 'review/result.json'), result)
    kit(['ladder', 'review-verdict', rv])
    return json(join(rv, 'review/result.json')).verdict
  }
  const blocker = { severity: 'blocker', path: 'a', issue: 'i', suggested_action: 's' }
  expect(verdict({ verdict: 'APPROVE', criteria_results: [{ id: 'AC1', met: true, evidence: 'e' }], findings: [blocker] })).toBe('CHANGES_REQUESTED')
  expect(verdict({ verdict: 'APPROVE', criteria_results: [{ id: 'TESTS_FAITHFUL', met: false, evidence: 'e' }], findings: [] })).toBe('CHANGES_REQUESTED')
  const rv = json(join(w.d, 'rv/review/result.json'))
  expect(rv.findings[0].severity).toBe('major')
  expect(json(join(w.d, 'rv/escalation.json')).trigger).toBe('review_findings')
  expect(verdict({ verdict: 'APPROVE', criteria_results: [{ id: 'AC1', met: true, evidence: 'e' }], findings: [{ ...blocker, severity: 'minor' }] })).toBe('APPROVE')
})
