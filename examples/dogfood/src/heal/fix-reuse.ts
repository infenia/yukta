// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// The free fix of the `reuse` stage: gives every file that `reuse lint` reports without licensing information the project's header
// (Apache-2.0, Infenia Private Limited). A file type reuse cannot comment gets a sidecar `<file>.license`. Run in the repository root.
import { run, tryJson } from '../lib.ts'

export function main(): number {
  const year = process.env.YUKTA_YEAR ?? String(new Date().getFullYear())
  const lint = tryJson(run(['reuse', 'lint', '--json']).stdout)
  const files = [
    ...new Set<string>([...(lint?.non_compliant?.missing_licensing_info ?? []), ...(lint?.non_compliant?.missing_copyright_info ?? [])]),
  ].sort()
  if (!files.length) return 0
  const r = run([
    'reuse',
    'annotate',
    '--license',
    'Apache-2.0',
    '--copyright',
    'Infenia Private Limited',
    '--year',
    year,
    '--merge-copyrights',
    '--fallback-dot-license',
    '--',
    ...files,
  ])
  process.stdout.write(r.stdout)
  process.stderr.write(r.stderr)
  return r.code
}

if (import.meta.main) process.exit(main())
