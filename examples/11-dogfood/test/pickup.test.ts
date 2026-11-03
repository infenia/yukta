// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// Offline test of the scheduled pickup (no Claude, no server, no GitHub): `pickup tick` against real channels (channel/src/main.ts, several at once, found
// through the registry) with a fake gh on PATH, the ticket mode, and busy() ignoring the `pickup` schedule.
import { afterAll, beforeAll, expect, setDefaultTimeout, test } from 'bun:test'
import { existsSync } from 'node:fs'
import { join } from 'node:path'
import { HERE, put, read, rm, script, sh, tmp, ts } from './helpers.ts'

setDefaultTimeout(120_000)
let d: string
const procs = new Map<string, ReturnType<typeof Bun.spawn>>()
const channelsPresent = (): boolean => existsSync(join(HERE, 'channel/node_modules'))

beforeAll(() => {
  d = tmp()
  // fake gh: answers from files in $FAKE_GH, the way gh prints JSON
  script(join(d, 'bin/gh'), `import { existsSync, readFileSync } from 'node:fs'
const a = process.argv.slice(2); const dir = process.env.FAKE_GH!
const cat = (f: string): void => void process.stdout.write(readFileSync(dir + '/' + f, 'utf8'))
const k = a[0] + ' ' + a[1]
if (k === 'issue list') cat('mine.json')
else if (k === 'project item-list') cat('ready.json')
else if (k === 'pr list') cat('prs.json')
else if (a[0] === 'api') { const f = 'refs-' + a[1]!.split('/').pop() + '.json'; if (existsSync(dir + '/' + f)) cat(f); else console.log('[]') }
else { console.error('fake gh: unexpected ' + a.join(' ')); process.exit(9) }`)
  Object.assign(process.env, { PATH: `${d}/bin:${process.env.PATH}`, FAKE_GH: join(d, 'gh'), YUKTA_DEV_HOME: join(d, 'home') })
  put(join(d, 'home/pickup-token'), 'secret\n'); put(join(d, 'gh/prs.json'), '[]')
  put(join(d, 'gh/mine.json'), [{ number: 11, title: 'low', url: 'u11' }, { number: 12, title: 'top', url: 'u12' }, { number: 13, title: 'unready', url: 'u13' }, { number: 14, title: 'later', url: 'u14' }])
})
afterAll(() => { for (const p of procs.values()) p.kill(); rm(d) })

const tick = (env: Record<string, string | undefined> = {}) => ts('src/pickup.ts', ['tick'], { env })
// the real project has about 1200 items: pad every answer
const ready = (items: object[]): void => put(join(d, 'gh/ready.json'), { items: [...items, ...Array.from({ length: 5000 }, (_, i) => ({ status: 'Done', priority: 'P3', title: 'x'.repeat(60), content: { number: 100000 + i } }))] })

async function startChannel(name: string): Promise<void> {
  const p = Bun.spawn([process.execPath, 'run', join(HERE, 'channel/src/main.ts')], { stdin: 'pipe', stdout: Bun.file(join(d, `out-${name}`)), stderr: Bun.file(join(d, `err-${name}`)), env: { ...process.env, YUKTA_CHANNEL_PROFILE: 'pickup', YUKTA_PICKUP_NAME: name } })
  procs.set(name, p)
  for (let i = 0; i < 150 && !existsSync(join(d, `home/pickup/${name}.json`)); i++) await Bun.sleep(100)
  expect(existsSync(join(d, `home/pickup/${name}.json`)), `channel ${name} did not register: ${read(join(d, `err-${name}`))}`).toBe(true)
}
const doneFor = async (name: string, issue: number, id: number): Promise<void> => {
  const stdin = procs.get(name)!.stdin as { write(s: string): void; flush(): void }
  stdin.write(JSON.stringify({ jsonrpc: '2.0', id, method: 'tools/call', params: { name: 'pickup_done', arguments: { issue, outcome: 'pr' } } }) + '\n')
  stdin.flush()
  await Bun.sleep(400)
}

test.skipIf(!channelsPresent())('no channel, a dead pid, two channels, priorities, busy sessions, PRs and branches, pinning, killed and closed channels, a wrong token', async () => {
  // 1. no channel (an empty registry, and one whose only entry is a dead pid: dropped)
  let o = tick()
  expect(o.code, o.all).toBe(0); expect(o.out).toStartWith('PICKUP skipped=no channel')
  put(join(d, 'home/pickup/ghost.json'), '{"name":"ghost","port":1,"pid":999999,"started":1}')
  expect(tick().out).toStartWith('PICKUP skipped=no channel')
  expect(existsSync(join(d, 'home/pickup/ghost.json')), 'the entry of a dead pid must be deleted').toBe(false)
  await startChannel('a'); await Bun.sleep(200); await startChannel('b')
  // 2. nothing Ready
  ready([{ status: 'Backlog', priority: 'P0', content: { number: 12 } }])
  expect(tick().out).toStartWith('PICKUP skipped=nothing assigned')
  // 3. two Ready issues, two idle sessions: the higher Priority to the oldest session, the next to the other; 13 (not Ready) never
  ready([{ status: 'Ready', priority: 'P2', content: { number: 11 } }, { status: 'Ready', priority: 'P1', content: { number: 12 } }, { status: 'In progress', priority: 'P0', content: { number: 13 } }])
  expect(tick().out.trim()).toBe('PICKUP handed=#12 to=a\nPICKUP handed=#11 to=b')
  for (let i = 0; i < 30 && !(read(join(d, 'out-a')) && read(join(d, 'out-b'))); i++) await Bun.sleep(100)
  expect(JSON.parse(read(join(d, 'out-a')).split('\n')[0]!)).toMatchObject({ method: 'notifications/claude/channel', params: { meta: { issue: '12' } } })
  expect(JSON.parse(read(join(d, 'out-b')).split('\n')[0]!).params.meta.issue).toBe('11')
  // 4. both busy
  ready([{ status: 'In progress', priority: 'P1', content: { number: 12 } }, { status: 'In progress', priority: 'P2', content: { number: 11 } }, { status: 'Ready', priority: 'P3', content: { number: 14 } }])
  expect(tick().out.trim()).toBe('PICKUP skipped=all busy (a,b)')
  // 5. a reports back: the next Ready issue goes to a, b stays busy
  await doneFor('a', 12, 2)
  expect(tick().out.trim()).toBe('PICKUP handed=#14 to=a')
  await doneFor('a', 14, 3)
  // 6. a Ready issue with an open PR (by branch, by "Fixes #n", but not "#140") or an issue/<n> branch is skipped
  ready([{ status: 'Ready', priority: 'P1', content: { number: 12 } }])
  put(join(d, 'gh/prs.json'), [{ headRefName: 'issue/12', title: 'x', body: '' }])
  expect(tick().out, 'PR by branch').toStartWith('PICKUP skipped=nothing assigned')
  put(join(d, 'gh/prs.json'), [{ headRefName: 'feat/a', title: 'feat', body: 'Fixes #12\n' }])
  expect(tick().out, 'PR by body').toStartWith('PICKUP skipped=nothing assigned')
  put(join(d, 'gh/prs.json'), []); put(join(d, 'gh/refs-12.json'), [{ ref: 'refs/heads/issue/12' }])
  expect(tick().out, 'branch on the remote').toStartWith('PICKUP skipped=nothing assigned')
  rm(join(d, 'gh/refs-12.json')); put(join(d, 'gh/prs.json'), [{ headRefName: 'feat/a', title: 'feat', body: 'Refs #120' }])
  expect(tick().out.trim(), '#120 must not match #12').toBe('PICKUP handed=#12 to=a')
  await doneFor('a', 12, 4); put(join(d, 'gh/prs.json'), [])
  // 7. YUKTA_PICKUP_ONLY pins one session: b is busy, so nothing goes anywhere
  expect(tick({ YUKTA_PICKUP_ONLY: 'b' }).out.trim()).toBe('PICKUP skipped=all busy (b)')
  expect(tick({ YUKTA_PICKUP_ONLY: 'zzz' }).out).toStartWith('PICKUP skipped=no channel named zzz')
  await doneFor('b', 11, 5)
  expect(tick({ YUKTA_PICKUP_ONLY: 'b' }).out.trim()).toBe('PICKUP handed=#12 to=b')
  await doneFor('b', 12, 6)
  // 8. a killed channel (no exit hook) is dropped and its file deleted; a closed one removes itself
  procs.get('a')!.kill(9); await procs.get('a')!.exited
  expect(tick().out.trim()).toBe('PICKUP handed=#12 to=b')
  expect(existsSync(join(d, 'home/pickup/a.json')), 'the entry of the killed channel must be deleted').toBe(false)
  ;(procs.get('b')!.stdin as { end(): void }).end()
  for (let i = 0; i < 50 && existsSync(join(d, 'home/pickup/b.json')); i++) await Bun.sleep(100)
  expect(existsSync(join(d, 'home/pickup/b.json')), 'a closed session must remove its entry').toBe(false)
  expect(tick().out).toStartWith('PICKUP skipped=no channel')
  // 9. a wrong token is an error, not a skip (same registry, another token)
  await startChannel('c')
  put(join(d, 'home2/pickup-token'), 'wrong\n')
  sh(['ln', '-sfn', join(d, 'home/pickup'), join(d, 'home2/pickup')])
  o = tick({ YUKTA_DEV_HOME: join(d, 'home2') })
  expect(o.code, 'a refused POST must fail').not.toBe(0)
  expect(o.all).toContain('answered 401')
})

test('ticket mode: the top Ready issue starts `yk ticket run` (a fake yk), no channel needed', () => {
  rm(join(d, 'home/pickup'))
  put(join(d, 'gh/mine.json'), [{ number: 11, title: 'low', url: 'u11' }, { number: 12, title: 'top', url: 'u12' }])
  put(join(d, 'gh/ready.json'), { items: [{ status: 'Ready', priority: 'P2', content: { number: 11 } }, { status: 'Ready', priority: 'P0', content: { number: 12 } }] })
  const yk = script(join(d, 'yk'), `import { appendFileSync } from 'node:fs'\nappendFileSync('${d}/yk.log', process.argv.slice(2).join(' ') + '\\n')`)
  const o = tick({ YUKTA_PICKUP_MODE: 'ticket', YUKTA_PICKUP_YK: yk })
  expect(o.code, o.all).toBe(0)
  expect(o.out.trim(), 'the top priority issue is handed').toBe('PICKUP handed=#12 to=ticket')
  expect(read(join(d, 'yk.log')).trim()).toBe('ticket run 12 --detach')
  put(join(d, 'home/pickup-mode'), 'ticket\n'); rm(join(d, 'yk.log'))
  expect(tick({ YUKTA_PICKUP_YK: yk }).out.trim(), 'the pickup-mode file switches too').toBe('PICKUP handed=#12 to=ticket')
  rm(join(d, 'home/pickup-mode'))
  expect(tick({ YUKTA_PICKUP_YK: yk }).out, 'default mode stays sessions').toStartWith('PICKUP skipped=no channel')
})

test('busy() does not count the pickup schedule (and counts anything else)', async () => {
  const serve = (rows: object[]): { port: number; stop: () => void } => { const s = Bun.serve({ hostname: '127.0.0.1', port: 0, fetch: () => Response.json({ data: rows }) }); return { port: s.port!, stop: () => void s.stop(true) } }
  const busy = async (port: number): Promise<string> => {
    const p = Bun.spawn([process.execPath, '-e', `import { busy } from '${HERE}/src/lib.ts'; console.log(await busy())`], { stdout: 'pipe', env: { ...process.env, YUKTA_DEV_PORT: String(port), YUKTA_DEV_HOME: join(d, 'home'), YUKTA_TOKEN: 'k' } })
    return (await new Response(p.stdout).text()).trim()
  }
  let s = serve([{ workflowId: 'pickup', status: 'RUNNING', executionId: 'p' }, { workflowId: 'tool', status: 'SUCCESS', executionId: 'a' }])
  expect(await busy(s.port), 'only pickup running: busy must be 0').toBe('0')
  s.stop()
  s = serve([{ workflowId: 'pickup', status: 'RUNNING', executionId: 'p' }, { workflowId: 'check', status: 'RUNNING', executionId: 'c' }])
  expect(await busy(s.port), 'a running check must count').toBe('1')
  s.stop()
})
