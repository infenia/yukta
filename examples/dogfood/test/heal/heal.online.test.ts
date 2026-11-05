// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// Online test of the check and heal workflows against a fake claude and a fake System One (no API cost, about a minute). Needs the dev server (yk up) and
// YK_ONLINE=1. The project is a throwaway git repo whose stages are `test -f <stage>.ok`; the fake claude creates that file (or not) as the plan says,
// and logs when each call started and ended.
//
//   two stages        both are healed, one fixer after the other (no overlap)
//   no change         an attempt that changed nothing ends the ladder (sonnet is not called)
//   escalation        a change that does not fix it escalates to the next rung
//   single attempt    a stage in heal.json singleAttempt (reuse) gets one attempt even when it changed something
//   verify            a verify that fails goes back to heal
//   baseline          a stage that fails the same way on origin/main gets no fixer; one that fails differently does
//   triage            a "hard" failure starts at sonnet/low (fake Ollama)
//   triage stop       a label in heal.json triage.stop ends the heal with no fixer (a generated session; off in the shipped one)
import { afterAll, beforeAll, expect, setDefaultTimeout, test } from 'bun:test'
import { join } from 'node:path'
import { generate } from '../../src/heal/gen-session.ts'
import { devKey } from '../../src/lib.ts'
import { HERE, git, json, put, read, rm, script, sh, startOllama, tmp, ts } from '../helpers.ts'

const ONLINE = Boolean(process.env.YK_ONLINE)
if (ONLINE) {
  process.env.YUKTA_TOKEN ||= devKey()
  process.env.YUKTA_SERVER_URL ||= 'http://127.0.0.1:9858'
}
setDefaultTimeout(600_000)
let d: string, proj: string, healdir: string, ollama: { port: number; kill: () => void } | undefined
let result = ''
const claudeLog = (): string[] => read(join(d, 'claude.log')).split('\n').filter(Boolean)
const calls = (): number => claudeLog().filter((l) => l.startsWith('start')).length
const cli = (): string =>
  sh(['sh', '-c', 'cd "$1" && ls -t "$HOME"/.local/state/yukta-dev/builds/*/yukta-cli | head -1', '_', HERE]).out.trim()

beforeAll(() => {
  if (!ONLINE) return
  d = tmp()
  proj = join(d, 'proj')
  healdir = join(d, 'heal')
  sh(['mkdir', '-p', proj])
  git(proj, 'init', '-q')
  sh(['git', '-c', 'user.email=t@t', '-c', 'user.name=t', 'commit', '-q', '--allow-empty', '-m', 'init'], { cwd: proj })
  // fake claude: the plan file lists "<stage> <model> <fix|wrong|lie|nochange>" lines (lie: claims a fix, changes nothing); a call not in the plan is a nochange
  script(
    join(d, 'claude'),
    `import { appendFileSync, readFileSync, writeFileSync } from 'node:fs'
const a = process.argv.slice(2); const dir = '${d}'
const prompt = a[a.indexOf('-p') + 1] ?? ''; const model = a[a.indexOf('--model') + 1] ?? ''
const stage = /Stage "([a-z_]*)"/.exec(prompt)?.[1] ?? ''
const action = readFileSync(dir + '/plan', 'utf8').split('\\n').map(l => l.split(' ')).find(f => f[0] === stage && f[1] === model)?.[2] ?? 'nochange'
appendFileSync(dir + '/prompt.log', prompt + '\\n---\\n')
appendFileSync(dir + '/claude.log', 'start ' + Date.now() / 1000 + ' ' + stage + ' ' + model + ' ' + action + '\\n')
await Bun.sleep(1000)
let fixed = false
if (action === 'fix') { writeFileSync(stage + '.ok', ''); fixed = true }
else if (action === 'wrong') { appendFileSync('wrong.txt', 'wrong\\n'); fixed = true }
else if (action === 'lie') fixed = true
appendFileSync(dir + '/claude.log', 'end ' + Date.now() / 1000 + ' ' + stage + ' ' + model + '\\n')
console.log(JSON.stringify({ is_error: false, subtype: 'success', total_cost_usd: 0.001, usage: { input_tokens: 1, output_tokens: 1 }, structured_output: { fixed, summary: 'fake' } }))`,
  )
})
afterAll(() => {
  ollama?.kill()
  if (d && !process.env.KEEP) rm(d)
})

type Opts = {
  compile?: string
  tests?: string
  reuse?: string
  events?: string
  autoReuse?: string
  verify?: string
  triageUrl?: string
  budget?: string
  fast?: string
  session?: any
}
/** Runs a check of the project and sets `result` to its RESULT line. */
function scenario(name: string, plan: string[], o: Opts = {}): void {
  const session = `test-heal-${process.pid}`
  sh(['git', 'clean', '-fdq'], { cwd: proj })
  rm(healdir)
  rm(join(d, 'claude.log'))
  sh(['mkdir', '-p', healdir])
  put(join(d, 'plan'), plan.join('\n') + '\n')
  const sess = o.session ?? JSON.parse(read(join(HERE, 'session.json')))
  const names: string[] = sess.workflows.check.nodes
    .filter((n: any) => n.type === 'SUB_WORKFLOW' && n.config.workflowId === 'tool' && n.nodeId !== 'verify')
    .map((n: any) => n.nodeId)
  const cmds: Record<string, string> = Object.fromEntries(names.map((n) => [n, '']))
  Object.assign(cmds, { compile: o.compile ?? '', tests: o.tests ?? '', reuse: o.reuse ?? '' })
  const text = JSON.stringify(sess).split('__PROJECT_PATH__').join(proj).split('__DOGFOOD__').join(HERE)
  const rendered = { ...JSON.parse(text), sessionId: session, name: session }
  put(join(d, 'session.json'), rendered)
  const apply = sh([cli(), 'session', 'apply', join(d, 'session.json')])
  expect(apply.code, `${name}: session apply: ${apply.all}`).toBe(0)
  const dash = Object.fromEntries(names.map((n) => [n, '-']))
  const mark = join(healdir, 'fast.ok')
  const payload = {
    project: proj,
    timeout: 120,
    healDir: healdir,
    events: o.events ?? '-',
    modules: '-',
    models: '-',
    heal: 'on',
    triage: o.triageUrl ? 'on' : 'off',
    rules: join(HERE, 'fixer-rules.md'),
    claudeBin: join(d, 'claude'),
    budgetCheck: o.budget ?? '-',
    triageModel: 'fake',
    triageUrl: o.triageUrl ?? 'http://127.0.0.1:1',
    verifyCommand: o.verify ?? 'true',
    cmd: cmds,
    auto: { ...dash, ...(o.autoReuse ? { reuse: o.autoReuse } : {}) },
    fmt: dash,
    fast: o.fast ? `${o.fast} && touch ${mark}` : '',
    chain: o.fast ? Object.fromEntries(Object.entries(cmds).map(([k, v]) => [k, v === '' ? v : `test -f ${mark} || ${v}`])) : cmds,
  }
  const start = sh([cli(), '-o', 'json', 'workflow', 'start', session, 'check', '--payload', JSON.stringify(payload)])
  const j = JSON.parse(start.out)
  const exec: string = (j.data ?? j).executionId
  expect(exec, `${name}: could not start`).toBeTruthy()
  for (let i = 0; i < 240; i++) {
    const st = JSON.parse(sh([cli(), '-o', 'json', 'workflow', 'status', session, exec]).out)
    if (!/^(RUNNING|QUEUED|PAUSED)$/.test((st.data ?? st).status)) break
    Bun.sleepSync(1000)
  }
  const r = ts('yk', ['result', session, exec], { env: { YUKTA_HEAL_RECORDS: healdir, YUKTA_SERVER_URL: 'http://127.0.0.1:9858' } })
  result = r.out.split('\n').find((l) => l.startsWith('RESULT')) ?? ''
  console.log(`     ${name}: ${result}`)
}
const expectIn = (name: string, s: string): void => void expect(result, `${name} expected '${s}' in: ${result}`).toContain(s)

test.skipIf(!ONLINE)('two stages: both are healed, one fixer after the other', () => {
  scenario('two stages', ['compile haiku fix', 'tests haiku fix'], {
    compile: 'test -f compile.ok',
    tests: 'test -f tests.ok',
    verify: 'test -f compile.ok && test -f tests.ok',
  })
  for (const s of ['status=SUCCESS', 'compile:haiku/high', 'tests:haiku/high']) expectIn('two stages', s)
  let end = 0
  for (const l of claudeLog()) {
    const f = l.split(' ')
    if (f[0] === 'start') expect(end === 0 || Number(f[1]) >= end, 'two fixers overlapped').toBe(true)
    else end = Number(f[1])
  }
})
test.skipIf(!ONLINE)('no change: an attempt that changed nothing ends the ladder', () => {
  scenario('no change', ['compile haiku nochange', 'compile sonnet fix'], { compile: 'test -f compile.ok' })
  expectIn('no change', 'status=FAILED')
  expectIn('no change', 'failed_node=compile')
  expect(calls()).toBe(1)
})
test.skipIf(!ONLINE)('escalation: a change that does not fix it escalates to the next rung; a claimed fix that changed nothing', () => {
  scenario('escalation', ['compile haiku wrong', 'compile sonnet fix'], { compile: 'test -f compile.ok' })
  expectIn('escalation', 'status=SUCCESS')
  expectIn('escalation', 'compile:sonnet/low')
  expect(calls()).toBe(2)
  expect(json(join(healdir, 'compile-1.json')).summary).toBe(
    'NOT FIXED: the model claimed a fix but the stage still fails. The model said: fake',
  )
  scenario('claimed fix, nothing changed', ['compile haiku lie', 'compile sonnet fix'], { compile: 'test -f compile.ok' })
  expectIn('lie', 'status=FAILED')
  expectIn('lie', 'failed_node=compile')
  expect(json(join(healdir, 'compile-1.json')).summary).toBe('NOT FIXED, guard: claimed a fix but changed nothing. The model said: fake')
})
test.skipIf(!ONLINE)('single attempt, check budget, excerpts', () => {
  scenario('single attempt', ['reuse haiku wrong', 'reuse sonnet fix'], { reuse: 'test -f reuse.ok' })
  expectIn('single attempt', 'status=FAILED')
  expectIn('single attempt', 'failed_node=reuse')
  expect(calls()).toBe(1)
  // the fake claude costs $0.001 a call: a cap of $0.0005 is spent after the first rung, so the second never starts
  scenario('check budget', ['compile haiku nochange', 'compile sonnet fix'], { budget: '0.0005', compile: 'test -f compile.ok' })
  expectIn('check budget', 'status=FAILED')
  expect(calls()).toBe(1)
  sh(['mkdir', '-p', join(proj, 'src')])
  put(join(proj, 'src/A.java'), Array.from({ length: 40 }, (_, i) => `line ${i + 1}`).join('\n') + '\n')
  git(proj, 'add', 'src/A.java')
  scenario('excerpts', ['compile haiku fix'], { compile: 'echo "src/A.java:20: error: boom"; test -f compile.ok' })
  expectIn('excerpts', 'status=SUCCESS')
  expect(read(join(d, 'prompt.log')), 'no excerpt in the prompt').toContain('line 20')
})
test.skipIf(!ONLINE)('fast path: pass makes the chain no-ops, fail runs the chain as ever', () => {
  scenario('fast path pass', ['compile haiku fix'], { fast: 'true', compile: 'false' })
  expectIn('fast pass', 'status=SUCCESS')
  expectIn('fast pass', 'healed=-')
  expect(calls()).toBe(0)
  scenario('fast path fail', ['compile haiku fix'], { fast: 'false', compile: 'test -f compile.ok' })
  expectIn('fast fail', 'status=SUCCESS')
  expectIn('fast fail', 'compile:haiku/high')
})
test.skipIf(!ONLINE)('verify: a verify that fails goes back to heal', () => {
  scenario('verify', ['compile haiku fix', 'verify haiku fix'], {
    compile: 'test -f compile.ok',
    verify: 'test -f compile.ok && test -f verify.ok',
  })
  expectIn('verify', 'status=SUCCESS')
  expectIn('verify', 'verify:haiku/high')
})
test.skipIf(!ONLINE)('baseline: a stage that fails the same way on origin/main gets no fixer; one that fails differently does', () => {
  git(proj, 'update-ref', 'refs/remotes/origin/main', 'HEAD')
  scenario('known baseline failure', ['compile haiku fix'], { compile: 'echo "error: boom at A.java:20"; test -f compile.ok' })
  expectIn('baseline', 'status=FAILED')
  expectIn('baseline', 'failed_node=compile')
  expect(calls()).toBe(0)
  // the directory name is part of the problem line, and differs between the project and the clean checkout of origin/main
  scenario('baseline failure differs', ['compile haiku fix'], { compile: 'echo "error: in $(basename "$PWD")"; test -f compile.ok' })
  expectIn('baseline differs', 'status=SUCCESS')
  expectIn('baseline differs', 'compile:haiku/high')
  git(proj, 'update-ref', '-d', 'refs/remotes/origin/main')
})
test.skipIf(!ONLINE)('triage: a hard failure starts at sonnet/low; a stop label ends the heal with no fixer', async () => {
  ollama = await startOllama(d)
  const url = `http://127.0.0.1:${ollama.port}`
  scenario('triage', ['compile haiku fix', 'compile sonnet fix'], {
    compile: 'echo error: compile failed difficulty: hard; test -f compile.ok',
    triageUrl: url,
  })
  expectIn('triage', 'status=SUCCESS')
  expectIn('triage', 'compile:sonnet/low')
  expect(
    claudeLog().some((l) => l.includes(' haiku ')),
    'haiku was called for a hard failure',
  ).toBe(false)
  const policy = json(join(HERE, 'heal.json'))
  policy.triage.stop = {
    environment: {
      describe: 'Caused by the machine or its setup, not by the change: a refused connection, a missing tool or credential.',
      reason: 'not caused by the change, no fixer started',
    },
  }
  scenario('triage stop', ['compile haiku fix'], {
    compile: 'echo error: connection refused difficulty: environment; test -f compile.ok',
    triageUrl: url,
    session: generate(JSON.parse(read(join(HERE, 'session.json'))), policy),
  })
  expectIn('triage stop', 'status=FAILED')
  expectIn('triage stop', 'failed_node=compile')
  expect(calls()).toBe(0)
})
test.skipIf(!ONLINE)('give up, and the free fix of reuse', () => {
  // five attempts at compile in the last week, none fixed: the healer does not try again; the same five a month ago do not count
  const now = new Date().toISOString().replace(/\.\d+Z$/, 'Z'),
    old = new Date(Date.now() - 30 * 86400000).toISOString().replace(/\.\d+Z$/, 'Z')
  for (const ts_ of [now, old]) {
    put(
      join(d, 'events.jsonl'),
      Array.from({ length: 5 }, () =>
        JSON.stringify({
          v: 1,
          ts: ts_,
          run: 'x',
          event: 'fix',
          stage: 'compile',
          attempt: 1,
          model: 'haiku',
          fixed: false,
          summary: 'it did not work',
        }),
      ).join('\n') + '\n',
    )
    scenario(`give up (${ts_})`, ['compile haiku fix'], { events: join(d, 'events.jsonl'), compile: 'test -f compile.ok' })
    if (ts_ === now) {
      expectIn('give up', 'status=FAILED')
      expect(calls()).toBe(0)
    } else {
      expectIn('give up', 'status=SUCCESS')
      expectIn('give up', 'compile:haiku/high')
    }
  }
  // the free fix makes reuse pass: no model is asked, and the run says healed=reuse:auto
  scenario('reuse free fix', ['reuse haiku fix', 'reuse sonnet fix'], { autoReuse: 'touch reuse.ok', reuse: 'test -f reuse.ok' })
  expectIn('reuse free fix', 'status=SUCCESS')
  expectIn('reuse free fix', 'healed=reuse:auto')
  expect(calls()).toBe(0)
  // the free fix does not help: the model gets one attempt (reuse is a single-attempt stage), no more
  scenario('reuse free fix cannot', ['reuse haiku nochange', 'reuse sonnet fix'], { autoReuse: 'true', reuse: 'test -f reuse.ok' })
  expectIn('reuse cannot', 'status=FAILED')
  expectIn('reuse cannot', 'failed_node=reuse')
  expect(calls()).toBe(1)
})
