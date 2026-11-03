// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// advise [--base REF] [--no-model]: recommends a test tier (CLAUDE.md "Test Execution Policy") for the current changes and runs nothing.
//
// The floor is deterministic, from the changed paths. The decision model (decisions.json) sees only a digest, one line per file
// (path, lines added and removed, kind of file; never a diff) and says what kind of change it is. Its answer can only WIDEN the tier:
//   public-contract -> at least 2, build-or-config -> at least 3, unsure (below minProbability) -> one tier more than the floor.
// No model, an error or an oversized digest: the floor stands. docs-or-comment-only / test-only / internal-change never narrow it.
//
//   tier 0 compile  1 tests of the changed classes  2 tests of the package  3 whole module  4 full check
import { REPO_ROOT, decide, git } from './lib.ts'

const lines = (s: string): string[] => (s.length ? s.replace(/\n$/, '').split('\n') : [])
const CRIT = {
  'docs-or-comment-only': 'Only documentation or comments changed; no behaviour.',
  'test-only': 'Only tests changed.',
  'internal-change': 'Code changed behind existing signatures: private or package-private members, a local rename, an implementation.',
  'public-contract': 'A public type, method signature, REST or MCP schema, configuration key or serialized format changed, so dependants can break.',
  'build-or-config': 'The build, dependencies, CI or configuration changed.',
}

export async function main(argv: string[]): Promise<number> {
  let base = 'origin/main'
  let model = true
  const min = Number(process.env.YUKTA_ADVISE_MIN_PROBABILITY ?? '0.6')
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--base') { base = argv[++i] ?? ''; if (!base) { console.error('--base needs a ref'); return 2 } }
    else if (argv[i] === '--no-model') model = false
    else { console.error('usage: advise [--base REF] [--no-model]'); return 2 }
  }
  const g = (a: string[]): string => git(a, REPO_ROOT).stdout
  const m = git(['merge-base', 'HEAD', base], REPO_ROOT)
  if (m.code) { console.error(`advise: no merge base with ${base}`); return 1 }
  const mb = m.stdout.trim()
  const files = [...new Set([...lines(g(['diff', '--name-only', mb])), ...lines(g(['ls-files', '--others', '--exclude-standard']))])].sort()
  if (!files.length) { console.log('advice: nothing changed, nothing to check'); return 0 }

  // ---- the floor, and the digest
  let tier = -1
  let digest = ''
  const stat = new Map<string, string>()
  for (const l of lines(g(['diff', '--numstat', mb])).filter(l => !l.startsWith('-'))) { const [a, d, f] = l.split('\t'); stat.set(f!, `+${a} -${d}`) }
  for (const f of files) {
    let k = 'code', t = 1
    if (/^(build-logic\/|gradle\/|config\/)|^(settings\.gradle\.kts|build\.gradle\.kts|gradle\.properties)$/.test(f)) { k = 'build'; t = 4 }
    else if (/^(plugin-api|messaging)\//.test(f)) { k = 'shared'; t = 3 }
    else if (f.endsWith('.md') || f.startsWith('docs/')) { k = 'docs'; t = 0 }
    else if (f.includes('/src/test/') || f.startsWith('ui/src/test/')) { k = 'test'; t = 1 }
    if (t > tier) tier = t
    digest += `${f} ${stat.get(f) ?? '+new'} ${k}\n`
  }
  if (tier >= 1 && tier < 3 && files.length > 15) tier = 3
  else if (tier >= 1 && tier < 2 && files.length > 5) tier = 2
  const floor = tier
  let why = 'paths'

  // ---- the model can only widen
  if (model) {
    const ans = await decide('advise', `${files.length} changed files\n${digest}`.slice(0, 900), 'What kind of change is this?', CRIT)
    if (ans) {
      const [label, p] = ans
      why = `model: ${label} (${p})`
      if (Number(p) < min) { tier += 1; why += ', unsure: one tier more' }
      else if (label === 'public-contract' && tier < 2) tier = 2
      else if (label === 'build-or-config' && tier < 3) tier = 3
    } else why = 'no model answer, the floor stands'
  }
  if (tier > 4) tier = 4
  console.log(`advice: tier ${tier} (floor ${floor} from ${files.length} changed files; ${why}). Advice only, it never narrows the floor; --no-model gives the floor alone.`)
  return 0
}

if (import.meta.main) process.exit(await main(process.argv.slice(2)))
