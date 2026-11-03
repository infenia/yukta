// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// Offline test of the key of the Claude Code session (#1924): server.properties gets an OPERATOR agent key limited to the dogfood session only when the server
// build knows `agent`, and the Yukta MCP of a pickup session uses it (YUKTA_MCP_KEY=session) while every other caller keeps the dev key.
import { afterAll, beforeAll, expect, test } from 'bun:test'
import { join } from 'node:path'
import { put, rm, tmp, withLib } from './helpers.ts'

let d: string
beforeAll(() => { d = tmp() })
afterAll(() => rm(d))
const lib = (code: string, extra: Record<string, string | undefined> = {}): string => withLib(code, { YUKTA_DEV_HOME: join(d, 'home'), YUKTA_MCP_KEY: undefined, ...extra }).out.trim()
const APIKEYS = 'core/src/main/java/com/infenia/yukta/security/ApiKeys.java'

test('an older server build: no agent key in server.properties, the session uses the dev key', () => {
  put(join(d, 'home/main', APIKEYS), 'public record KeyEntry(String name) {}')
  const props = lib('console.log(l.serverProperties())')
  expect(props).toContain('yukta.api-keys[0].name=fixer')
  expect(props).not.toContain('agent')
  expect(lib('console.log(l.mcpKey() === l.devKey())', { YUKTA_MCP_KEY: 'session' })).toBe('true')
})

test('a server build that knows agent: the session key is an OPERATOR agent key of the dogfood session; only YUKTA_MCP_KEY=session uses it', () => {
  put(join(d, 'home/main', APIKEYS), 'public record KeyEntry(String name, boolean agent) {}')
  const props = lib('console.log(l.serverProperties())')
  for (const line of ['yukta.api-keys[1].name=claude-session', 'yukta.api-keys[1].role=OPERATOR', 'yukta.api-keys[1].sessions=dogfood', 'yukta.api-keys[1].agent=true']) expect(props).toContain(line)
  expect(lib('console.log(l.mcpKey() === l.sessionKey())', { YUKTA_MCP_KEY: 'session' })).toBe('true')
  expect(lib('console.log(l.mcpKey() === l.devKey())')).toBe('true')
  expect(lib('console.log(new Set([l.devKey(), l.fixerKey(), l.sessionKey()]).size)')).toBe('3')
})
