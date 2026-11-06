// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// `yk ticket worktree` and `yk ticket pr` (src/ticket/branch.ts): offline, on a throwaway repo with a bare origin.
import { afterAll, beforeAll, expect, test } from 'bun:test'
import { existsSync } from 'node:fs'
import { join } from 'node:path'
import { KIT, git, initRepo, put, rm, sh, tmp } from './helpers.ts'

const d = tmp()
const repo = join(d, 'repo')
const cmd = (fn: 'worktree' | 'pr' | 'releaseCmd', ...args: string[]) =>
  sh(
    [
      process.execPath,
      '-e',
      `process.exit(await import(${JSON.stringify(join(KIT, 'src/ticket/branch.ts'))}).then(m => m.${fn}(process.argv.slice(1))))`,
      ...args,
    ],
    { cwd: repo },
  )
beforeAll(() => {
  initRepo(repo, { 'a.txt': 'a\n' })
  sh(['git', 'init', '-q', '--bare', join(d, 'remote.git')])
  git(repo, 'remote', 'add', 'origin', join(d, 'remote.git'))
  git(repo, 'push', '-q', 'origin', 'main')
})
afterAll(() => rm(d))

test('worktree: new branch, then reuse of the worktree, of a local branch and of a remote branch', () => {
  const a = cmd('worktree', '7')
  expect(a.code, a.all).toBe(0)
  expect(a.out).toContain('BRANCH=issue/7')
  expect(existsSync(join(d, 'yukta-issue-7'))).toBe(true)
  expect(cmd('worktree', '7').code, 'idempotent').toBe(0)
  git(repo, 'worktree', 'remove', '--force', join(d, 'yukta-issue-7'))
  expect(cmd('worktree', '7').code, 'attaches to the local branch').toBe(0)
  git(repo, 'worktree', 'remove', '--force', join(d, 'yukta-issue-7'))
  git(repo, 'push', '-q', 'origin', 'issue/7')
  git(repo, 'branch', '-q', '-D', 'issue/7')
  expect(cmd('worktree', '7').code, 'attaches to origin/issue/7').toBe(0)
  expect(git(join(d, 'yukta-issue-7'), 'rev-parse', '--abbrev-ref', 'HEAD').out.trim()).toBe('issue/7')
})

test('worktree --in switches an existing clean worktree; refuses main and a dirty tree', () => {
  const wt = join(d, 'yukta-issue-7')
  expect(cmd('worktree', '8', '--in', wt).code).toBe(0)
  expect(git(wt, 'rev-parse', '--abbrev-ref', 'HEAD').out.trim()).toBe('issue/8')
  put(join(wt, 'dirty.txt'), 'x')
  expect(cmd('worktree', '7', '--in', wt).err).toContain('uncommitted')
  expect(cmd('worktree', '7', '--branch', 'main').err).toContain('refusing')
  expect(cmd('worktree', '9', '--in', join(d, 'nowhere')).err).toContain('not a worktree')
})

test('pr refuses a dirty tree and a branch with no commit of its own', () => {
  const wt = join(d, 'yukta-issue-7')
  expect(cmd('pr', '7', '--worktree', wt).err).toContain('uncommitted')
  rm(join(wt, 'dirty.txt'))
})

test('release after a push frees the worktree; the next ticket takes it over under its own name', () => {
  const wt7 = join(d, 'yukta-issue-7')
  git(wt7, 'switch', '-q', 'issue/7')
  put(join(wt7, 'f.txt'), 'f')
  git(wt7, 'add', '-A')
  git(wt7, 'commit', '-q', '-m', 'feat: f')
  expect(cmd('releaseCmd', '7').err, 'unpushed commit').toContain('not pushed')
  git(wt7, 'push', '-q', 'origin', 'issue/7')
  expect(cmd('releaseCmd', '7').out).toContain('RELEASED=')
  expect(git(wt7, 'rev-parse', '--abbrev-ref', 'HEAD').out.trim()).toBe('HEAD')
  const r = cmd('worktree', '9')
  expect(r.code, r.all).toBe(0)
  expect(existsSync(wt7), 'moved, not copied').toBe(false)
  expect(git(join(d, 'yukta-issue-9'), 'rev-parse', '--abbrev-ref', 'HEAD').out.trim()).toBe('issue/9')
  expect(
    git(repo, 'worktree', 'list')
      .out.split('\n')
      .filter((l) => l.includes('yukta-issue')),
    'no extra worktree',
  ).toHaveLength(1)
})

test('releaseFailed: a failed run without commits frees its worktree (diff kept); a branch with commits stays', async () => {
  const { releaseFailed, isReleased, attach } = await import('../../src/ticket/branch.ts')
  const wt = join(d, 'yukta-issue-11')
  const run = join(d, 'run11')
  put(join(run, 'x'), '')
  git(repo, 'fetch', '-q', 'origin')
  put(join(repo, 'ui/node_modules/x/package.json'), '{}') // the main checkout's toolchain (git ignores it)
  put(join(repo, 'ui/.keep'), '')
  git(repo, 'add', 'ui/.keep')
  git(repo, 'commit', '-q', '-m', 'ui')
  git(repo, 'push', '-q', 'origin', 'main')
  git(repo, 'fetch', '-q', 'origin')
  attach(repo, wt, 'issue/11', 'origin/main', false)
  expect(existsSync(join(wt, 'ui/node_modules/x/package.json')), 'node_modules linked from the main checkout').toBe(true)
  expect(git(wt, 'status', '--porcelain', '--untracked-files=all').out, 'the link is not an untracked file').not.toContain('node_modules')
  put(join(wt, 'a.txt'), 'edited by the failed run\n')
  put(join(wt, 'new.txt'), 'untracked\n')
  expect(releaseFailed(wt, run)).toContain('released')
  expect(isReleased(wt)).toBe(true)
  expect(git(repo, 'rev-parse', '--verify', '-q', 'issue/11').code, 'the branch is gone').not.toBe(0)
  expect(sh(['cat', join(run, 'leftover.patch')]).out).toContain('edited by the failed run')
  expect(existsSync(join(wt, 'new.txt'))).toBe(false)
  // the next run of the issue takes the released worktree over at the same path
  attach(repo, wt, 'issue/11', 'origin/main', false)
  expect(git(wt, 'rev-parse', '--abbrev-ref', 'HEAD').out.trim()).toBe('issue/11')
  put(join(wt, 'b.txt'), 'b\n')
  git(wt, 'add', '-A')
  git(wt, 'commit', '-q', '-m', 'work')
  expect(releaseFailed(wt, run), 'commits are kept for a person').toContain('has commits')
  expect(isReleased(wt)).toBe(false)
})
