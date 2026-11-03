// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// ticketkit init ISSUE | --ticket-file F [--root R] [--state-dir D] [--base REF] [--no-worktree] [--run-id ID]
//
// Creates .yukta-dev/ticket/<run_id>/ (design section 4), branch issue/<n> and worktree ../yukta-issue-<n>.
// Prints RUN_DIR=... and WORKTREE=...; prints SKIPPED <why> and exits 3 when the run must not start (no acceptance criteria, branch or
// worktree exists): no tokens are spent. Only the acceptance criteria count as requirements; the rest of the issue is data.
import { symlinkSync } from 'node:fs'
import { type Json, KIT, die, existsSync, git, isDir, join, load, mkdirp, parseArgs, resolve, run, save, writeFileSync } from './lib.ts'

const CONVENTIONAL: Record<string, string> = { feat: 'feature', fix: 'bug', refactor: 'refactor', docs: 'docs', chore: 'chore', ci: 'chore', build: 'chore', style: 'chore', perf: 'chore', revert: 'chore', test: 'chore' }
const TYPE_LABEL: Record<string, string> = { 'type/feature': 'feature', 'type/bugfix': 'bug', 'type/refactor': 'refactor', 'type/docs': 'docs', 'type/chore': 'chore', 'type/test': 'chore' }

export function kindOf(title: string, labels: string[]): string {
  for (const l of labels) if (l in TYPE_LABEL) return TYPE_LABEL[l]!
  const m = /^(\w+)(\([^)]*\))?!?:/.exec(title)
  return m ? CONVENTIONAL[m[1]!] ?? 'feature' : 'feature'
}

/** The list items under an 'Acceptance criteria' heading, up to the next heading. */
export function criteria(body: string | null | undefined): string[] {
  const out: string[] = []
  let on = false
  for (const line of (body ?? '').split(/\r?\n/)) {
    if (/^\s{0,3}#{1,6}\s/.test(line)) { on = /acceptance criteria/i.test(line); continue }
    const m = /^\s*(?:[-*]|\d+[.)])\s+(?:\[[ xX]\]\s*)?(.+)/.exec(line)
    if (on && m) out.push(m[1]!.trim())
  }
  return out
}

const stamp = (): string => new Date().toISOString().replace(/[-:]/g, '').replace(/\.\d+Z$/, 'Z')

export function main(argv: string[]): number {
  const { pos, opt, flag } = parseArgs(argv, { value: ['ticket-file', 'root', 'state-dir', 'base', 'run-id'], bool: ['no-worktree'] })
  const issue = pos[0]
  if (Boolean(issue) === Boolean(opt['ticket-file'])) die('usage: ticketkit init ISSUE | --ticket-file F')
  const root = resolve(opt.root ?? git(['rev-parse', '--show-toplevel']).stdout.trim())
  let raw: Json
  if (opt['ticket-file']) raw = load(opt['ticket-file'])
  else {
    const r = run(['gh', 'issue', 'view', issue!, '--json', 'number,title,body,labels'])
    if (r.code) die(`gh issue view ${issue}: ${r.stderr.trim()}`)
    raw = JSON.parse(r.stdout)
  }
  const labels: string[] = (raw.labels ?? []).map((l: Json) => (typeof l === 'object' ? l.name : l))
  const project = load(join(KIT, 'project.json'))
  const ac = criteria(raw.body)
  const n = String(raw.number)
  if (!ac.length) { console.log('SKIPPED no acceptance criteria'); return 3 }
  const branch = project.git.branch.replaceAll('{n}', n)
  const wt = resolve(root, project.git.worktree.replaceAll('{n}', n))
  if (git(['rev-parse', '--verify', '-q', branch], { cwd: root, check: false }).code === 0 || existsSync(wt)) {
    console.log(`SKIPPED branch ${branch} or worktree ${wt} exists`)
    return 3
  }
  let runId: string = opt['run-id'] ?? `${n}-${stamp()}`
  const state = opt['state-dir'] ?? process.env.YUKTA_DEV_STATE_DIR ?? join(root, '.yukta-dev')
  const baseId = runId
  let k = 1
  if (opt['run-id'] && existsSync(join(state, 'ticket', runId))) die(`run ${runId} exists`)
  while (existsSync(join(state, 'ticket', runId))) runId = `${baseId}-${++k}` // two runs of one issue in the same second
  const dir = join(state, 'ticket', runId)
  for (const d of ['spec', 'steps', 'review']) mkdirp(join(dir, d))
  const modules = [...new Set(labels.filter(l => l in project.module_labels))].sort()
  save(join(dir, 'ticket.json'), {
    ticket_id: n, title: raw.title, kind: kindOf(raw.title, labels), labels, modules, acceptance_criteria: ac, body: raw.body ?? '',
    allow_guard_paths: labels.includes('build') || labels.includes('dogfood'),
  })
  save(join(dir, 'project.json'), project)
  writeFileSync(join(dir, 'events.jsonl'), '', { flag: 'a' })
  if (!flag.has('no-worktree')) {
    git(['worktree', 'add', '-q', '-b', branch, wt, opt.base ?? 'origin/main'], { cwd: root })
    const nm = join(root, 'ui', 'node_modules')
    if (isDir(nm) && !existsSync(join(wt, 'ui', 'node_modules'))) symlinkSync(nm, join(wt, 'ui', 'node_modules'))
  }
  console.log(`RUN_DIR=${dir}\nWORKTREE=${wt}\nRUN_ID=${runId}`)
  return 0
}
