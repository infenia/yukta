// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// Offline test of the triage evaluation: a stub System One server that always answers "average", and the synthetic fixtures.
// Checks the plumbing and the arithmetic of eval.ts; it says nothing about a real model.
import { afterAll, beforeAll, expect, setDefaultTimeout, test } from 'bun:test'
import { join } from 'node:path'
import { HERE, put, rm, sh, tmp } from './helpers.ts'

setDefaultTimeout(120_000)
let d: string, server: ReturnType<typeof Bun.serve>
beforeAll(() => {
  d = tmp()
  server = Bun.serve({
    hostname: '127.0.0.1', port: 0,
    async fetch(req) {
      const body = (await req.json()) as { questions: { q: { criteria: Record<string, string> } } }
      const probs = Object.fromEntries(Object.keys(body.questions.q.criteria).map(k => [k, k === 'average' ? 0.7 : 0.05]))
      return Response.json({ answers: { q: { choice: 'average', probabilities: probs } } })
    },
  })
})
afterAll(() => { server.stop(true); rm(d) })

// the stub runs in this process, so eval runs asynchronously (a blocking call would starve it)
async function evalTriage(args: string[]): Promise<string> {
  const p = Bun.spawn([process.execPath, join(HERE, 'eval/triage/eval.ts'), ...args], { stdout: 'pipe', stderr: 'pipe', env: { ...process.env, OLLAMA_URL: `http://127.0.0.1:${server.port}` } })
  const [out, err] = await Promise.all([new Response(p.stdout).text(), new Response(p.stderr).text(), p.exited])
  return out + err
}
const fx = join(HERE, 'eval/triage/fixtures/synthetic-b.json')

test('case count, exact and within one, policy and 3 classes, and the misses', async () => {
  const out = await evalTriage(['--cases', fx, '--misses'])
  expect(out).toMatch(/^14 labelled case/m)
  // the stub always says average; heal.json has the levels easy, average and hard, and the seven labels map onto them (from7): 4 of the 14 synthetic-b cases
  // are average or moderate, which is the level it says; every level is within one of average
  expect(out).toMatch(/^heal.json +4\/14 +14\/14 /m)
  expect(out).toContain('policy-exact')
  expect(out).toMatch(/^heal.json .* 4\/14 +4\/14 +[0-9.]+s\/call, 0 failed/m)
  expect(out).toMatch(/miss .*h-license .*easy *got average/)
})
test('labels by id and the split; a variant file', async () => {
  put(join(d, 'labels.json'), { 'h-license': { level: 'trivial', set: 'dev' }, 'h-404': { level: 'moderate', set: 'test' } })
  expect(await evalTriage(['--cases', fx, '--labels', join(d, 'labels.json'), '--split', 'test'])).toMatch(/^1 labelled case/m)
  put(join(d, 'v.json'), { question: 'q', state: 'short', levels: { trivial: 't', average: 'a' } })
  expect(await evalTriage(['--cases', fx, '--variant', join(d, 'v.json')])).toMatch(/^v\.json/m)
  void sh
})
