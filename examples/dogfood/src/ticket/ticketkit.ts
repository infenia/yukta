#!/usr/bin/env bun
// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
//
// ticketkit <subcommand>: the deterministic tools of the ticket pipeline (design/plans/ticket-pipeline.md).
//   validate-schema NAME FILE   exit 0 when FILE is valid against schemas/NAME.schema.json
//   init ISSUE | --ticket-file F  create the run dir, branch and worktree (exit 3 and SKIPPED when it must not run)
//   route RUN_DIR               write routing.json: models, effort and tests_mode (design 3.1, 3.3)
//   validate-spec RUN_DIR       check spec/spec.json (design 8.1)
//   pack-step | scope-check | regen | free-fix | digest | event   per-step tools (src/ticket/stepkit.ts, design 5.2, 5.4, 8.2, 15.7)
//   call ROLE RUN_DIR | flow ACTION RUN_DIR   one Claude call of a role; the glue of the workflows (src/ticket/call.ts, flow.ts)
//   gate-tests RUN_DIR          after the test writer: tags, compile, red check, lint, then lock the tests (design 8.1b)
//   github | ladder | report | waves   intake and PR, the retry ladder, the cost report, step waves
import { readFileSync } from 'node:fs'

type Module = { main: (argv: string[]) => number | Promise<number> | void }
const MODULES: Record<string, () => Promise<Module>> = {
  'validate-schema': () => import('./validate-schema.ts'),
  init: () => import('./init.ts'),
  route: () => import('./route.ts'),
  'validate-spec': () => import('./validate-spec.ts'),
  call: () => import('./call.ts'),
  flow: () => import('./flow.ts'),
  'gate-tests': () => import('./gate-tests.ts'),
  github: () => import('./github.ts'),
  ladder: () => import('./ladder.ts'),
  report: () => import('./report.ts'),
  waves: () => import('./waves.ts'),
  advice: () => import('./advice.ts'),
}
const STEPKIT = new Set(['pack-step', 'scope-check', 'regen', 'free-fix', 'digest', 'event'])

export async function dispatch(cmd: string, argv: string[]): Promise<number> {
  if (STEPKIT.has(cmd)) return (await import('./stepkit.ts')).main(cmd, argv)
  const load = Object.hasOwn(MODULES, cmd) ? MODULES[cmd] : undefined
  if (!load) {
    console.error(`ticketkit: unknown subcommand '${cmd}'`)
    return 2
  }
  return ((await (await load()).main(argv)) as number | undefined) ?? 0
}

if (import.meta.main) {
  const [cmd = '', ...rest] = process.argv.slice(2)
  if (cmd === '' || cmd === '-h' || cmd === '--help') {
    console.log(
      readFileSync(import.meta.path, 'utf8')
        .split('\n')
        .slice(4, 13)
        .map((l) => l.slice(3))
        .join('\n'),
    )
    process.exit(0)
  }
  process.exit(await dispatch(cmd, rest))
}
