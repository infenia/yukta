// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// Online test of how yk runs end and report (needs the dev server: yk up, and YK_ONLINE=1; no Claude, no cost, about 30 s):
//   interrupt   a foreground yk stops its execution when it is stopped (SIGTERM, as Claude Code does to a background task)
//   detach      a detached run outlives its client
//   stages      yk watch --stages prints a "STAGE <name> finished" line
import { expect, setDefaultTimeout, test } from 'bun:test'
import { join } from 'node:path'
import { HERE, ts } from './helpers.ts'

const ONLINE = Boolean(process.env.YK_ONLINE)
setDefaultTimeout(300_000)
const yk = (...a: string[]) => ts('yk', a)
const execOf = (s: string): string => /STARTED execution=(\S+)/.exec(s)?.[1] ?? ''
const state = (e: string): string => yk('tree', e).out.split('\n')[0]!

test.skipIf(!ONLINE)('interrupt: SIGTERM to the foreground client ends the run', async () => {
  const p = Bun.spawn([process.execPath, join(HERE, 'yk'), 'tool', 'sleep', '120'], { stdout: 'pipe', stderr: 'pipe' })
  let out = ''
  void (async () => {
    for await (const c of p.stdout) out += new TextDecoder().decode(c)
  })()
  for (let i = 0; i < 300 && !out.includes('STARTED'); i++) await Bun.sleep(100)
  const e = execOf(out)
  expect(e, 'the run did not start').toBeTruthy()
  p.kill('SIGTERM')
  expect(await p.exited).not.toBe(0)
  for (let i = 0; i < 60 && !state(e).includes('STOPPED'); i++) await Bun.sleep(1000)
  expect(state(e)).toContain('STOPPED')
})
test.skipIf(!ONLINE)('detach: the run outlives the client; a second yk in the same checkout is not refused', () => {
  const e = execOf(yk('tool', '--detach', 'sleep', '20').out)
  expect(state(e)).toContain('RUNNING')
  yk('stop', e)
  const e1 = execOf(yk('tool', '--detach', 'sleep', '6').out)
  const second = yk('tool', 'echo', 'concurrent')
  expect(second.out, 'concurrent: second run did not finish').toMatch(/^RESULT status=SUCCESS/m)
  yk('stop', e1)
})
test.skipIf(!ONLINE)('levels: a failing run drops the known noise, names the next level, and yk detail returns the full text', () => {
  const out = yk('tool', 'sh', '-c', 'echo "BUILD FAILED in 4s"; echo "Foo.java:3: error: boom"; echo "second line"; exit 1').all
  expect(out).not.toContain('BUILD FAILED')
  expect(out).toContain('Foo.java:3: error: boom')
  const m = /^MORE yk detail (\S+) (\S+) /m.exec(out)
  expect(m, `no MORE line: ${out}`).toBeTruthy()
  expect(yk('detail', m![1]!, m![2]!, '--grep', 'boom').out).toContain('error: boom')
  expect(yk('detail', m![1]!, m![2]!, '--tail', '1').out.trim()).toBe('second line')
  expect(yk('tool', 'true').out.trim().split('\n').pop()).toStartWith('RESULT')
})
test.skipIf(!ONLINE)(
  'stages: a progress line without --all; bytes= is what was shown; wall= and cost= are there; yk stats reads the run',
  () => {
    const out = yk('tool', '--detach', 'sleep', '2').all
    const e = execOf(out)
    const s = /session=(\S+)/.exec(out)![1]!
    const w = yk('watch', s, e, '--stages').all
    expect(w).toMatch(/^STAGE run finished$/m)
    expect(w).toMatch(/^RESULT status=SUCCESS/m)
    expect(Number(/^RESULT .* bytes=(\d+)$/m.exec(w)![1])).toBe(Buffer.byteLength(w))
    expect(w).toMatch(/^RESULT .* wall=[0-9.]+ cost=\$[0-9.]+ bytes=[0-9]+$/m)
    expect(yk('stats').out).toContain('median_wall')
  },
)
