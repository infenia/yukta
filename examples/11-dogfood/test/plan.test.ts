// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// Offline test of plan's class-level test selection (Tier 1) in a throwaway git repo: core <- web.
import { afterAll, beforeAll, expect, test } from 'bun:test'
import { join } from 'node:path'
import { plan } from '../src/plan.ts'
import { git, initRepo, put, read, rm, sh, tmp } from './helpers.ts'

let repo: string
beforeAll(() => {
  repo = tmp()
  initRepo(repo, {
    'settings.gradle.kts': 'include("core")\ninclude("web")\n', 'core/build.gradle.kts': 'plugins { id("x") }\n', 'web/build.gradle.kts': 'dependencies { implementation(project(":core")) }\n',
    'core/src/main/java/p/Foo.java': 'package p;\nclass Foo {\n  int f() { return 1; }\n}\n', 'core/src/test/java/p/FooTest.java': 'package p;\nclass FooTest {}\n',
    'core/src/main/java/p/Bar.java': 'package p;\nclass Bar {}\n', 'web/src/main/java/w/W.java': 'package w; class W {}\n',
  })
})
afterAll(() => rm(repo))
const lines = (): string[] => plan(false, 'HEAD', repo).text.split('\n')
const testsLine = (): string => lines().find(l => l.startsWith('tests\t'))?.split('\t')[2] ?? ''
const sed = (file: string, from: string, to: string): void => put(join(repo, file), read(join(repo, file)).replace(from, to))

test('a private change in a class with a test: only that test, no dependants, no coverage stage', () => {
  sed('core/src/main/java/p/Foo.java', 'return 1', 'return 2')
  expect(testsLine()).toBe('./gradlew :core:test --tests p.FooTest -q --console=plain')
  expect(lines().some(l => l.startsWith('coverage'))).toBe(false)
})
test('a changed test class alone: that test', () => {
  git(repo, 'checkout', '-q', '.'); put(join(repo, 'core/src/test/java/p/FooTest.java'), read(join(repo, 'core/src/test/java/p/FooTest.java')) + '// c\n')
  expect(testsLine()).toContain(':core:test --tests p.FooTest ')
})
test('a public member touched: the whole module and its dependants', () => {
  git(repo, 'checkout', '-q', '.'); sed('core/src/main/java/p/Foo.java', '  int f', '  public int f')
  expect(testsLine()).toContain(':web:test')
})
test('a class without a test: the whole module', () => {
  git(repo, 'checkout', '-q', '.'); put(join(repo, 'core/src/main/java/p/Bar.java'), read(join(repo, 'core/src/main/java/p/Bar.java')) + '// c\n')
  expect(testsLine()).toContain(':core:jacocoTestReport')
  void sh
})
