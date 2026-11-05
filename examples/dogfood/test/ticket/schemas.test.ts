// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// Offline test (no API cost): every schema accepts its valid fixture and rejects each invalid one; every ticket role runs through claude.ts against a fake
// claude, gets its own constant role file, and its answer validates against its schema.
import { afterAll, beforeAll, expect, setDefaultTimeout, test } from 'bun:test'
import { chmodSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import { DOGFOOD, KIT, json, kit, put, read, rm, sh, tmp } from './helpers.ts'
import { flatSchema } from '../../src/ticket/call.ts'

setDefaultTimeout(300_000)
const fixtures = readdirSync(join(KIT, 'fixtures'))
const schemas = readdirSync(join(KIT, 'schemas'))
  .filter((f) => f.endsWith('.schema.json'))
  .map((f) => f.replace('.schema.json', ''))

test.each(fixtures)('%s: the valid fixture is accepted, every invalid one rejected', (name) => {
  const dir = join(KIT, 'fixtures', name)
  expect(kit(['validate-schema', name, join(dir, 'valid.json')]).code, `${name}: valid.json rejected`).toBe(0)
  const bad = readdirSync(dir).filter((f) => f.startsWith('invalid-'))
  expect(bad.length, `${name}: needs at least two invalid fixtures`).toBeGreaterThanOrEqual(2)
  for (const b of bad) expect(kit(['validate-schema', name, join(dir, b)]).code, `${name}: ${b} accepted`).not.toBe(0)
})
test('every schema has fixtures; unknown schemas and subcommands fail', () => {
  for (const s of schemas) expect(fixtures, `${s}: no fixtures`).toContain(s)
  expect(kit(['validate-schema', 'nope', '/dev/null']).code).not.toBe(0)
  expect(kit(['validate-schema', 'nope', '/dev/null']).all).toContain('no schema')
  expect(kit(['bogus']).code).not.toBe(0)
})

let d: string
beforeAll(() => {
  d = tmp()
  put(
    join(d, 'claude.ts'),
    `#!/usr/bin/env bun\nimport { appendFileSync, readFileSync, writeFileSync } from 'node:fs'\nwriteFileSync('${d}/argv', process.argv.slice(2).join('\\n') + '\\n')\nconsole.log(JSON.stringify({ is_error: false, structured_output: JSON.parse(readFileSync('${d}/answer.json', 'utf8')) }))\nvoid appendFileSync\n`,
  )
  chmodSync(join(d, 'claude.ts'), 0o755)
  put(join(d, 'rules'), 'COMMON\n')
})
afterAll(() => rm(d))

test.each([
  ['architect', 'ticket-spec'],
  ['test_writer', 'test-writer-result'],
  ['implementer', 'step-result'],
  ['reviewer', 'review-result'],
])('role %s answers %s with its own constant role file', (role, schema) => {
  put(join(d, 'answer.json'), read(join(KIT, 'fixtures', schema, 'valid.json')))
  const env = {
    YUKTA_FIXER_MCP: 'off',
    YK_MODEL: 'sonnet',
    YK_EFFORT: 'medium',
    YK_RULES: join(d, 'rules'),
    YK_SCHEMA: '{}',
    YK_CLAUDE_BIN: join(d, 'claude.ts'),
    YK_ROLE: role,
  }
  const call = (): ReturnType<typeof sh> => sh([process.execPath, join(DOGFOOD, 'src/heal/claude.ts')], { input: '{"prompt":"p"}', env })
  const r = call()
  expect(r.code, `${role}: claude failed ${r.err}`).toBe(0)
  const answer = JSON.parse(r.out).structured_output
  expect(answer).toBeTruthy()
  put(join(d, 'answer-only.json'), answer)
  expect(kit(['validate-schema', schema, join(d, 'answer-only.json')]).code, `${role}: answer not valid for ${schema}`).toBe(0)
  const argv = read(join(d, 'argv'))
  expect(argv, `${role}: role file missing from the system prompt`).toContain(`Role: ticket ${role.replaceAll('_', ' ')}`)
  expect(argv).toContain('COMMON')
  call()
  expect(read(join(d, 'argv')), `${role}: the system prompt changed between two calls (breaks the prompt cache)`).toBe(argv)
  expect(argv.split('\n')).toContain('medium')
})
test('an unknown role must fail', () => {
  const r = sh([process.execPath, join(DOGFOOD, 'src/heal/claude.ts')], {
    input: '{"prompt":"p"}',
    env: { YUKTA_FIXER_MCP: 'off', YK_MODEL: 'sonnet', YK_RULES: join(d, 'rules'), YK_SCHEMA: '{}', YK_ROLE: 'wizard' },
  })
  expect(r.code).not.toBe(0)
  void json
})

test('the schema given to claude declares no $schema (claude refuses draft 2020-12) and keeps the inlined $defs', () => {
  for (const n of ['ticket-spec', 'revision-result', 'step-result', 'review-result']) {
    const text = flatSchema(n)
    expect(text, n).not.toContain('$schema')
    expect(text, n).not.toContain('ticket-spec.schema.json#')
  }
  expect(JSON.parse(flatSchema('revision-result'))['$defs']).toBeDefined()
  for (const n of ['step-result', 'ticket-spec', 'review-result'])
    for (const k of ['allOf', 'anyOf', 'oneOf'])
      expect(JSON.parse(flatSchema(n))[k], `${n} ${k}: the API refuses it at the top level`).toBeUndefined()
})
