// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// The script steps of heal and attempt (workflows heal, fix-from and attempt in session.json, see src/gen-heal.ts). Each is run by a
// PROCESS_EXECUTOR node; they are usable by hand too.
//
//   brief           heal: stdin {stdout, ...} (the failed stage's output) or a tool result {results: [{stdout}]}; prints
//                   {brief, problems, triage, known}: the short text the triage model reads, the problem lines the fixer reads, and whether
//                   the stage fails the same way on a clean origin/main (run once per base commit; the heal then stops, with no fixer)
//   triage-log      heal: a side branch of every port of the triage: writes the triage event (route, label, probability, seconds)
//   record-auto     heal: the free fix made the stage pass: writes the record behind healed=<stage>:auto
//   healed          check: exit 0 when anything was healed (so verify runs), else 1
//   prepare         attempt: stdin {problems, previous}; numbers the attempt, snapshots the tree, prints {prompt, attempt};
//                   exit 4 when the model is not in YUKTA_MODELS
//   claimed         attempt: stdin = the claude workflow's result; keeps its cost, usage and claim
//   judge           attempt: the guard. Decides from what changed on disk, not from what Claude said; exit 0 = a clean change
//   report          attempt: stdin = the last node's result; writes the attempt's record and prints
//                   {stage, model, effort, attempt, fixed, cost, summary, problems}; always exit 0 (the workflow branches on fixed)
//
// Environment: YUKTA_HEAL_DIR (the records of the run, required), YUKTA_STAGE, YUKTA_MODEL, YUKTA_EFFORT, YUKTA_MODELS, YUKTA_MODULE,
// YUKTA_COMMAND (the command that proves the stage), YUKTA_PROBLEMS, YUKTA_TRIAGE, YUKTA_HEAL_TIMEOUT, YUKTA_HEAL_EVENTS (a file;
// every step appends one JSON line).
// A value of "-" means empty: a start variable of a workflow cannot be.
// Nothing here commits or reverts.
import { mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import {
  HERE,
  type Json,
  appendFileSync,
  basename,
  dirname,
  git,
  isDir,
  isFile,
  join,
  mkdirp,
  readFileSync,
  readJson,
  readdirSync,
  rmSync,
  run,
  sha1,
  statSync,
  text,
  tryJson,
  writeFileSync,
} from '../lib.ts'

// Paths a fixer must not touch: the rules it is being measured against, the build and this tooling.
const PROTECTED = /^(config\/|build-logic\/|gradle\/|\.github\/|\.claude\/|examples\/dogfood\/|REUSE\.toml|.*\.gradle\.kts$)/
// Ways to make a check pass without fixing the code.
const SUPPRESSION = /SuppressFBWarnings|NOPMD|CHECKSTYLE:OFF|@SuppressWarnings\("(PMD|checkstyle)|@Disabled|spotless:off/
const TESTS_RE = /(\/src\/test\/|Test\.java$|_test\.go$|\.(test|spec)\.[jt]sx?$)/
const lines = (s: string): string[] => (s.length ? s.replace(/\n$/, '').split('\n') : [])

/** spinner and colour codes out (mint redraws one line many times), carriage returns as newlines */
// eslint-disable-next-line no-control-regex
export const plain = (s: string): string => s.replace(/\x1b\[[0-9;?]*[A-Za-z]/g, '').replace(/\r/g, '\n')

/** what the stage checks, from its name (plan names the stages), so the triage question can stay neutral */
export function ecosystem(stage: string): string {
  if (stage.startsWith('cli_')) return 'go (cli)'
  if (stage.startsWith('ui_')) return 'typescript (ui)'
  if (['reuse', 'actionlint', 'docs'].includes(stage)) return 'repo files'
  return 'java (gradle)'
}

/** The lines of a run that say what went wrong, as yukta's --quiet picks them. */
export function problemLines(raw: string, max = 60): string[] {
  const out: string[] = []
  let ctx = 0
  for (const l of plain(raw).split('\n')) {
    if (/What went wrong/.test(l)) {
      ctx = 3
      out.push(l)
      continue
    }
    if (ctx > 0 && /^\* /.test(l)) {
      ctx = 0
      continue
    }
    if (ctx > 0) {
      ctx--
      out.push(l)
      continue
    }
    if (/(^|[^a-z])(error|failed|failure)([^a-z]|$)|exception|see the report at|violation/.test(l.toLowerCase())) out.push(l)
  }
  const seen = new Set<string>()
  return out
    .map((l) => l.slice(0, 300))
    .filter((l) => (seen.has(l) ? false : (seen.add(l), true)))
    .slice(0, max)
}

function walk(dir: string, accept: (p: string) => boolean, out: string[] = []): string[] {
  let ents
  try {
    ents = readdirSync(dir, { withFileTypes: true })
  } catch {
    return out
  }
  for (const e of ents) {
    if (e.name === 'node_modules' || e.name === '.git') continue
    const p = join(dir, e.name)
    if (e.isDirectory()) walk(p, accept, out)
    else if (accept(p)) out.push(p)
  }
  return out
}

/** test classes with a failure in a recent report */
export function failingTests(): string[] {
  const names = walk(
    '.',
    (p) =>
      /\/build\/test-results\/.*\/TEST-[^/]*\.xml$/.test(p) &&
      Date.now() - statSync(p).mtimeMs < 120 * 60000 &&
      /<(failure|error) /.test(readFileSync(p, 'utf8')),
  )
  return [...new Set(names.map((p) => /TEST-(.*)\.xml$/.exec(p)![1]!))].sort().slice(0, 15)
}

/** problem lines compared across two checkouts: the path of the checkout and every number out */
const norm = (root: string, s: string): string => s.replaceAll(`${root}/`, '').replace(/[0-9]+/g, 'N')

/** up to 6 file:line spots named in the problem lines, 6 lines either side, so a cheap model needs fewer reads. Only tracked files. */
export function excerpts(problems: string): string {
  const spots: string[] = []
  const seen = new Set<string>()
  for (const m of problems.matchAll(/[A-Za-z0-9_./-]+\.(?:java|go|ts|tsx|js|kts|sh|py|md|mdx|json):[0-9]+/g)) {
    const s = m[0].replace(/^(\/[^:]*\/)?/, '')
    if (!seen.has(s)) {
      seen.add(s)
      spots.push(s)
    }
    if (spots.length >= 20) break
  }
  const out: string[] = []
  let shown = 0
  for (const spot of spots) {
    const i = spot.lastIndexOf(':')
    const f = spot.slice(0, i)
    const l = Number(spot.slice(i + 1))
    if (!f || !isFile(f) || git(['ls-files', '--error-unmatch', '--', f]).code) continue
    out.push(`${f}:${l}`)
    const a = l > 6 ? l - 6 : 1
    readFileSync(f, 'utf8')
      .split('\n')
      .forEach((line, k) => {
        if (k + 1 >= a && k + 1 <= l + 6) out.push(`${String(k + 1).padStart(5)}  ${line.slice(0, 200)}`)
      })
    if (++shown >= 6) break
  }
  return out.join('\n')
}

/** {path: hash} of every modified or untracked file, to tell what a step changed. */
function snapshot(): Map<string, string> {
  const files = git(['ls-files', '-m', '-o', '--exclude-standard', '-z']).stdout.split('\0').filter(Boolean)
  const m = new Map<string, string>()
  for (const f of files) {
    const r = git(['hash-object', '--', f])
    m.set(f, r.code ? 'deleted' : r.stdout.trim())
  }
  return m
}
const snapText = (m: Map<string, string>): string =>
  m.size
    ? [...m]
        .map(([f, h]) => `${h} ${f}`)
        .sort()
        .join('\n') + '\n'
    : ''

/** paths whose state differs from the snapshot in FILE */
function changedSince(file: string): string[] {
  const before = new Map<string, string>()
  for (const l of lines(text(file)).filter(Boolean)) {
    const i = l.indexOf(' ')
    before.set(l.slice(i + 1), l.slice(0, i))
  }
  const now = snapshot()
  const out = new Set<string>()
  for (const [f, h] of now) if (before.get(f) !== h) out.add(f)
  for (const [f] of before) if (!now.has(f)) out.add(f)
  return [...out].sort()
}

const stdoutOf = (msg: Json): string => msg?.results?.[0]?.stdout ?? msg?.stdout ?? ''

export async function main(argv: string[], stdin?: () => string): Promise<number> {
  const action = argv[0]
  if (!action) {
    console.error('usage: heal brief|record-auto|healed|prepare|claimed|judge|report')
    return 2
  }
  const dir = process.env.YUKTA_HEAL_DIR as string
  if (!dir) {
    console.error('YUKTA_HEAL_DIR is required')
    return 1
  }
  for (const v of [
    'YUKTA_STAGE',
    'YUKTA_MODEL',
    'YUKTA_EFFORT',
    'YUKTA_TRIAGE_ROUTE',
    'YUKTA_TRIAGE_START',
    'YUKTA_TRIAGE_MODEL',
    'YUKTA_MODELS',
    'YUKTA_MODULE',
    'YUKTA_COMMAND',
    'YUKTA_PROBLEMS',
    'YUKTA_TRIAGE',
    'YUKTA_HEAL_TIMEOUT',
    'YUKTA_HEAL_EVENTS',
    'YUKTA_BUDGET_CHECK',
  ])
    if (process.env[v] === '-') delete process.env[v]
  const env = process.env
  const stage = env.YUKTA_STAGE ?? ''
  const model = env.YUKTA_MODEL ?? ''
  const effort = env.YUKTA_EFFORT ?? ''
  const module = env.YUKTA_MODULE ?? ''
  const read = stdin ?? ((): string => readFileSync(0, 'utf8'))
  mkdirp(dir)
  const tStart = Date.now() / 1000
  const secsSince = (t: number): number => Math.round((Date.now() / 1000 - t) * 10) / 10

  /** one line on the monitoring log. Never fails a step: monitoring must not break a heal. */
  const emit = (ev: string, fields: Json = {}): void => {
    const events = env.YUKTA_HEAL_EVENTS
    if (!events) return
    try {
      mkdirp(dirname(events))
      const top = git(['rev-parse', '--show-toplevel']).stdout.trim() || process.cwd()
      const rec: Json = {
        v: 1,
        ts: new Date().toISOString().replace(/\.\d+Z$/, 'Z'),
        run: '-',
        session: env.YUKTA_SESSION_ID ?? '-',
        project: basename(top),
        event: ev,
        ...fields,
      }
      if (ev === 'fix' && !(rec.summary ?? '')) rec.summary = 'no summary recorded'
      appendFileSync(events, JSON.stringify(rec) + '\n')
    } catch {
      /* ignore */
    }
  }
  const attemptNo = (): number => parseInt(text(join(dir, `${stage}.attempt`)).trim() || '0', 10) || 0
  const recordFiles = (): string[] =>
    isDir(dir)
      ? readdirSync(dir)
          .filter((f) => /-[0-9][^/]*\.json$/.test(f))
          .sort()
          .map((f) => join(dir, f))
      : []
  const stageRecords = (): string[] =>
    recordFiles().filter((f) => basename(f).startsWith(`${stage}-`) && /^[0-9]/.test(basename(f).slice(stage.length + 1)))

  /** <dir>/<stage>-<n>.json, what healed= and the next attempt read */
  const record = (fixed: boolean, files: string[], cost: string, summary0: string, usage: Json = null, quiet = false): void => {
    const n = attemptNo()
    const summary = summary0.replaceAll('\n', ' ').slice(0, 160)
    writeFileSync(
      join(dir, `${stage}-${n}.json`),
      JSON.stringify(
        { stage, attempt: n, mode: model, effort: effort === '' ? null : effort, fixed, files, cost: Number(cost), summary, usage },
        null,
        2,
      ) + '\n',
    )
    if (!quiet)
      console.error(
        `HEAL ${stage} attempt=${n} mode=${model}${effort ? ` effort=${effort}` : ''} fixed=${fixed} files=${files.length} cost=$${cost} summary=${summary.slice(0, 120)}`,
      )
  }

  /** 0 when the failed stage (its output is in OUT) fails the same way on a clean origin/main, so no fixer can be blamed for it. */
  const baselineKnown = (out: string): boolean => {
    if (!env.YUKTA_COMMAND) return false
    const root = git(['rev-parse', '--show-toplevel']).stdout.trim()
    if (!root) return false
    const baseR = git(['rev-parse', '--verify', '-q', 'origin/main'])
    if (baseR.code) return false
    const base = baseR.stdout.trim()
    const cache = join(env.YUKTA_DEV_STATE_DIR || join(root, '.yukta-dev'), 'baseline', base)
    if (!isFile(join(cache, stage))) {
      const wt = mkdtempSync(join(tmpdir(), 'yk-baseline-'))
      if (git(['worktree', 'add', '-q', '--detach', wt, base]).code) {
        rmSync(wt, { recursive: true, force: true })
        return false
      }
      const r = run(['sh', '-c', env.YUKTA_COMMAND], { cwd: wt, timeoutMs: Number(env.YUKTA_BASELINE_TIMEOUT || 3600) * 1000 })
      if (r.code !== 128) {
        mkdirp(cache)
        const body =
          r.code === 0
            ? 'pass\n'
            : `fail\n${problemLines(r.stdout + r.stderr, 60)
                .map((l) => norm(wt, l))
                .join('\n')}\n`
        writeFileSync(join(cache, `${stage}.${process.pid}`), body)
        run(['mv', join(cache, `${stage}.${process.pid}`), join(cache, stage)])
      }
      if (git(['worktree', 'remove', '-f', wt]).code) rmSync(wt, { recursive: true, force: true })
    }
    const c = lines(text(join(cache, stage)))
    if (c[0] !== 'fail') return false
    const mine = problemLines(out, 60).map((l) => norm(root, l))
    const known = new Set(c.slice(1))
    return mine.length > 0 && mine.every((l) => known.has(l))
  }

  switch (action) {
    case 'brief': {
      const msg = tryJson(read()) ?? {}
      const out = msg.stdout ?? msg.results?.[0]?.stdout ?? ''
      const failing = (): string => (stage === 'tests' ? `failing test classes: ${failingTests().join(' ')}` : '')
      const briefLines = [
        `stage: ${stage}`,
        `ecosystem: ${ecosystem(stage)}`,
        `files changed so far: ${lines(git(['ls-files', '-m', '-o', '--exclude-standard']).stdout).length}`,
        `command: ${env.YUKTA_COMMAND ?? ''}`,
        ...problemLines(out, 25),
        ...(failing() ? [failing()] : []),
      ]
      const brief = briefLines
        .map((l) => l.slice(0, 300))
        .join('\n')
        .slice(0, 1000)
      // the keyword lines do not say it for every tool (reuse lists the files without a licence): the end of the output too
      const problems = [
        ...problemLines(out, 60),
        'Output (last 40 lines):',
        ...plain(out)
          .split('\n')
          .slice(-40)
          .map((l) => l.slice(0, 300)),
        ...(failing() ? [failing()] : []),
      ].join('\n')
      let known = false
      if (baselineKnown(out)) {
        known = true
        console.error(`stage ${stage} already fails the same way on origin/main: a known baseline failure, no fixer started`)
        emit('baseline', { stage, known: true })
      }
      writeFileSync(join(dir, `${stage}.triage-t0`), `${tStart}\n`)
      emit('brief', { stage, chars: brief.length, secs: secsSince(tStart) })
      console.log(JSON.stringify({ brief, problems, triage: env.YUKTA_TRIAGE ?? 'on', known }))
      return 0
    }
    case 'triage-log': {
      // stdin: the message the triage routed. The classification is in its metadata; where a step sees it is not documented, so look in the
      // places it can be and keep the raw text of the first 300 characters for the report when none has it.
      const msg = env.YUKTA_PAYLOAD || read()
      const t0 = parseFloat(text(join(dir, `${stage}.triage-t0`)).trim()) || tStart
      const route = env.YUKTA_TRIAGE_ROUTE ?? ''
      const m = tryJson(msg) ?? null
      const c = m?.metadata?.classification ?? m?.classification ?? m?.results?.[0]?.metadata?.classification ?? {}
      const fallback = route === 'error' || route === 'uncertain'
      const seen = Object.keys(c).length > 0
      const why = env.YUKTA_METADATA_FAILURE_MESSAGE ?? ''
      emit('triage', {
        stage,
        route: fallback ? route : 'level',
        label: c.label ?? (fallback ? null : route),
        p: c.probability ?? null,
        start: env.YUKTA_TRIAGE_START ?? '',
        model: env.YUKTA_TRIAGE_MODEL ?? '',
        secs: secsSince(t0),
        chars: (m?.brief ?? '').length,
        fallback: fallback ? route : null,
        classification_seen: seen,
        raw: seen ? null : msg.slice(0, 300),
        why: why === '' ? null : why.slice(0, 500),
      })
      return 0
    }
    case 'record-auto': {
      writeFileSync(join(dir, `${stage}.attempt`), `${stageRecords().length + 1}\n`)
      return (record2(true, [], '0', 'the free automatic fix made the stage pass', 'auto', ''), 0)
    }
    case 'healed':
      return recordFiles().some((f) => tryJson(text(f))?.fixed) ? 0 : 1
    case 'prepare': {
      const msg = tryJson(read()) ?? {}
      const n = stageRecords().length + 1
      writeFileSync(join(dir, `${stage}.attempt`), `${n}\n`)
      // a model that is left out (yk check --models) still has its rungs; the attempt stops here and costs nothing
      if (env.YUKTA_MODELS && !`,${env.YUKTA_MODELS},`.includes(`,${model},`)) {
        console.error(`model ${model} is not allowed in this run (--models ${env.YUKTA_MODELS})`)
        return 4
      }
      // the budget of the whole check: what the attempts of every stage have cost so far; at the cap no rung starts
      if (env.YUKTA_BUDGET_CHECK && recordFiles().length) {
        const spent = recordFiles().reduce((a, f) => a + (tryJson(text(f))?.cost ?? 0), 0)
        if (spent >= Number(env.YUKTA_BUDGET_CHECK)) {
          console.error(`the budget of this check is spent: $${spent} of $${env.YUKTA_BUDGET_CHECK} (YUKTA_BUDGET_CHECK)`)
          return 4
        }
      }
      // a stage the healer has not fixed lately is not tried again: heal.json giveUp {minAttempts, maxFixRate, days} against the fix events of the log
      const events = env.YUKTA_HEAL_EVENTS
      if (events && isFile(events) && statSync(events).size) {
        const policy = readJson(join(HERE, 'heal.json')).giveUp
        if (policy) {
          const since = new Date(Date.now() - policy.days * 86400000).toISOString().replace(/\.\d+Z$/, 'Z')
          const a = lines(text(events))
            .map(tryJson)
            .filter(
              (e: Json) =>
                e &&
                e.event === 'fix' &&
                e.stage === stage &&
                (e.model ?? '') !== 'auto' &&
                (e.ts ?? '') >= since &&
                !(e.summary ?? '').startsWith('the attempt did not start'),
            )
          const k = a.filter((e: Json) => e.fixed === true).length
          if (a.length >= policy.minAttempts && k / a.length <= policy.maxFixRate) {
            console.error(
              `giving up on ${stage}: the healer fixed ${k} of ${a.length} attempts in the last ${policy.days} days (heal.json giveUp)`,
            )
            return 4
          }
        }
      }
      writeFileSync(join(dir, `${stage}-${n}.before`), snapText(snapshot()))
      // the working tree as it is now (the developer's own edits included), to diff this attempt against
      writeFileSync(join(dir, `${stage}-${n}.base`), `${git(['stash', 'create']).stdout.trim() || 'HEAD'}\n`)
      const problems: string = msg.problems ?? ''
      const previous: string = msg.previous ?? ''
      const stat = git(['diff', '--stat']).stdout.trim().split('\n').slice(-8).join('\n')
      // everything that varies per run is in the user prompt; the system prompt is one constant file, so every attempt shares the cached prefix
      const ex = excerpts(problems)
      const prompt = `Stage "${stage}" of the Yukta quality gate failed. Fix the root cause in the source so it passes.
${env.YUKTA_SESSION_ID ? `Yukta session: ${env.YUKTA_SESSION_ID}\n` : ''}Problems (from the run):
${problems}
${ex ? `The code around the failures (file:line, a few lines either side):\n${ex}\n` : ''}${previous ? `Earlier attempt: ${previous}\n` : ''}Files already changed in the working tree (by the developer, keep them):
${stat || 'none'}
Verify with only:
${env.YUKTA_COMMAND || 'the failing command'}
${env.YUKTA_HEAL_TIMEOUT ? `You have about ${Math.floor(Number(env.YUKTA_HEAL_TIMEOUT) / 60)} minutes at ${effort || 'this'} effort. If you cannot fix it in that time, stop and answer fixed=false with what you learned.\n` : ''}Then answer with the JSON object only.`
      console.log(JSON.stringify({ prompt, attempt: n }))
      return 0
    }
    case 'claimed': {
      const n = attemptNo()
      const msg = tryJson(read()) ?? {}
      // the claude workflow's result: {results: [{output: <claude's JSON>}]}
      const r: Json = msg.results?.[0]?.output ?? msg.output ?? {}
      let answer: Json = r.structured_output ?? (typeof r.result === 'string' ? tryJson(r.result) : undefined) ?? {}
      if (answer === null || typeof answer !== 'object') answer = {}
      const bad = r.is_error === true || (r.subtype ?? 'success') !== 'success'
      const u = r.usage
      const rec = {
        cost: r.total_cost_usd ?? 0,
        usage:
          u == null
            ? null
            : {
                input: u.input_tokens,
                cache_creation: u.cache_creation_input_tokens,
                cache_read: u.cache_read_input_tokens,
                output: u.output_tokens,
              },
        claimed: answer.fixed ?? false,
        summary:
          answer.summary ?? 'no answer from claude' + (bad ? ` (${r.subtype ?? 'error'}: ${String(r.result ?? '').slice(0, 100)})` : ''),
      }
      writeFileSync(join(dir, `${stage}-${n}.claude`), JSON.stringify(rec) + '\n')
      // the live line of the attempt (the recheck decides whether it fixed the stage; healed= in RESULT says so)
      console.error(
        `HEAL ${stage} attempt=${n} mode=${model} effort=${effort} claimed=${rec.claimed} cost=$${rec.cost} summary=${String(rec.summary).slice(0, 120)}`,
      )
      return 0
    }
    case 'judge': {
      const n = attemptNo()
      const claimed = tryJson(text(join(dir, `${stage}-${n}.claude`)))?.claimed === true
      const changed = changedSince(join(dir, `${stage}-${n}.before`))
      const base = text(join(dir, `${stage}-${n}.base`)).trim() || 'HEAD'
      let violations = ''
      const touched = changed.filter((f) => PROTECTED.test(f))
      if (touched.length) violations += ` touched protected path: ${touched.slice(0, 3).join(',')}.`
      const diffs = changed
        .filter((f) => isFile(f))
        .map((f) => git(['diff', '-U0', base, '--', f]).stdout)
        .join('')
      const untracked = lines(git(['ls-files', '-o', '--exclude-standard']).stdout)
        .filter((f) => changed.includes(f))
        .slice(0, 50)
      const addedText = [...lines(diffs).filter((l) => /^\+[^+]/.test(l)), ...untracked.map((f) => text(f))].join('\n')
      if (SUPPRESSION.test(addedText)) violations += ' added a suppression.'
      const removed = lines(diffs).filter((l) => /^-[^-].*(assert|@Test|verify\()/.test(l)).length
      const added = lines(diffs).filter((l) => /^\+[^+].*(assert|@Test|verify\()/.test(l)).length
      if (removed > added) violations += ` removed assertions (${removed} removed, ${added} added).`
      // a test whose assertion was edited, with no code changed beside it, is a test made to fit the code
      if (changed.some((f) => TESTS_RE.test(f)) && !changed.some((f) => !TESTS_RE.test(f)) && removed > 0 && added > 0)
        violations += ' changed test expectations without changing any code.'
      if (claimed && !changed.length) violations += ' claimed a fix but changed nothing.'
      let outside: string[] = []
      if (!violations && claimed && changed.length) {
        const mods = module.split(/\s+/).filter(Boolean)
        if (mods.length) outside = changed.filter((f) => !mods.some((m) => f.startsWith(`${m}/`)))
      }
      writeFileSync(join(dir, `${stage}-${n}.verdict`), JSON.stringify({ files: changed, violations, outside }) + '\n')
      // 0 = a clean change that Claude says fixes it: the stage runs again
      return claimed && !violations && changed.length ? 0 : 1
    }
    case 'report': {
      const n = attemptNo()
      const msg = tryJson(read()) ?? {}
      const claudeRec = tryJson(text(join(dir, `${stage}-${n}.claude`)))
      const verdict = tryJson(text(join(dir, `${stage}-${n}.verdict`)))
      const cost = String(claudeRec?.cost ?? 0)
      const usage = claudeRec?.usage ?? null
      let summary: string = claudeRec?.summary ?? ''
      if (!summary) summary = 'the attempt did not start (model not allowed, or the issue budget is spent)'
      const files: string[] = verdict?.files ?? []
      const guard: string = verdict?.violations ?? ''
      // the recheck ran when the message is a tool result labelled with this stage: it passed when its command exited 0
      const ran = (msg.results?.[0]?.input?.label ?? '') === stage
      const ok = msg.results?.[0]?.success ?? false
      let problems = env.YUKTA_PROBLEMS ?? ''
      if (ran && ok !== true) {
        const out = stdoutOf(msg)
        problems =
          problemLines(out, 60).join('\n') +
          '\nOutput (last 40 lines):\n' +
          plain(out)
            .split('\n')
            .slice(-40)
            .map((l) => l.slice(0, 300))
            .join('\n')
      }
      const fixed = ran && ok === true
      // no progress: the stage fails the way the previous attempt left it (same problem lines, numbers aside), so a stronger model would start from
      // the same problems and the same wrong idea; the ladder stops (gen-heal reads `same`)
      let same = false
      if (ran && ok !== true) {
        const digest = sha1(
          problemLines(stdoutOf(msg), 60)
            .map((l) => l.replace(/[0-9]+/g, 'N'))
            .join('\n') + '\n',
        ).slice(0, 16)
        const f = join(dir, `${stage}.digest`)
        if (isFile(f) && text(f).trim() === digest) same = true
        writeFileSync(f, digest + '\n')
      }
      // What the model says is not what happened: when the guard objected, or the model claimed a fix but the recheck still fails, the summary says
      // NOT FIXED and why, and the model's words follow as a quote.
      const claimed = claudeRec?.claimed === true
      if (guard) summary = `NOT FIXED, guard:${guard} The model said: ${summary}`
      else if (claimed && !fixed) summary = `NOT FIXED: the model claimed a fix but the stage still fails. The model said: ${summary}`
      // a step with outputFormat JSON must print nothing but the JSON (the engine merges stderr into stdout): no HEAL line here, claimed prints it
      record(fixed, files, cost, summary, usage, true)
      emit('fix', {
        stage,
        attempt: n,
        model,
        effort: effort === '' ? null : effort,
        fixed,
        files,
        cost: Number(cost),
        usage,
        secs: secsSince(tStart),
        guard: guard === '' ? null : guard,
        summary: summary.slice(0, 160),
        modules: module.split(' ').filter((x) => x !== ''),
      })
      // what the next attempt reads as "Earlier attempt": the summary plus the files this one changed, so it does not explore them again
      let handoff = summary.slice(0, 300)
      if (files.length) handoff += ` Files it changed (still in the working tree): ${files.join(', ').slice(0, 300)}.`
      console.log(
        JSON.stringify({
          stage,
          model,
          effort,
          attempt: n,
          fixed,
          changed: files.length > 0,
          same,
          cost: Number(cost),
          summary: handoff,
          problems: fixed ? '' : problems,
        }),
      )
      // always 0: a step that exits non-zero has no parsed `output`, and the carry of the next attempt reads it. The workflow branches on fixed.
      return 0
    }
    default:
      console.error(`heal: unknown action '${action}'`)
      return 2
  }

  function record2(fixed: boolean, files: string[], cost: string, summary: string, m: string, e: string): void {
    const saveModel = m
    const rec = {
      stage,
      attempt: attemptNo(),
      mode: saveModel,
      effort: e === '' ? null : e,
      fixed,
      files,
      cost: Number(cost),
      summary: summary.slice(0, 160),
      usage: null,
    }
    writeFileSync(join(dir, `${stage}-${attemptNo()}.json`), JSON.stringify(rec, null, 2) + '\n')
    console.error(
      `HEAL ${stage} attempt=${attemptNo()} mode=${saveModel} fixed=${fixed} files=${files.length} cost=$${cost} summary=${summary.slice(0, 120)}`,
    )
  }
}

if (import.meta.main) process.exit(await main(process.argv.slice(2)))
