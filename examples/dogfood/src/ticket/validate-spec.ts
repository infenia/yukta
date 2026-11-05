// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// ticketkit validate-spec RUN_DIR [--worktree W]: design 8.1 and the sizing lint of 11.3.
//
// Reads spec/spec.json, routing.json, ticket.json and project.json from RUN_DIR; the checks that need files run in the worktree
// (default: the one in RUN_DIR/worktree, else the current directory). Prints ERROR / WARN lines, writes spec/validation.json and, when
// there is no error, spec/spec.hash. Exit 0 = valid, 1 = errors. A valid spec may raise routing.json's risk (high-risk paths).
import { createHash } from 'node:crypto'
import { isAbsolute } from 'node:path'
import {
  GUARD_PATHS,
  type Json,
  RISK,
  type Risk,
  existsSync,
  git,
  isFile,
  join,
  load,
  matches,
  maxRisk,
  modelTable,
  moduleOf,
  parseArgs,
  readFileSync,
  resolve,
  save,
  writeFileSync,
} from './lib.ts'
import { validateSchema } from './validate-schema.ts'

/** The tests modes whose tests are written (and red-checked) before any step. */
export const UP_FRONT = ['repro_only', 'cases_to_code', 'architect_code']
export const MAX_STEPS: Record<string, number> = { S: 2, M: 5, L: 8 }
const escapeRe = (s: string): string => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')

/** A placeholder is one word without shell metacharacters, so a command cannot smuggle a second command in. */
const WORD = '[^\\s;&|<>$`\'"\\\\]+'
export function templateRe(t: string): RegExp {
  // `{files}` is one or more such words (a scoped test run); any other placeholder is exactly one.
  return new RegExp(
    '^' +
      t
        .split(/(\{[a-z_]+\})/)
        .map((p) => (p === '{files}' ? `${WORD}(?: ${WORD})*` : /^\{[a-z_]+\}$/.test(p) ? WORD : escapeRe(p)))
        .join('') +
      '$',
  )
}

/** A command is a project template, or a chain of them joined by ` && ` (each link is checked on its own). */
export const isProjectCommand = (templates: RegExp[], c: string): boolean => c.split(' && ').every((p) => templates.some((t) => t.test(p)))

/** `lines` is one or more comma-separated ranges, `10-80` or a single line `79`, all within 1..n. */
export const linesOk = (lines: string, n: number): boolean =>
  lines.split(',').every((part) => {
    const m = /^(\d+)(?:-(\d+))?$/.exec(part.trim())
    return !!m && Number(m[1]) >= 1 && Number(m[1]) <= Number(m[2] ?? m[1]) && Number(m[2] ?? m[1]) <= n
  })

/** An error from a project policy (guard paths, files agents may not edit), not from how the spec is written. */
export const isPolicyConflict = (e: string): boolean => /is a guard path|may not be edited by an agent/.test(e)

export function patchFiles(text: string): Set<string> {
  const out = new Set<string>()
  for (const m of text.matchAll(/^\+\+\+ b\/(\S+)/gm)) out.add(m[1]!)
  for (const m of text.matchAll(/^--- a\/(\S+)/gm)) out.add(m[1]!)
  return out
}

function gradleDeps(wt: string, module: string): Set<string> {
  const f = join(wt, module, 'build.gradle.kts')
  return isFile(f) ? new Set([...readFileSync(f, 'utf8').matchAll(/project\("(:[^"]+)"\)/g)].map((m) => m[1]!)) : new Set()
}

const subset = (a: Set<string>, b: Set<string>): boolean => [...a].every((x) => b.has(x))
const lineCount = (text: string): number => (text.length ? text.replace(/\r?\n$/, '').split(/\r?\n/).length : 0)

export function validate(
  runDir: string,
  worktree?: string,
  draft?: string,
): { errors: string[]; warnings: string[]; ok: [Json, Json] | null } {
  const errors: string[] = []
  const warnings: string[] = []
  const err = (m: string): void => void errors.push(m)
  const warn = (m: string): void => void warnings.push(m)
  const wt = resolve(worktree ?? (existsSync(join(runDir, 'worktree')) ? join(runDir, 'worktree') : '.'))
  const specFile = draft ? resolve(draft) : join(runDir, 'spec', 'spec.json')
  const routing = load(join(runDir, 'routing.json'))
  const ticket = load(join(runDir, 'ticket.json'))
  const project = load(join(runDir, 'project.json'))
  // 1 schema
  const r = validateSchema('ticket-spec', specFile)
  if (r.code) {
    for (const line of r.lines) err('schema ' + line)
    return { errors, warnings, ok: null } // nothing below is safe on a spec that does not parse
  }
  const spec = load(specFile)
  if (spec.ticket_id !== ticket.ticket_id) err(`ticket_id ${spec.ticket_id} is not the ticket's ${ticket.ticket_id}`)
  const steps: Record<string, Json> = Object.fromEntries(spec.steps.map((s: Json) => [s.id, s]))
  // 2 graph
  if (Object.keys(steps).length !== spec.steps.length) err('duplicate step ids')
  for (const s of spec.steps) for (const d of s.depends_on) if (!(d in steps)) err(`${s.id} depends on unknown ${d}`)
  const deps: Record<string, string[]> = Object.fromEntries(
    Object.entries(steps).map(([i, s]) => [i, (s.depends_on as string[]).filter((d) => d in steps)]),
  )
  const anc: Record<string, Set<string>> = {}
  const ancestors = (i: string, seen: string[] = []): Set<string> => {
    if (anc[i]) return anc[i]!
    if (seen.includes(i)) {
      err(`dependency cycle through ${i}`)
      return new Set()
    }
    const out = new Set<string>()
    for (const d of deps[i]!) {
      out.add(d)
      for (const x of ancestors(d, [...seen, i])) out.add(x)
    }
    anc[i] = out
    return out
  }
  for (const i of Object.keys(steps)) ancestors(i)
  // scaffold
  const sp = isAbsolute(spec.scaffold_patch) ? spec.scaffold_patch : join(runDir, spec.scaffold_patch)
  let scaffoldFiles = new Set<string>()
  if (draft && !spec.scaffold_patch) {
    /* a draft without stubs: call.ts names an empty patch */
  } else if (!isFile(sp)) err(`scaffold_patch ${spec.scaffold_patch} does not exist`)
  else {
    const text = readFileSync(sp, 'utf8')
    scaffoldFiles = patchFiles(text)
    if (text.trim() && git(['apply', '--check', sp], { cwd: wt, check: false }).code)
      err('scaffold_patch does not apply cleanly to the checkout')
  }
  const sc = spec.scope
  const locked = new Set<string>(spec.locked_files)
  const allowArch = spec.tests_mode === 'architect_code'
  // the test gate only accepts files named here; an empty list makes every written test a scope_violation
  if (!locked.size && UP_FRONT.includes(spec.tests_mode))
    err(`locked_files is empty but tests_mode ${spec.tests_mode} writes tests up front: name the test file paths`)
  const allWrites = new Set<string>(spec.steps.flatMap((s: Json) => s.write_files))
  // 6 scaffold touches only stubs
  for (const f of [...scaffoldFiles].sort()) {
    if (locked.has(f) && !allowArch) err(`scaffold touches locked file ${f}`)
    else if (!locked.has(f) && !allWrites.has(f)) err(`scaffold touches ${f}, which no step writes (stubs only)`)
  }
  const guardOk = ticket.allow_guard_paths ?? false
  let risk: Risk = maxRisk(spec.risk, routing.risk)
  const templates = Object.values<string>(project.commands).map(templateRe)
  const testTemplates = ['test_class', 'go_test', 'ui_test', 'ui_test_files']
    .filter((k) => k in project.commands)
    .map((k) => templateRe(project.commands[k]))
  // 3 per step
  for (const s of spec.steps) {
    const i: string = s.id
    for (const f of s.write_files as string[]) {
      if (!matches(f, sc.allowed_globs)) err(`${i}: write_files ${f} is outside scope.allowed_globs`)
      if (matches(f, sc.forbidden_globs)) err(`${i}: write_files ${f} is in forbidden_globs`)
      if (locked.has(f)) err(`${i}: write_files ${f} is a locked file`)
      if (matches(f, GUARD_PATHS) && !guardOk)
        err(`${i}: scope_violation: ${f} is a guard path and the issue is not about build or dogfood`)
      if (matches(f, project.denied_write)) err(`${i}: write_files ${f} may not be edited by an agent`)
      if (matches(f, project.high_risk_paths)) risk = 'high'
    }
    if (s.write_files.length > s.limits.max_files)
      err(`${i}: ${s.write_files.length} write_files exceed limits.max_files ${s.limits.max_files}`)
    const created = new Set<string>([...scaffoldFiles, ...[...ancestors(i)].flatMap((d) => steps[d]!.write_files as string[])])
    for (const rf of s.read_files as Json[]) {
      const path = join(wt, rf.path)
      if (!isFile(path) && !created.has(rf.path)) err(`${i}: read_files ${rf.path} does not exist and nothing creates it`)
      else if (rf.lines && isFile(path)) {
        const n = lineCount(readFileSync(path, 'utf8'))
        if (!linesOk(rf.lines, n)) err(`${i}: read_files ${rf.path} lines ${rf.lines} not within 1-${n}`)
      }
    }
    // 4 commands
    for (const c of s.done_when.commands as string[])
      if (!isProjectCommand(templates, c)) err(`${i}: done_when command is not a project command template: ${c}`)
    const mods = [...new Set((s.write_files as string[]).map(moduleOf))]
    if (mods.length > 1)
      err(
        `${i}: step touches more than one module: [${mods
          .sort()
          .map((m) => `'${m}'`)
          .join(', ')}]`,
      )
    if (
      !s.covers.length &&
      !(s.done_when.commands as string[]).some((c) => c.split(' && ').some((p) => testTemplates.some((t) => t.test(p))))
    )
      err(`${i}: not independently verifiable: no covers and no test command in done_when`)
    if (!(s.limits.max_diff_lines >= 30 && s.limits.max_diff_lines <= 150))
      warn(`${i}: max_diff_lines ${s.limits.max_diff_lines} is outside the 30-150 target`)
    for (const d of s.depends_on as string[]) {
      if (d in steps && subset(new Set(s.write_files), new Set(steps[d]!.write_files)) && s.limits.max_diff_lines < 20)
        err(`${i}: tiny step in the same files as ${d}: merge it into ${d}`)
    }
  }
  // 5 acceptance criteria
  for (const c of spec.acceptance_criteria) {
    const v = c.verification
    if (v.kind === 'command' && (!v.command || !isProjectCommand(templates, v.command)))
      err(`${c.id}: command is missing or not a project command template`)
  }
  // 7 risk override (a write_file in high_risk_paths raised `risk` above)
  if (RISK.indexOf(risk) > RISK.indexOf(routing.risk)) {
    Object.assign(routing, {
      risk,
      allow_architect_code: true,
      models: modelTable(routing.complexity, risk, routing.tier_mode, project.implementer_override),
      tests_mode: routing.tests_mode,
    })
    warn(`risk raised to ${risk} (spec or high-risk paths); routing.json updated`)
  }
  // 8 tests
  if (spec.tests_mode !== routing.tests_mode) {
    if (!(allowArch && routing.allow_architect_code && spec.test_cases.some((c: Json) => c.critical)))
      err(`tests_mode ${spec.tests_mode} differs from routing.tests_mode ${routing.tests_mode}`)
  }
  const cases: string[] = spec.test_cases.map((c: Json) => c.id)
  if (cases.length !== new Set(cases).size) err('duplicate test case ids')
  if (cases.length > project.tests.max_cases_total)
    err(`${cases.length} test cases exceed max_cases_total ${project.tests.max_cases_total}`)
  const covered = new Set<string>(spec.steps.flatMap((s: Json) => s.covers))
  for (const c of cases.filter((c) => !covered.has(c)).sort()) err(`test case ${c} is covered by no step`)
  for (const c of [...covered].filter((c) => !cases.includes(c)).sort()) err(`a step covers unknown test case ${c}`)
  // 9 parallel safety
  const ids = Object.keys(steps)
  for (let x = 0; x < ids.length; x++) {
    for (let y = x + 1; y < ids.length; y++) {
      const a = ids[x]!
      const b = ids[y]!
      if (anc[b]!.has(a) || anc[a]!.has(b)) continue
      const both = (steps[a]!.write_files as string[]).filter((f) => (steps[b]!.write_files as string[]).includes(f))
      if (both.length)
        err(
          `${a} and ${b} are not ordered but both write [${[...new Set(both)]
            .sort()
            .map((f) => `'${f}'`)
            .join(', ')}]`,
        )
    }
  }
  // 10 sizing, module order
  if (ids.length > MAX_STEPS[routing.complexity]!)
    err(`${ids.length} steps exceed the ${MAX_STEPS[routing.complexity]} allowed for complexity ${routing.complexity}`)
  const gm = (s: Json): string => (s.write_files.length ? ':' + moduleOf(s.write_files[0]).split('/').join(':') : '')
  for (const [i, s] of Object.entries(steps)) {
    for (const d of anc[i]!) {
      if (gm(s) && gm(steps[d]!) && gm(steps[d]!) !== gm(s) && gradleDeps(wt, gm(steps[d]!).slice(1).replaceAll(':', '/')).has(gm(s))) {
        err(`${i} (${gm(s)}) runs after ${d} (${gm(steps[d]!)}), which depends on it: order the provider first`)
      }
    }
  }
  return { errors, warnings, ok: [spec, routing] }
}

export function main(argv: string[]): number {
  const { pos, opt } = parseArgs(argv, { value: ['worktree', 'spec'] })
  const runDir = pos[0]!
  const { errors, warnings, ok } = validate(runDir, opt.worktree, opt.spec)
  for (const e of errors) console.log('ERROR ' + e)
  for (const w of warnings) console.log('WARN ' + w)
  if (opt.spec) {
    // --spec FILE: the architect checks a draft; nothing is written
    console.log(`RESULT validate-spec errors=${errors.length} warnings=${warnings.length}`)
    return errors.length ? 1 : 0
  }
  save(join(runDir, 'spec', 'validation.json'), { errors, warnings })
  // a policy the ticket itself needs to break is no error another architect attempt can fix: it becomes a question to a person (ladder clarification)
  const policy = errors.filter(isPolicyConflict)
  if (policy.length)
    save(join(runDir, 'spec', 'needs-clarification.json'), {
      status: 'NEEDS_CLARIFICATION',
      questions: [
        {
          id: 'Q1',
          question: `The spec has to write files project policy forbids: ${policy.join('; ')}`.slice(0, 300),
          why_blocking: 'Name another path for it, or reject the run: the pipeline will not write there.',
        },
      ],
    })
  if (ok && !errors.length) {
    save(join(runDir, 'routing.json'), ok[1])
    writeFileSync(
      join(runDir, 'spec', 'spec.hash'),
      'sha256:' +
        createHash('sha256')
          .update(readFileSync(join(runDir, 'spec', 'spec.json')))
          .digest('hex') +
        '\n',
    )
  }
  console.log(`RESULT validate-spec errors=${errors.length} warnings=${warnings.length}`)
  return errors.length ? 1 : 0
}
