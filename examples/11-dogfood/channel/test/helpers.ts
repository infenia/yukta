// A channel wired to an in-memory MCP client (standing in for Claude Code) and a real HTTP server on a free port.
import { mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { InMemoryTransport } from '@modelcontextprotocol/sdk/inMemory.js'
import type { Notification, Request, Result } from '@modelcontextprotocol/sdk/types.js'

import { Channel, type ChannelOptions } from '../src/channel.ts'
import { serve } from '../src/http.ts'

export const TOKEN = 'secret'

export type Rig = {
  channel: Channel
  client: Client<Request, Notification, Result>
  base: string
  dir: string
  received: Notification[]
  post: (path: string, body: unknown, token?: string | null) => Promise<{ status: number; body: any }>
  get: (path: string, token?: string | null) => Promise<{ status: number; body: any }>
  until: (cond: () => boolean, what: string) => Promise<void>
  close: () => Promise<void>
}

export async function rig(opts: Partial<ChannelOptions> = {}): Promise<Rig> {
  const dir = mkdtempSync(join(tmpdir(), 'yukta-channel-'))
  const tokenFile = join(dir, 'token')
  writeFileSync(tokenFile, TOKEN)
  const channel = new Channel({ name: 'test', instructions: 'be nice', log: () => {}, ...opts })
  const client = new Client<Request, Notification, Result>({ name: 'claude-code', version: '0' }, { capabilities: {} })
  const received: Notification[] = []
  client.fallbackNotificationHandler = async n => {
    received.push(n)
  }
  const [a, b] = InMemoryTransport.createLinkedPair()
  await Promise.all([channel.connect(a), client.connect(b)])
  const http = serve(channel, { port: 0, tokenFile })
  const base = `http://127.0.0.1:${http.port}`
  const call = async (method: string, path: string, body?: unknown, token: string | null = TOKEN) => {
    const res = await fetch(base + path, {
      method,
      headers: { ...(token === null ? {} : { authorization: `Bearer ${token}` }), 'content-type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
    return { status: res.status, body: await res.json() }
  }
  return {
    channel,
    client,
    base,
    dir,
    received,
    post: (path, body, token = TOKEN) => call('POST', path, body, token),
    get: (path, token = TOKEN) => call('GET', path, undefined, token),
    async until(cond, what) {
      for (let i = 0; i < 100 && !cond(); i++) await Bun.sleep(20)
      if (!cond()) throw new Error(`timed out waiting for ${what}`)
    },
    async close() {
      await http.stop(true)
      await client.close()
      await channel.close()
    },
  }
}

// Reads server-sent events from /v1/stream as {id, event, data}.
export async function sse(base: string, headers: Record<string, string> = {}) {
  const ctl = new AbortController()
  const res = await fetch(`${base}/v1/stream`, { headers: { authorization: `Bearer ${TOKEN}`, ...headers }, signal: ctl.signal })
  const reader = res.body!.getReader()
  const dec = new TextDecoder()
  let buf = ''
  const events: { id: string; event: string; data: any }[] = []
  void (async () => {
    try {
      for (;;) {
        const { value, done } = await reader.read()
        if (done) return
        buf += dec.decode(value)
        let i: number
        while ((i = buf.indexOf('\n\n')) >= 0) {
          const block = buf.slice(0, i)
          buf = buf.slice(i + 2)
          const f = Object.fromEntries(block.split('\n').filter(l => !l.startsWith(':')).map(l => [l.slice(0, l.indexOf(':')), l.slice(l.indexOf(':') + 2)]))
          if (f.event) events.push({ id: f.id as string, event: f.event, data: JSON.parse(f.data as string) })
        }
      }
    } catch {
      // aborted
    }
  })()
  return { res, events, stop: () => ctl.abort() }
}
