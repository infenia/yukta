// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// Offline test of input-bridge.ts (#1920): which envelopes become a notice, who owns the run, and the stream of a fake server reaching a fake channel.
import { afterAll, beforeAll, expect, test } from 'bun:test'
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const home = mkdtempSync(join(tmpdir(), 'bridge-'))
process.env.YUKTA_DEV_HOME = home
process.env.YUKTA_DEV_STATE_DIR = join(home, 'state')
const bridge = await import('../src/input-bridge.ts')

const posted: any[] = []
let busyIssue = '7'
let failPosts = 0
let channel: ReturnType<typeof Bun.serve>
let feed: ReturnType<typeof Bun.serve>
let frames: string[] = []

const env = (type: string, tier: string, over: Record<string, unknown> = {}): any => ({
  type,
  event: {
    request: {
      id: 'r1',
      sessionId: 's-1',
      executionId: 'x-1',
      tier,
      spec: { routeName: 'ticket', risk: 'MEDIUM', prompt: 'Approve the spec?', context: { issue: '7', run_id: 'run-1' }, ...over },
    },
  },
})

beforeAll(() => {
  channel = Bun.serve({
    hostname: '127.0.0.1',
    port: 0,
    async fetch(req) {
      const u = new URL(req.url)
      if (u.pathname === '/v1/status') return Response.json({ busy: busyIssue !== '', meta: { issue: busyIssue } })
      if (failPosts > 0 && req.method === 'POST') {
        failPosts--
        return Response.json({ error: 'x' }, { status: 503 })
      }
      posted.push({ auth: req.headers.get('authorization'), ...((await req.json()) as object) })
      return Response.json({ event_id: 'e1' }, { status: 202 })
    },
  })
  mkdirSync(join(home, 'pickup'), { recursive: true })
  writeFileSync(join(home, 'pickup', 's1.json'), JSON.stringify({ name: 's1', port: channel.port, pid: process.pid, started: 1 }))
  writeFileSync(join(home, 'pickup-token'), 'tok')
  feed = Bun.serve({
    hostname: '127.0.0.1',
    port: 0,
    idleTimeout: 0,
    fetch: () =>
      new Response(
        new ReadableStream({
          start(c) {
            for (const f of frames) c.enqueue(new TextEncoder().encode(`data: ${f}\n\n`))
            c.close()
          },
        }),
        { headers: { 'Content-Type': 'text/event-stream' } },
      ),
  })
})
afterAll(() => {
  channel.stop(true)
  feed.stop(true)
  rmSync(home, { recursive: true, force: true })
})

test('notice: only the ticket route at the session or human tier, with an issue', () => {
  expect(bridge.notice(env('InputRequested', 'session'))).toMatchObject({
    issue: '7',
    idempotency_key: 'input-r1-session',
    meta: { kind: 'input', request_id: 'r1', session_id: 's-1', execution_id: 'x-1', run: 'run-1', phase: 'session' },
  })
  expect(bridge.notice(env('InputEscalated', 'human'))?.content).toContain('Ask the person')
  expect(bridge.notice(env('InputRequested', 'supervisor'))).toBeUndefined()
  expect(bridge.notice(env('InputRequested', 'session', { routeName: 'other' }))).toBeUndefined()
  expect(bridge.notice(env('InputRequested', 'session', { context: {} }))).toBeUndefined()
  expect(bridge.notice(env('InputAnswered', 'session'))).toBeUndefined()
})

test('deliver: one transient failure is retried, a second one is reported as failed', async () => {
  const before = posted.length
  failPosts = 1
  expect(await bridge.deliver(env('InputRequested', 'session'), 1)).toBe('sent')
  expect(posted.length).toBe(before + 1)
  failPosts = 2
  expect(await bridge.deliver(env('InputRequested', 'session'), 1)).toBe('failed')
  failPosts = 0
})

test('the prompt is cut to 1 KB', () => {
  expect(bridge.notice(env('InputRequested', 'session', { prompt: 'x'.repeat(5000) }))!.content.length).toBeLessThan(1400)
})

test('deliver: to the session busy with the issue, with the token; none for another issue or an idle session', async () => {
  posted.length = 0
  expect(await bridge.deliver(env('InputRequested', 'session'))).toBe('sent')
  expect(posted[0]).toMatchObject({ auth: 'Bearer tok', idempotency_key: 'input-r1-session', meta: { kind: 'input', issue: '7' } })
  expect(await bridge.deliver(env('InputRequested', 'session', { context: { issue: '8' } }))).toBe('no-owner')
  busyIssue = ''
  expect(await bridge.deliver(env('InputRequested', 'session'))).toBe('no-owner')
  busyIssue = '7'
  expect(await bridge.deliver(env('InputRequested', 'supervisor'))).toBe('skipped')
  expect(posted.length).toBe(1)
})

test('the stream: frames are read, only the matching one reaches the channel', async () => {
  posted.length = 0
  frames = [JSON.stringify(env('InputRequested', 'supervisor')), JSON.stringify(env('InputRequested', 'session')), 'not json']
  const seen: string[] = []
  await bridge.readStream(`http://127.0.0.1:${feed.port}/`, 'k', async (e) => {
    seen.push(e.type)
    await bridge.deliver(e)
  })
  expect(seen).toEqual(['InputRequested', 'InputRequested'])
  expect(posted.length).toBe(1)
})

test('pickup: the event for a ticket-pipeline session says so and carries the issue the bridge matches', async () => {
  const { eventFor } = await import('../src/pickup.ts')
  expect(eventFor(7, 'feat: x', 'u', 'ticket')).toMatchObject({
    meta: { issue: '7', via: 'ticket' },
    content: expect.stringContaining('yk ticket run 7 --detach --notify'),
  })
  const direct = eventFor(7, 'feat: x', 'u', 'direct')
  expect(direct.meta).toEqual({ issue: '7', url: 'u', title: 'feat: x' })
  expect(direct.content).toStartWith('Pick up issue #7')
})

test('a channel whose process is gone is not an owner; a busy session of another issue neither', async () => {
  writeFileSync(join(home, 'pickup', 'ghost.json'), JSON.stringify({ name: 'ghost', port: channel.port, pid: 2 ** 22 + 12345, started: 0 }))
  posted.length = 0
  busyIssue = '9'
  expect(await bridge.deliver(env('InputRequested', 'session'))).toBe('no-owner')
  busyIssue = '7'
  expect(await bridge.deliver(env('InputRequested', 'session'))).toBe('sent')
  expect(posted.length).toBe(1)
})

test('watch: a stream that ends is reopened, every frame of the second connection is delivered, an abort ends the loop', async () => {
  posted.length = 0
  frames = [JSON.stringify(env('InputRequested', 'session'))]
  const ac = new AbortController()
  const logs: string[] = []
  const done = bridge.watch(ac.signal, `http://127.0.0.1:${feed.port}`, (m) => logs.push(m), 20)
  for (let i = 0; i < 100 && posted.length < 2; i++) await Bun.sleep(50)
  ac.abort()
  await done
  expect(posted.length, 'the feed ended after one frame and was opened again').toBeGreaterThanOrEqual(2)
  expect(logs.some((l) => l.includes('BRIDGE InputRequested r1 -> sent'))).toBe(true)
})

test('pickup: the default event makes the session an orchestrator (ticket pipeline), not the implementer', async () => {
  const { eventFor } = await import('../src/pickup.ts')
  const e = eventFor(7, 'feat: x', 'u')
  expect(e.meta.via).toBe('ticket')
  expect(e.content).toContain('pickup skill')
  expect(e.content).toContain('Do NOT')
})
