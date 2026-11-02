import { afterEach, describe, expect, test } from 'bun:test'

import { Channel } from '../src/channel.ts'
import { type Rig, rig, sse } from './helpers.ts'

let r: Rig
afterEach(async () => {
  await r?.close()
})

const channelEvents = () => r.received.filter(n => n.method === 'notifications/claude/channel')

describe('handshake', () => {
  test('declares the channel capability, tools and instructions', async () => {
    r = await rig()
    expect(r.client.getServerCapabilities()?.experimental).toEqual({ 'claude/channel': {} })
    expect(r.client.getInstructions()).toBe('be nice')
    expect((await r.client.listTools()).tools.map(t => t.name)).toEqual(['reply', 'done'])
  })

  test('declares permission relay only when it is on, and then needs senders', async () => {
    r = await rig({ permissionRelay: true, senders: ['dev'] })
    expect(r.client.getServerCapabilities()?.experimental).toEqual({ 'claude/channel': {}, 'claude/channel/permission': {} })
    expect(() => new Channel({ name: 'x', instructions: '', permissionRelay: true })).toThrow(/sender allowlist/)
  })
})

describe('POST /v1/events', () => {
  test('needs the token', async () => {
    r = await rig()
    expect((await r.post('/v1/events', { content: 'x' }, null)).status).toBe(401)
    expect((await r.post('/v1/events', { content: 'x' }, 'wrong')).status).toBe(401)
    expect(channelEvents()).toHaveLength(0)
  })

  test('becomes one channel event with meta, event_id and sender', async () => {
    r = await rig()
    const a = await r.post('/v1/events', { content: 'build failed', meta: { severity: 'high', run_id: '1234' }, sender: 'ci' })
    expect(a.status).toBe(202)
    await r.until(() => channelEvents().length === 1, 'the event')
    expect(channelEvents()).toHaveLength(1)
    expect(channelEvents()[0]?.params).toEqual({
      content: 'build failed',
      meta: { severity: 'high', run_id: '1234', event_id: a.body.event_id, sender: 'ci' },
    })
  })

  test.each([
    ['empty content', { content: '' }],
    ['a meta key with a hyphen (Claude Code would drop it silently)', { content: 'x', meta: { 'run-id': '1' } }],
    ['a reserved meta key', { content: 'x', meta: { event_id: 'spoof' } }],
    ['a meta value that is not a string', { content: 'x', meta: { n: 1 } }],
    ['an unknown field', { content: 'x', extra: true }],
  ])('rejects %s', async (_, body) => {
    r = await rig()
    const a = await r.post('/v1/events', body)
    expect(a.status).toBe(400)
    expect(a.body.error).toBe('invalid')
    expect(channelEvents()).toHaveLength(0)
  })

  test('rejects JSON that is not JSON and a body that is too large', async () => {
    r = await rig()
    const bad = await fetch(`${r.base}/v1/events`, { method: 'POST', headers: { authorization: 'Bearer secret' }, body: '{nope' })
    expect(bad.status).toBe(400)
    const big = await r.post('/v1/events', { content: 'x'.repeat(200_000) })
    expect(big.status).toBe(413)
  })

  test('a repeated idempotency_key sends nothing', async () => {
    r = await rig()
    const a = await r.post('/v1/events', { content: 'once', idempotency_key: 'k1' })
    const b = await r.post('/v1/events', { content: 'once', idempotency_key: 'k1' })
    expect(a.status).toBe(202)
    expect(b).toEqual({ status: 200, body: { event_id: a.body.event_id, duplicate: true } })
    await r.until(() => channelEvents().length >= 1, 'the event')
    expect(channelEvents()).toHaveLength(1)
  })

  test('with a sender allowlist only listed senders get in', async () => {
    r = await rig({ senders: ['dev'] })
    expect((await r.post('/v1/events', { content: 'x' })).status).toBe(403)
    expect((await r.post('/v1/events', { content: 'x', sender: 'mallory' })).status).toBe(403)
    expect((await r.post('/v1/events', { content: 'x', sender: 'dev' })).status).toBe(202)
  })
})

describe('exclusive channel', () => {
  test('answers 409 while an event is in flight and is freed by done', async () => {
    r = await rig({ exclusive: true })
    const a = await r.post('/v1/events', { content: 'one', meta: { issue: '7' } })
    expect(a.status).toBe(202)
    const busy = await r.post('/v1/events', { content: 'two' })
    expect(busy.status).toBe(409)
    expect(busy.body).toMatchObject({ error: 'busy', event_id: a.body.event_id, meta: { issue: '7' } })
    expect((await r.get('/v1/status')).body).toMatchObject({ busy: true, event_id: a.body.event_id })

    const done = await r.client.callTool({ name: 'done', arguments: { event_id: a.body.event_id, outcome: 'ok' } })
    expect(done.isError).toBe(false)
    expect((await r.get('/v1/status')).body.busy).toBe(false)
    expect((await r.post('/v1/events', { content: 'three' })).status).toBe(202)
  })

  test('done for another event does not free it', async () => {
    r = await rig({ exclusive: true })
    await r.post('/v1/events', { content: 'one' })
    await r.client.callTool({ name: 'done', arguments: { event_id: 'other' } })
    expect((await r.post('/v1/events', { content: 'two' })).status).toBe(409)
  })

  test('frees itself after the busy limit', async () => {
    r = await rig({ exclusive: true, busyLimitMs: 30 })
    await r.post('/v1/events', { content: 'one' })
    await Bun.sleep(60)
    expect((await r.get('/v1/status')).body.busy).toBe(false)
    expect((await r.post('/v1/events', { content: 'two' })).status).toBe(202)
  })

  test('a channel that is not exclusive takes every event', async () => {
    r = await rig()
    expect((await r.post('/v1/events', { content: 'one' })).status).toBe(202)
    expect((await r.post('/v1/events', { content: 'two' })).status).toBe(202)
    expect((await r.get('/v1/status')).body.busy).toBe(false)
  })
})

describe('GET /v1/stream (outbound)', () => {
  test('carries what Claude sends back: the accepted event, a reply and done', async () => {
    r = await rig({ exclusive: true })
    const s = await sse(r.base)
    const a = await r.post('/v1/events', { content: 'ping', meta: { chat_id: 'c1' } })
    await r.client.callTool({ name: 'reply', arguments: { event_id: a.body.event_id, text: 'pong', meta: { lang: 'en' } } })
    await r.client.callTool({ name: 'done', arguments: { event_id: a.body.event_id, outcome: 'ok', note: 'n' } })
    await r.until(() => s.events.length >= 3, 'three stream events')
    s.stop()
    expect(s.events.map(e => e.event)).toEqual(['event', 'reply', 'done'])
    expect(s.events[1]?.data).toMatchObject({
      event_id: a.body.event_id,
      text: 'pong',
      meta: { lang: 'en' },
      in_reply_to: { chat_id: 'c1', event_id: a.body.event_id },
    })
    expect(s.events[2]?.data).toMatchObject({ event_id: a.body.event_id, outcome: 'ok', note: 'n' })
  })

  test('replays what a reconnecting client missed (Last-Event-ID)', async () => {
    r = await rig()
    await r.post('/v1/events', { content: 'one' })
    await r.post('/v1/events', { content: 'two' })
    const s = await sse(r.base, { 'last-event-id': '1' })
    await r.until(() => s.events.length >= 1, 'the replay')
    s.stop()
    expect(s.events.map(e => e.data.content)).toEqual(['two'])
  })

  test('needs the token', async () => {
    r = await rig()
    const res = await fetch(`${r.base}/v1/stream`)
    expect(res.status).toBe(401)
  })

  test('reply and done reject bad arguments', async () => {
    r = await rig()
    expect((await r.client.callTool({ name: 'reply', arguments: {} })).isError).toBe(true)
    expect((await r.client.callTool({ name: 'nope', arguments: {} })).isError).toBe(true)
  })
})

describe('permission relay', () => {
  const ask = () =>
    r.client.notification({
      method: 'notifications/claude/channel/permission_request',
      params: { request_id: 'abcde', tool_name: 'Bash', description: 'Run shell command', input_preview: '{"command":"ls"}' },
    })

  test('forwards the prompt to the stream and the verdict back to Claude Code', async () => {
    r = await rig({ permissionRelay: true, senders: ['dev'] })
    const s = await sse(r.base)
    await ask()
    await r.until(() => s.events.some(e => e.event === 'permission_request'), 'the prompt')
    expect((await r.get('/v1/permissions')).body.pending).toMatchObject([{ request_id: 'abcde', tool_name: 'Bash' }])

    expect((await r.post('/v1/permissions/abcde', { behavior: 'allow' })).status).toBe(403) // no sender
    expect((await r.post('/v1/permissions/abcde', { behavior: 'allow', sender: 'mallory' })).status).toBe(403)
    expect((await r.post('/v1/permissions/abcde', { behavior: 'maybe', sender: 'dev' })).status).toBe(400)
    expect((await r.post('/v1/permissions/zzzzz', { behavior: 'allow', sender: 'dev' })).status).toBe(404)

    const a = await r.post('/v1/permissions/abcde', { behavior: 'deny', sender: 'dev' })
    expect(a).toEqual({ status: 202, body: { request_id: 'abcde', behavior: 'deny' } })
    await r.until(() => r.received.some(n => n.method === 'notifications/claude/channel/permission'), 'the verdict')
    expect(r.received.find(n => n.method === 'notifications/claude/channel/permission')?.params).toEqual({ request_id: 'abcde', behavior: 'deny' })
    await r.until(() => s.events.some(e => e.event === 'permission_resolved'), 'resolved')
    s.stop()
    expect((await r.post('/v1/permissions/abcde', { behavior: 'allow', sender: 'dev' })).status).toBe(404) // answered once
  })

  test('is off by default', async () => {
    r = await rig()
    expect((await r.get('/v1/permissions')).status).toBe(404)
    expect((await r.post('/v1/permissions/abcde', { behavior: 'allow', sender: 'dev' })).status).toBe(404)
  })
})

describe('status and contract', () => {
  test('status needs no token and says whether Claude Code is connected', async () => {
    r = await rig()
    const s = await r.get('/v1/status', null)
    expect(s.status).toBe(200)
    expect(s.body).toMatchObject({ name: 'test', connected: true, exclusive: false, busy: false })
  })

  test('serves its OpenAPI document without a token', async () => {
    r = await rig({ permissionRelay: true, senders: ['dev'] })
    const d = await r.get('/v1/openapi.json', null)
    expect(d.status).toBe(200)
    expect(d.body.openapi).toBe('3.1.0')
    expect(Object.keys(d.body.paths)).toEqual(['/v1/status', '/v1/events', '/v1/stream', '/v1/permissions', '/v1/permissions/{request_id}'])
  })

  test('unknown paths are 404', async () => {
    r = await rig()
    expect((await r.get('/v1/nope')).status).toBe(404)
    expect((await r.get('/elsewhere', null)).status).toBe(404)
  })
})
