// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// Offline test of advise in a throwaway git repo against the fake Ollama: the floor from the paths, and a model that can only widen it.
// The fake model answers the label after "difficulty:" in the digest, so a changed file is named "difficulty:<label>".
import { afterAll, beforeAll, expect, setDefaultTimeout, test } from 'bun:test'
import { join } from 'node:path'
import { ts, git, initRepo, put, rm, sh, startOllama, tmp } from './helpers.ts'

setDefaultTimeout(120_000)
let d: string, repo: string, ollama: { port: number; kill: () => void }
beforeAll(async () => {
  d = tmp(); repo = join(d, 'w')
  ollama = await startOllama(d)
  initRepo(repo, { seed: 'x\n' })
})
afterAll(() => { ollama.kill(); rm(d) })

const change = (file: string): void => {
  sh(['git', 'clean', '-fdq'], { cwd: repo }); git(repo, 'checkout', '-q', '.')
  put(join(repo, file), 'c\n')
}
const tier = (env: Record<string, string> = {}, ...args: string[]): string => {
  const o = ts('src/advise.ts', ['--base', 'HEAD', ...args], {
    env: { YUKTA_REPO_ROOT: repo, YUKTA_DEV_HOME: join(d, 'home'), YUKTA_DEV_STATE_DIR: join(d, 'state'), YUKTA_DECISION: 'on', YUKTA_DECISION_MODEL: 'fake', YUKTA_DECISION_URL: `http://127.0.0.1:${ollama.port}`, ...env },
  })
  return o.out.replace(/^advice: tier ([0-9]).*[\s\S]*$/, '$1')
}

test('the floor, from the paths', () => {
  change('README.md'); expect(tier()).toBe('0')
  change('core/src/main/A.java'); expect(tier({}, '--no-model'), 'one class').toBe('1')
  change('plugin-api/src/main/B.java'); expect(tier({}, '--no-model'), 'shared code').toBe('3')
  change('build-logic/x.kts'); expect(tier({}, '--no-model'), 'build').toBe('4')
})
test('the model widens', () => {
  change('core/src/main/difficulty:public-contract'); expect(tier()).toBe('2')
  change('core/src/main/difficulty:build-or-config'); expect(tier()).toBe('3')
  change('core/src/main/difficulty:uncertain'); expect(tier(), 'unsure').toBe('2')
})
test('... and never narrows', () => {
  change('core/src/main/difficulty:docs-or-comment-only'); expect(tier()).toBe('1')
  change('plugin-api/difficulty:internal-change'); expect(tier()).toBe('3')
})
test('no model, or one that errors: the floor stands', () => {
  change('core/src/main/difficulty:boom'); expect(tier()).toBe('1')
  change('core/src/main/difficulty:public-contract'); expect(tier({ YUKTA_DECISION: 'off' })).toBe('1')
})
