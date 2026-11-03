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
let channel: ReturnType<typeof Bun.serve>
let feed: ReturnType<typeof Bun.serve>
let frames: string[] = []

const env = (type: string, tier: string, over: Record<string, unknown> = {}): any => ({
  type,
  event: { request: { id: 'r1', tier, spec: { routeName: 'ticket', risk: 'MEDIUM', prompt: 'Approve the spec?', context: { issue: '7', run_id: 'run-1' }, ...over } } },
})

beforeAll(() => {
  channel = Bun.serve({
    hostname: '127.0.0.1', port: 0,
    async fetch(req) {
      const u = new URL(req.url)
      if (u.pathname === '/v1/status') return Response.json({ busy: busyIssue !== '', meta: { issue: busyIssue } })
      posted.push({ auth: req.headers.get('authorization'), ...(await req.json()) })
      return Response.json({ event_id: 'e1' }, { status: 202 })
    },
  })
  mkdirSync(join(home, 'pickup'), { recursive: true })
  writeFileSync(join(home, 'pickup', 's1.json'), JSON.stringify({ name: 's1', port: channel.port, pid: process.pid, started: 1 }))
  writeFileSync(join(home, 'pickup-token'), 'tok')
  feed = Bun.serve({
    hostname: '127.0.0.1', port: 0, idleTimeout: 0,
    fetch: () => new Response(new ReadableStream({ start(c) { for (const f of frames) c.enqueue(new TextEncoder().encode(`data: ${f}\n\n`)); c.close() } }), { headers: { 'Content-Type': 'text/event-stream' } }),
  })
})
afterAll(() => { channel.stop(true); feed.stop(true); rmSync(home, { recursive: true, force: true }) })

test('notice: only the ticket route at the session or human tier, with an issue', () => {
  expect(bridge.notice(env('InputRequested', 'session'))).toMatchObject({ issue: '7', idempotency_key: 'input-r1-session', meta: { kind: 'input', request_id: 'r1', run: 'run-1', phase: 'session' } })
  expect(bridge.notice(env('InputEscalated', 'human'))?.content).toContain('Ask the person')
  expect(bridge.notice(env('InputRequested', 'supervisor'))).toBeUndefined()
  expect(bridge.notice(env('InputRequested', 'session', { routeName: 'other' }))).toBeUndefined()
  expect(bridge.notice(env('InputRequested', 'session', { context: {} }))).toBeUndefined()
  expect(bridge.notice(env('InputAnswered', 'session'))).toBeUndefined()
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
  await bridge.readStream(`http://127.0.0.1:${feed.port}/`, 'k', async e => { seen.push(e.type); await bridge.deliver(e) })
  expect(seen).toEqual(['InputRequested', 'InputRequested'])
  expect(posted.length).toBe(1)
})
