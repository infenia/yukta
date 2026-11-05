// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// Offline test of ticketkit call's failure handling: what a claude run's output means (classify), which failures are called again,
// the test writer's blocked answer as its own exit code, and its reason reaching the next architect prompt (flow retry-spec).
import { afterAll, beforeAll, expect, test } from 'bun:test'
import { chmodSync, existsSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import { EXIT, classify, promptFor } from '../../src/ticket/call.ts'
import { initRepo, json, kit, put, read, rm, tmp } from './helpers.ts'

let d: string, R: string, W: string, fake: string
const ok = (a: unknown): string => JSON.stringify({ is_error: false, total_cost_usd: 0.01, structured_output: a })

beforeAll(() => {
  d = tmp()
  R = join(d, 'run')
  W = join(d, 'w')
  initRepo(W, { 'core/src/main/java/B.java': 'class B {}\n' })
  put(join(R, 'ticket.json'), {
    ticket_id: '7',
    title: 't',
    kind: 'feature',
    labels: [],
    modules: ['core'],
    acceptance_criteria: ['a'],
    body: 'b',
  })
  put(join(R, 'project.json'), { commands: {}, high_risk_paths: [], default_limits: {}, tests: {} })
  put(join(R, 'routing.json'), {
    complexity: 'S',
    risk: 'low',
    tests_mode: 'cases_to_code',
    budget_usd: 1,
    models: { architect: { model: 'sonnet', effort: 'high' }, test_writer: { model: 'sonnet', effort: 'low' } },
  })
  put(join(R, 'spec/spec.json'), {
    tests_mode: 'cases_to_code',
    test_cases: [{ id: 'T1' }],
    locked_files: ['core/src/test/java/BTest.java'],
  })
  // the fake claude prints the next line of $FAKE_OUT per call (a counter file keeps the place); an empty line is a crash
  fake = join(d, 'claude')
  put(
    fake,
    `#!/usr/bin/env bun\nimport { existsSync, readFileSync, writeFileSync } from 'node:fs'\nconst c = '${d}/n'\nconst n = existsSync(c) ? Number(readFileSync(c, 'utf8')) : 0\nwriteFileSync(c, String(n + 1))\nconst out = readFileSync('${d}/out', 'utf8').split('\\n')[n] ?? ''\nif (out) console.log(out)\nprocess.exit(out ? 0 : 1)\n`,
  )
  chmodSync(fake, 0o755)
})
afterAll(() => rm(d))

const call = (role: string, outs: string[]) => {
  put(join(d, 'out'), outs.join('\n'))
  rm(join(d, 'n'))
  for (const f of readdirSync(R).filter((f) => f.startsWith('claude-'))) rm(join(R, f))
  return kit(['call', role, R, '--worktree', W], { env: { YK_CLAUDE_BIN: fake, TICKET_RETRY_PAUSE_MS: '0', YUKTA_FIXER_MCP: 'off' } })
}
const calls = (): number => Number(read(join(d, 'n')))

test('the ticket workflow sends a blocked test writer, a red-check failure and a validator failure back to the next architect', () => {
  const edges = new Set<string>(
    json(join(import.meta.dir, '../../ticket-session.json')).workflows.ticket.edges.map(
      (e: { source: string; target: string; sourcePort?: string }) => `${e.source}:${e.sourcePort ?? ''}>${e.target}`,
    ),
  )
  for (const e of [
    'test_writer1:failure>retry_spec1',
    'gate_tests1:failure>retry_spec1',
    'retry_spec1:success>architect2',
    'validate_spec1:failure>spec_conflict1',
    'spec_conflict_ask1?:ask>ask_clarify1',
    'spec_conflict_ask1?:retry>architect2',
    'gate_tests2:success>commit_tests',
    'test_writer3:failure>test_writer3.dead',
  ])
    expect(edges.has(e), e).toBe(true)
})

test('classify: crash, caps, transient and other API errors, an answer', () => {
  expect(classify(1, '', 'boom').kind).toBe('crash')
  expect(classify(0, 'not json', '').kind).toBe('crash')
  expect(classify(0, JSON.stringify({ subtype: 'error_max_turns', is_error: true }), '').kind).toBe('limit')
  expect(classify(0, JSON.stringify({ subtype: 'error_max_budget_usd' }), '').kind).toBe('limit')
  expect(classify(1, JSON.stringify({ is_error: true, result: 'API Error: 529 overloaded' }), '').kind).toBe('transient')
  expect(classify(1, JSON.stringify({ is_error: true, result: 'API Error: 400 input_schema does not support oneOf' }), '').kind).toBe('api')
  expect(classify(0, ok({ a: 1 }), '')).toMatchObject({ kind: 'ok', ans: { a: 1 } })
  expect(classify(0, JSON.stringify({ is_error: false, result: '{"a":2}' }), '').ans).toEqual({ a: 2 })
})

test('a crash and a transient error are called again, every raw answer is kept', () => {
  const good = ok({ test_files: [], notes: 'n', blocked: null })
  const o = call('test_writer', ['', JSON.stringify({ is_error: true, result: '429 rate limit' }), good])
  expect(o.code, o.all).toBe(0)
  expect(calls()).toBe(3)
  expect(existsSync(join(R, 'claude-test_writer-run-1.call3.json'))).toBe(true)
  const ev = read(join(R, 'events.jsonl'))
    .trim()
    .split('\n')
    .slice(-3)
    .map((l) => JSON.parse(l).outcome)
  expect(ev).toEqual(['crash', 'transient', 'ok'])
})

test('three crashes, a cap and a 400 end with their own exit code and name', () => {
  let o = call('test_writer', ['', '', ''])
  expect([o.code, calls()]).toEqual([EXIT.api, 3])
  expect(o.err).toContain('cli_crash')
  o = call('test_writer', [JSON.stringify({ subtype: 'error_max_turns', is_error: true })])
  expect([o.code, calls()], 'a cap is not called again with the same limits').toEqual([EXIT.limit, 1])
  o = call('test_writer', [JSON.stringify({ is_error: true, result: '400 bad schema' })])
  expect([o.code, calls()]).toEqual([EXIT.api, 1])
  o = call('test_writer', [ok({ nope: 1 }), ok({ nope: 2 })])
  expect([o.code, calls()], 'a schema-invalid answer gets one retry').toEqual([EXIT.schema, 2])
  expect(o.err).toContain('schema_invalid')
})

test('a blocked test writer exits 3; retry-spec hands the reason to the next architect and resets the worktree', () => {
  const blocked = { category: 'stub_missing', detail: 'no Slug class in the repo' }
  const o = call('test_writer', [ok({ test_files: [], notes: 'n', blocked })])
  expect(o.code).toBe(EXIT.blocked)
  expect(json(join(R, 'spec/test-writer-result.json')).blocked).toEqual(blocked)
  put(join(W, 'core/src/test/java/Stray.java'), 'x\n')
  expect(kit(['flow', 'retry-spec', R, '--worktree', W]).out).toContain('stub_missing')
  expect(existsSync(join(W, 'core/src/test/java/Stray.java')), 'the worktree is clean for the next tests').toBe(false)
  expect(json(join(R, 'spec/downstream.json'))).toEqual({ test_writer_blocked: blocked })
  expect(promptFor('architect', R, undefined, W)).toContain('no Slug class in the repo')
  // the test writer prompt carries its self-check
  expect(promptFor('test_writer', R, undefined, W)).toContain('gate-tests')
})

test('in a mode without tests up front, a blocked test writer changes nothing: exit 0, no self-check', () => {
  const sf = join(R, 'spec/spec.json')
  const prev = read(sf)
  put(sf, { ...JSON.parse(prev), tests_mode: 'existing' })
  rm(join(R, 'spec/downstream.json'))
  try {
    const o = call('test_writer', [ok({ test_files: [], notes: 'n', blocked: { category: 'stub_missing', detail: 'd' } })])
    expect(o.code, o.all).toBe(0)
    expect(promptFor('test_writer', R, undefined, W)).not.toContain('gate-tests')
  } finally {
    put(sf, prev)
  }
})
