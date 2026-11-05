// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// The step of the `tool` workflow: runs the shell line in $YUKTA_TOOL_COMMAND (the command of `yk gradle`, `yk tool` and every stage of `yk check`)
// in the working directory of the node, its output going straight through. Nothing to run (an empty command or "-") is a pass.
import { spawnSync } from 'node:child_process'

export function main(): number {
  const cmd = process.env.YUKTA_TOOL_COMMAND ?? ''
  if (cmd === '' || cmd === '-') {
    console.log(`tool ${process.env.YUKTA_TOOL_LABEL ?? ''}: nothing to run`)
    return 0
  }
  const r = spawnSync('sh', ['-c', 'eval "$YUKTA_TOOL_COMMAND"'], { stdio: 'inherit' })
  return r.status ?? (r.signal ? 128 : 1)
}

if (import.meta.main) process.exit(main())
