import { afterEach, expect, test } from 'bun:test'
import { mkdtempSync, readFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

import { pickupProfile } from '../src/pickup.ts'
import { type Rig, rig } from './helpers.ts'

let r: Rig
afterEach(async () => {
  await r?.close()
})

const pickup = async () => {
  const home = mkdtempSync(join(tmpdir(), 'yukta-pickup-'))
  r = await rig(pickupProfile({}, home).options)
  return { home }
}

const issue = { content: 'Pick up issue #7: t\nhttps://example.test/7', meta: { issue: '7', url: 'https://example.test/7', title: 't' } }
const done = (args: Record<string, unknown>) => r.client.callTool({ name: 'pickup_done', arguments: args })

test('is exclusive, has pickup_done only and names it in the instructions', async () => {
  await pickup()
  expect((await r.client.listTools()).tools.map(t => t.name)).toEqual(['pickup_done'])
  expect(r.client.getInstructions()).toContain('pickup_done')
  expect(r.client.getServerVersion()?.name).toBe('yukta-pickup')
  expect((await r.get('/v1/status')).body).toMatchObject({ exclusive: true, busy: false })
})

test('hands one issue at a time and pickup_done frees the channel and writes the ledger', async () => {
  const { home } = await pickup()
  expect((await r.post('/v1/events', issue)).status).toBe(202)
  await r.until(() => r.received.length === 1, 'the event')
  expect(r.received[0]?.params).toMatchObject({ meta: { issue: '7', url: 'https://example.test/7', title: 't' } })
  const busy = await r.post('/v1/events', issue)
  expect(busy.status).toBe(409)
  expect(busy.body.meta).toMatchObject({ issue: '7' })

  const ans = await done({ issue: 7, outcome: 'pr', url: 'https://example.test/pr/1' })
  expect(ans.isError).toBe(false)
  expect((await r.get('/v1/status')).body.busy).toBe(false)
  const line = JSON.parse(readFileSync(join(home, 'pickup.jsonl'), 'utf8').trim())
  expect(line).toMatchObject({ issue: 7, outcome: 'pr', url: 'https://example.test/pr/1' })
  expect((await r.post('/v1/events', issue)).status).toBe(202) // free again
})

test('pickup_done for another issue keeps the channel busy', async () => {
  await pickup()
  await r.post('/v1/events', issue)
  await done({ issue: 8, outcome: 'skipped' })
  expect((await r.post('/v1/events', issue)).status).toBe(409)
})

test('pickup_done rejects an outcome it does not know', async () => {
  await pickup()
  expect((await done({ issue: 7, outcome: 'maybe' })).isError).toBe(true)
  expect((await done({ outcome: 'pr' })).isError).toBe(true)
})
