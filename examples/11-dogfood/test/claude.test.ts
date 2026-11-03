// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// Offline test of the headless claude launcher against a fake claude that records its arguments: the MCP access of the fixers (none without a key, read
// tools only with one, the key never in an argument, YUKTA_FIXER_MCP=off) and the role of the system prompt (common rules plus fixer-rules/<kind>.md).
import { afterAll, beforeAll, expect, setDefaultTimeout, test } from 'bun:test'
import { join } from 'node:path'
import { ts, put, read, rm, script, tmp } from './helpers.ts'

setDefaultTimeout(120_000)
let d: string
beforeAll(() => {
  d = tmp()
  script(join(d, 'claude'), `import { writeFileSync } from 'node:fs'\nwriteFileSync('${d}/argv', process.argv.slice(2).join('\\n') + '\\n')\nwriteFileSync('${d}/key', (process.env.YK_MCP_KEY ?? '') + '\\n')\nconsole.log('{"is_error":false,"structured_output":{"fixed":false,"summary":"x"}}')`)
  put(join(d, 'rules'), 'rules\n')
})
afterAll(() => rm(d))
const call = (env: Record<string, string | undefined> = {}) => {
  rm(join(d, 'argv')); rm(join(d, 'key'))
  const o = ts('src/claude.ts', [], {
    input: '{"prompt":"fix it"}',
    env: { YUKTA_DEV_HOME: join(d, 'home'), YK_MODEL: 'haiku', YK_RULES: join(d, 'rules'), YK_SCHEMA: '{}', YK_CLAUDE_BIN: join(d, 'claude'), YUKTA_FIXER_MCP: undefined, YUKTA_FIXER_MCP_KEY: undefined, YUKTA_SERVER_URL: undefined, YK_MCP_URL: undefined, YK_MCP_KEY: undefined, ...env },
  })
  expect(o.code, o.err).toBe(0)
  return read(join(d, 'argv'))
}

test('MCP access: none without a key, read tools only with one, the key by environment', () => {
  let argv = call()
  expect(argv).toContain('--strict-mcp-config'); expect(argv).not.toContain('--mcp-config')
  put(join(d, 'home/fixer-key'), 'secretkey\n')
  argv = call()
  expect(argv, 'no --mcp-config with the dev fixer key').toContain('--mcp-config')
  expect(argv).toContain('mcp__yukta__get_node_output'); expect(argv).toContain('mcp__yukta__get_workflow_history')
  for (const t of ['start_workflow', 'control_workflow', 'control_node', 'signal_node', 'create_session']) expect(argv.split('\n'), `${t} is not denied`).toContain(`mcp__yukta__${t}`)
  const allowed = argv.split('\n').slice(0, argv.split('\n').indexOf('--disallowedTools')).filter(l => l.startsWith('mcp__yukta__'))
  expect(allowed.every(l => l.startsWith('mcp__yukta__get_')), 'a non-read MCP tool is allowed').toBe(true)
  expect(argv).not.toContain('secretkey')
  expect(read(join(d, 'key')).trim(), 'the key reaches claude by environment').toBe('secretkey')
  expect(argv).toContain('"X-API-Key":"${YK_MCP_KEY}"')
  expect(call({ YUKTA_FIXER_MCP: 'off' }), 'YUKTA_FIXER_MCP=off still configures MCP').not.toContain('--mcp-config')
  expect(call({ YUKTA_SERVER_URL: 'http://other:1' }), 'the dev fixer key was sent to another server').not.toContain('--mcp-config')
  expect(call({ YUKTA_SERVER_URL: 'http://other:1', YUKTA_FIXER_MCP_KEY: 'k2' })).toContain('http://other:1/mcp')
})

test.each([
  ['style', 'static-analysis'], ['spotbugs', 'SpotBugs'], ['tests', 'Java test'], ['coverage', 'coverage'], ['compile', 'compile'], ['ui_vitest', 'UI test'], ['ui_tsc', 'UI static-check'], ['cli_vet', 'Go'],
  ['reuse', '-'], ['spotless', '-'],
])('the fixer role of stage %s', (stage, role) => {
  const argv = call({ YUKTA_FIXER_MCP: 'off', YK_RULES: join(d, 'rules'), YK_STAGE: stage })
  expect(argv, `${stage}: common rules missing`).toContain('rules')
  if (role === '-') expect(argv, `${stage}: got a role`).not.toMatch(/^Role:/m)
  else expect(argv, `${stage}: expected role '${role}'`).toMatch(new RegExp(`^Role: ${role}`, 'm'))
})
