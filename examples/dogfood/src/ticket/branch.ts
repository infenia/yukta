// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// The git steps the session (the orchestrator of an issue) may call itself, so it never has to edit or run git by hand:
//
//   yk ticket worktree ISSUE [--branch B] [--base REF] [--in DIR]
//       a worktree for the issue: ../yukta-issue-<n> on branch issue/<n> (or B). Reuses what exists: the worktree is already there on the branch
//       (printed as is); the branch exists locally or on origin (the worktree is attached to it); else it is cut from --base (default origin/main).
//       --in DIR: no new worktree, DIR (an existing worktree, clean) switches to the branch instead.
//       A worktree that was released (below) is taken over first, moved to ../yukta-issue-<n>, so worktrees do not pile up.
//   yk ticket release ISSUE|--worktree W
//       frees a worktree whose branch is pushed: detached at origin/main and marked free for the next ticket. Refuses a dirty tree or unpushed commits.
//   yk ticket pr ISSUE [--worktree W] [--title T] [--result "RESULT line"] [--keep]
//       pushes the branch of the worktree and opens a DRAFT PR with `Fixes #n`; an open PR of the branch is printed, not duplicated.
//       Releases the worktree afterwards (--keep leaves it); `yk ticket worktree <n>` brings the branch back for review fixes.
//       Refuses main/master, a dirty tree, a branch with no commit of its own and a title that is not a Conventional Commit. Never merges.
import { appendFileSync, existsSync, lstatSync, readFileSync, rmSync, symlinkSync, writeFileSync } from 'node:fs'
import { dirname } from 'node:path'
import { CONVENTIONAL } from './github.ts'
import { die, git, isDir, join, load, parseArgs, resolve, run, KIT } from './lib.ts'

const REPO = process.env.YUKTA_PICKUP_REPO ?? 'infenia/yukta'
const root = (): string => resolve(git(['rev-parse', '--path-format=absolute', '--git-common-dir']).stdout.trim(), '..')
const paths = (n: string): { branch: string; wt: string } => {
  const p = load(join(KIT, 'project.json')).git
  return { branch: p.branch.replaceAll('{n}', n), wt: resolve(root(), p.worktree.replaceAll('{n}', n)) }
}
const ok = (args: string[], cwd: string): boolean => git(args, { cwd, check: false }).code === 0
const current = (cwd: string): string => git(['rev-parse', '--abbrev-ref', 'HEAD'], { cwd, check: false }).stdout.trim()
const issueNo = (s: string | undefined): string => (s && /^\d{1,8}$/.test(s) ? s : die('need an issue number'))

const marker = (wt: string): string => join(git(['rev-parse', '--absolute-git-dir'], { cwd: wt }).stdout.trim(), 'yukta-released')

/** Frees a worktree for the next ticket: its branch must be pushed and the tree clean. Detached at origin/main, marked; the files that git ignores (build output, node_modules) stay. */
export function release(wt: string): string | null {
  const branch = current(wt)
  if (git(['status', '--porcelain'], { cwd: wt }).stdout.trim()) return `${wt} has uncommitted changes`
  if (branch !== 'HEAD') {
    if (!ok(['rev-parse', '--verify', '-q', `refs/remotes/origin/${branch}`], wt)) return `branch ${branch} is not pushed`
    if (Number(git(['rev-list', '--count', `origin/${branch}..HEAD`], { cwd: wt }).stdout))
      return `branch ${branch} has commits that are not pushed`
  }
  git(['switch', '-q', '--detach', 'origin/main'], { cwd: wt })
  writeFileSync(marker(wt), '')
  rmSync(continueMark(wt), { force: true })
  return null
}

export const isReleased = (wt: string): boolean => existsSync(wt) && existsSync(marker(wt))

/** `yk ticket worktree` marks the worktree so the next `yk ticket run` continues its branch (commits kept) instead of refusing it. One run only. */
const continueMark = (wt: string): string => join(git(['rev-parse', '--absolute-git-dir'], { cwd: wt }).stdout.trim(), 'yukta-continue')

/** Consumes the mark: null when `wt` may be continued on `branch`, else why not. The branch must be cut directly from `main` (its fork point on
 * main's first-parent line, no merge commits) and have a commit of its own. */
export function takeContinue(wt: string, branch: string, main = 'origin/main'): string | null {
  if (!existsSync(wt) || !existsSync(continueMark(wt))) return `run 'yk ticket worktree' first to continue it`
  if (current(wt) !== branch) return `${wt} is on ${current(wt) || '?'}, not ${branch}`
  if (git(['status', '--porcelain'], { cwd: wt }).stdout.trim()) return `${wt} has uncommitted changes`
  const count = (...a: string[]): number => Number(git(['rev-list', '--count', ...a, `${main}..HEAD`], { cwd: wt }).stdout)
  if (!count()) return `${branch} has no commit of its own on ${main}`
  if (count('--merges')) return `${branch} has merge commits, not a direct branch of ${main}`
  const fork = git(['merge-base', main, 'HEAD'], { cwd: wt }).stdout.trim()
  // ponytail: a branch stacked on another unmerged branch passes too (git cannot tell its commits apart); reflog would, but it is not always kept
  if (!git(['rev-list', '--first-parent', main], { cwd: wt }).stdout.split('\n').includes(fork))
    return `${branch} does not branch off ${main}'s own history`
  rmSync(continueMark(wt))
  return null
}

/** After a failed run: a branch with no commits of its own holds only the run's debris, so its diff goes to RUN/leftover.patch and the worktree is
 * released (the branch deleted) for the next run of the issue. A branch with commits is left for a person. Returns what it did. */
export function releaseFailed(wt: string, run: string): string {
  if (!existsSync(wt)) return 'no worktree'
  const branch = current(wt)
  if (branch === 'HEAD') return 'worktree is detached, left as it is'
  if (ok(['rev-parse', '--verify', '-q', `refs/remotes/origin/${branch}`], wt)) return `branch ${branch} is pushed, left for you`
  if (Number(git(['rev-list', '--count', 'origin/main..HEAD'], { cwd: wt }).stdout)) return `branch ${branch} has commits, left for you`
  git(['add', '-A', '-N'], { cwd: wt })
  writeFileSync(join(run, 'leftover.patch'), git(['diff', '--binary', 'HEAD'], { cwd: wt }).stdout)
  git(['reset', '-q', '--hard'], { cwd: wt })
  git(['clean', '-q', '-fd', '-e', 'node_modules'], { cwd: wt })
  git(['switch', '-q', '--detach', 'origin/main'], { cwd: wt })
  git(['branch', '-q', '-D', branch], { cwd: wt })
  writeFileSync(marker(wt), '')
  return `released (the run's uncommitted changes are in ${join(run, 'leftover.patch')})`
}

/** Puts `branch` on a worktree at `wt`: a released one is moved there and switched, else a new one is added. `start` is the commit for a new branch. */
export function attach(R: string, wt: string, branch: string, start: string, exists: boolean): void {
  const free = git(['worktree', 'list', '--porcelain'], { cwd: R })
    .stdout.split('\n\n')
    .map((b) => /^worktree (.+)$/m.exec(b)?.[1])
    .find((p) => p && p !== R && existsSync(p) && existsSync(marker(p)) && !git(['status', '--porcelain'], { cwd: p }).stdout.trim())
  if (free) {
    if (free !== wt) git(['worktree', 'move', free, wt], { cwd: R })
    git(exists ? ['switch', '-q', branch] : ['switch', '-q', '-c', branch, start], { cwd: wt })
    rmSync(marker(wt), { force: true })
  } else git(exists ? ['worktree', 'add', '-q', wt, branch] : ['worktree', 'add', '-q', '-b', branch, wt, start], { cwd: R })
  // the JS toolchains of R, else of the main checkout (R can be a worktree without them): without them the UI tests and the dogfood type check
  // of the run cannot start (git ignores node_modules, so a new worktree has none)
  const main = dirname(git(['rev-parse', '--path-format=absolute', '--git-common-dir'], { cwd: R }).stdout.trim())
  for (const dir of NODE_DIRS) {
    const nm = [join(R, dir, 'node_modules'), join(main, dir, 'node_modules')].find(isDir)
    const link = join(wt, dir, 'node_modules')
    if (nm && isDir(join(wt, dir)) && !existsSync(link)) symlinkSync(nm, link)
    if (existsSync(link) && lstatSync(link).isSymbolicLink()) ignoreLink(wt, `/${dir}/node_modules`) // also a link a released worktree kept
  }
}

/** `node_modules/` in .gitignore matches directories only, so git sees a symlinked node_modules as an untracked file (a scope violation, and
 * `git add -A` would commit it): the link goes into the shared info/exclude, as Claude Code does for its own worktree links. */
function ignoreLink(wt: string, line: string): void {
  const ex = resolve(wt, git(['rev-parse', '--git-path', 'info/exclude'], { cwd: wt }).stdout.trim())
  const cur = existsSync(ex) ? readFileSync(ex, 'utf8') : ''
  if (!cur.split('\n').includes(line)) appendFileSync(ex, (cur && !cur.endsWith('\n') ? '\n' : '') + line + '\n')
}

/** The folders whose node_modules the stages of yk check use (ui: vitest, tsc, eslint; dogfood: bun check of the scripts and the channel). */
export const NODE_DIRS = ['ui', 'examples/dogfood', 'examples/dogfood/channel']

export function worktree(argv: string[]): number {
  const { pos, opt } = parseArgs(argv, { value: ['branch', 'base', 'in'] })
  const n = issueNo(pos[0])
  const d = paths(n)
  const branch = opt.branch ?? d.branch
  const base = opt.base ?? 'origin/main'
  const R = root()
  if (/^(main|master)$/.test(branch)) die(`refusing branch ${branch}`)
  git(['fetch', '-q', 'origin'], { cwd: R, check: false })
  const local = ok(['rev-parse', '--verify', '-q', `refs/heads/${branch}`], R)
  const remote = ok(['rev-parse', '--verify', '-q', `refs/remotes/origin/${branch}`], R)
  let wt = d.wt
  if (opt.in) {
    wt = resolve(opt.in)
    if (!git(['worktree', 'list', '--porcelain'], { cwd: R }).stdout.includes(`worktree ${wt}\n`))
      die(`${wt} is not a worktree of this repository`)
    if (git(['status', '--porcelain'], { cwd: wt }).stdout.trim()) die(`${wt} has uncommitted changes; commit or stash them first`)
    rmSync(marker(wt), { force: true })
    if (current(wt) !== branch)
      git(
        local
          ? ['switch', '-q', branch]
          : remote
            ? ['switch', '-q', '-c', branch, `origin/${branch}`]
            : ['switch', '-q', '-c', branch, base],
        { cwd: wt },
      )
  } else if (existsSync(wt) && !existsSync(marker(wt))) {
    // a released worktree at the path is taken over below
    if (current(wt) !== branch) die(`${wt} exists on branch ${current(wt) || '?'}, not ${branch}; use --in ${wt} to switch it`)
  } else {
    attach(R, wt, branch, remote ? `origin/${branch}` : base, local)
  }
  writeFileSync(continueMark(wt), '') // the next `yk ticket run` continues this branch (init.ts)
  console.log(`WORKTREE=${wt}\nBRANCH=${branch}`)
  return 0
}

export function releaseCmd(argv: string[]): number {
  const { pos, opt } = parseArgs(argv, { value: ['worktree'] })
  const wt = resolve(opt.worktree ?? paths(issueNo(pos[0])).wt)
  if (!existsSync(wt)) die(`no worktree ${wt}`)
  const why = release(wt)
  if (why) die(`not released: ${why}`)
  console.log(`RELEASED=${wt}`)
  return 0
}

export function pr(argv: string[]): number {
  const { pos, opt, flag } = parseArgs(argv, { value: ['worktree', 'title', 'result'], bool: ['keep'] })
  const n = issueNo(pos[0])
  const wt = resolve(opt.worktree ?? paths(n).wt)
  if (!existsSync(wt)) die(`no worktree ${wt}; create it with yk ticket worktree ${n}`)
  const branch = current(wt)
  if (!branch || branch === 'HEAD' || /^(main|master)$/.test(branch)) die(`refusing to open a PR from '${branch || 'a detached HEAD'}'`)
  if (git(['status', '--porcelain'], { cwd: wt }).stdout.trim()) die(`${wt} has uncommitted changes; commit them first`)
  const have = run(['gh', 'pr', 'list', '-R', REPO, '--head', branch, '--state', 'open', '--json', 'url', '-q', '.[0].url'], { cwd: wt })
  if (have.code) die(`gh pr list: ${have.stderr.trim()}`)
  if (have.stdout.trim()) {
    console.log(`PR_URL=${have.stdout.trim()}\nEXISTING=1`)
    return 0
  }
  if (!Number(git(['rev-list', '--count', 'origin/main..HEAD'], { cwd: wt }).stdout))
    die(`branch ${branch} has no commit of its own on origin/main`)
  let title = opt.title
  if (!title) {
    const v = run(['gh', 'issue', 'view', n, '-R', REPO, '--json', 'title', '-q', '.title'])
    if (v.code) die(`gh issue view ${n}: ${v.stderr.trim()}`)
    title = v.stdout.trim()
  }
  if (!CONVENTIONAL.test(title)) die(`title '${title}' is not a Conventional Commit; pass --title 'feat: ...'`)
  git(['push', '-q', '-u', 'origin', branch], { cwd: wt })
  const body = `Fixes #${n}\n\n${opt.result ? `${opt.result}\n\n` : ''}🤖 Generated with [Claude Code](https://claude.com/claude-code)\n`
  const c = run(['gh', 'pr', 'create', '-R', REPO, '--draft', '--head', branch, '--base', 'main', '--title', title, '--body', body], {
    cwd: wt,
  })
  if (c.code) die(`gh pr create: ${c.stderr.trim()}`)
  console.log(`PR_URL=${c.stdout.trim().split('\n').pop()}`)
  if (!flag.has('keep')) console.log(release(wt) ? `KEPT=${wt}` : `RELEASED=${wt}`)
  return 0
}
