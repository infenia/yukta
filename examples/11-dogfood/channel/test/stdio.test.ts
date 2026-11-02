// The real thing: `bun src/main.ts` as Claude Code runs it (stdio MCP, HTTP on a free port, a registry entry), driven by an MCP client.
import { afterEach, expect, test } from 'bun:test'
import { existsSync, mkdtempSync, readdirSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js'
import type { Notification, Request, Result } from '@modelcontextprotocol/sdk/types.js'

const main = join(import.meta.dir, '../src/main.ts')
const clients: Client[] = []
afterEach(async () => {
  for (const c of clients.splice(0)) await c.close()
})

async function start(home: string, env: Record<string, string>) {
  const transport = new StdioClientTransport({
    command: process.execPath,
    args: ['run', main],
    env: { PATH: process.env.PATH ?? '', YUKTA_DEV_HOME: home, ...env },
    stderr: 'pipe',
  })
  const client = new Client<Request, Notification, Result>({ name: 'claude-code', version: '0' }, { capabilities: {} })
  const received: Notification[] = []
  client.fallbackNotificationHandler = async n => {
    received.push(n)
  }
  await client.connect(transport)
  clients.push(client)
  return { client, received, transport }
}

const until = async (cond: () => boolean, what: string) => {
  for (let i = 0; i < 150 && !cond(); i++) await Bun.sleep(20)
  if (!cond()) throw new Error(`timed out waiting for ${what}`)
}

test('generic channel: handshake, token, event, reply, registry, and the entry goes with the session', async () => {
  const home = mkdtempSync(join(tmpdir(), 'yukta-stdio-'))
  const { client, received } = await start(home, { YUKTA_CHANNEL_NAME: 'hook' })
  expect(client.getServerCapabilities()?.experimental).toEqual({ 'claude/channel': {} })
  expect(client.getServerVersion()?.name).toBe('hook')

  const regDir = join(home, 'channels')
  await until(() => existsSync(join(regDir, 'hook.json')), 'the registry entry')
  const { port, pid } = JSON.parse(readFileSync(join(regDir, 'hook.json'), 'utf8')) as { port: number; pid: number }
  const token = readFileSync(join(home, 'channel-token'), 'utf8') // created at start
  expect(token.length).toBeGreaterThan(20)

  const url = `http://127.0.0.1:${port}`
  expect((await fetch(`${url}/v1/events`, { method: 'POST', body: '{"content":"x"}' })).status).toBe(401)
  const res = await fetch(`${url}/v1/events`, {
    method: 'POST',
    headers: { authorization: `Bearer ${token}` },
    body: JSON.stringify({ content: 'hello', meta: { k: 'v' } }),
  })
  expect(res.status).toBe(202)
  await until(() => received.length === 1, 'the event')
  expect(received[0]?.method).toBe('notifications/claude/channel')
  expect(received[0]?.params).toMatchObject({ content: 'hello', meta: { k: 'v' } })

  // closing the pipe is the end of the session: the process exits and removes its entry
  await client.close()
  await until(() => {
    try {
      process.kill(pid, 0)
      return false
    } catch {
      return true
    }
  }, 'the channel to exit')
  expect(readdirSync(regDir)).toEqual([])
})

test('pickup profile: name, token file, registry and port from the old environment variables', async () => {
  const home = mkdtempSync(join(tmpdir(), 'yukta-stdio-'))
  writeFileSync(join(home, 'pickup-token'), 'secret')
  const { client } = await start(home, { YUKTA_CHANNEL_PROFILE: 'pickup', YUKTA_PICKUP_NAME: 'one' })
  expect(client.getServerVersion()?.name).toBe('yukta-pickup')
  await until(() => existsSync(join(home, 'pickup', 'one.json')), 'the registry entry')
  const { port } = JSON.parse(readFileSync(join(home, 'pickup', 'one.json'), 'utf8')) as { port: number }
  const res = await fetch(`http://127.0.0.1:${port}/v1/events`, {
    method: 'POST',
    headers: { authorization: 'Bearer secret' },
    body: JSON.stringify({ content: 'Pick up issue #1: t', meta: { issue: '1' } }),
  })
  expect(res.status).toBe(202)
  expect((await client.callTool({ name: 'pickup_done', arguments: { issue: 1, outcome: 'skipped' } })).isError).toBe(false)
})
