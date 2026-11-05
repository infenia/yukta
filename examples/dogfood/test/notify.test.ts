// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// Offline test of yk --notify (no Claude, no server, no cost): notifySession writes the auth line and the user line to a Unix socket, does nothing without
// a socket or with YUKTA_NOTIFY=off, never fails; notifyText builds the message of a finished run; the flags are checked before anything starts.
import { afterAll, beforeAll, expect, setDefaultTimeout, test } from 'bun:test'
import { createServer, type Server } from 'node:net'
import { join } from 'node:path'
import { notifySession, notifyText } from '../src/lib.ts'
import { rm, tmp, ts } from './helpers.ts'

setDefaultTimeout(60_000)
let d: string,
  server: Server,
  got = ''
beforeAll(async () => {
  d = tmp()
  server = createServer((c) =>
    c.on('data', (b) => {
      got += b.toString()
    }),
  )
  await new Promise<void>((r) => server.listen(join(d, 's.sock'), r))
  delete process.env.CLAUDE_CODE_MESSAGING_SOCKET
  delete process.env.CLAUDE_CODE_MESSAGING_TOKEN
})
afterAll(() => {
  server.close()
  rm(d)
})
const waitLines = async (n: number): Promise<string[]> => {
  for (let i = 0; i < 50 && got.split('\n').filter(Boolean).length < n; i++) await Bun.sleep(100)
  return got.split('\n').filter(Boolean)
}

test('the auth line, then the user line with the text', async () => {
  got = ''
  process.env.CLAUDE_CODE_MESSAGING_SOCKET = join(d, 's.sock')
  process.env.CLAUDE_CODE_MESSAGING_TOKEN = 'tok123'
  await notifySession('[yk] hello\nsecond line "quoted"')
  const ls = await waitLines(2)
  expect(JSON.parse(ls[0]!)).toEqual({ type: 'auth', token: 'tok123' })
  expect(JSON.parse(ls[1]!)).toEqual({ type: 'user', message: { role: 'user', content: '[yk] hello\nsecond line "quoted"' } })
})
test('no token: just the user line; long text is cut at 8000 characters', async () => {
  got = ''
  delete process.env.CLAUDE_CODE_MESSAGING_TOKEN
  await notifySession('x'.repeat(20000))
  const ls = await waitLines(1)
  expect(ls).toHaveLength(1)
  expect(JSON.parse(ls[0]!).message.content).toHaveLength(8000)
})
test('silent and successful: no socket, YUKTA_NOTIFY=off, a socket nobody listens on', async () => {
  got = ''
  delete process.env.CLAUDE_CODE_MESSAGING_SOCKET
  await notifySession('x')
  process.env.CLAUDE_CODE_MESSAGING_SOCKET = join(d, 's.sock')
  process.env.YUKTA_NOTIFY = 'off'
  await notifySession('x')
  await Bun.sleep(300)
  expect(got, 'off must send nothing').toBe('')
  delete process.env.YUKTA_NOTIFY
  process.env.CLAUDE_CODE_MESSAGING_SOCKET = join(d, 'nobody.sock')
  await notifySession('x') // must not throw
  delete process.env.CLAUDE_CODE_MESSAGING_SOCKET
})
test('the message of a finished run', () => {
  const log =
    'WARN fast: something harmless\nERROR style/pmd: core/src/A.java:12 UnusedLocalVariable\nOUTPUT tests: 3 tests failed\nMORE yk detail dogfood e1\nRESULT status=FAILED failed_node=style healed=- execution=e1 session=dogfood wall=3 cost=$0 bytes=10\n'
  const txt = notifyText('dogfood', 'e1', log)
  expect(txt.startsWith('[yk] run finished: RESULT status=FAILED failed_node=style')).toBe(true)
  expect(txt).toContain('ERROR style/pmd: core/src/A.java:12 UnusedLocalVariable')
  expect(txt).toContain('OUTPUT tests: 3 tests failed')
  expect(txt).not.toContain('harmless')
  expect(txt.trimEnd().endsWith('More: yk detail dogfood e1')).toBe(true)
  expect(notifyText('dogfood', 'e2', 'ERROR x: boom\n')).toStartWith('[yk] run e2 ended without a RESULT line')
})
test('the flags: --notify needs --detach, checked before any server is touched', () => {
  for (const args of [
    ['gradle', '--notify', ':core:compileJava'],
    ['tool', '--notify', 'true'],
    ['check', '--notify'],
  ]) {
    const o = ts('yk', args, { env: { YUKTA_SERVER_URL: 'http://127.0.0.1:1' } })
    expect(o.code, `yk ${args.join(' ')} must be refused`).not.toBe(0)
    expect(o.all).toContain('--notify needs --detach')
  }
})
