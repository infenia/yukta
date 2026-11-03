// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
// Offline test of yk-hook.ts: the command rewrite (what is denied, what passes). Run: yk tool -C .claude/hooks bun test
import { expect, test } from 'bun:test'
import { rewrite } from './yk-hook.ts'

const CASES: [string, string | null][] = [
  ['./gradlew :core:test --tests X', 'yk gradle :core:test --tests X'],
  ['FOO=1 timeout 60 ./gradlew -q --offline :core:compileJava', 'yk gradle :core:compileJava'],
  ['cd ui && pnpm lint', 'yk tool -C ui pnpm lint'],
  ['go test ./...', 'yk tool -C cli go test ./...'],
  ['go version', null],
  ['./gradlew bootRun', null],
  ['./gradlew :boot:bootRun --args=x', null],
  ['echo bootRun; ./gradlew test', 'yk gradle test'],
  ["git commit -m 'fix bootRun' && npx prettier .", 'yk tool -C ui npx prettier .'],
  ["git commit -m 'run gradle test && pnpm lint'", null],
  ['ls | grep gradle', null],
  ['git status', null],
  ["bash -c 'cd ui && pnpm test'", 'yk tool -C ui pnpm test'],
  ['sh -c "./gradlew :core:test"', 'yk gradle :core:test'],
  ['ui/node_modules/.bin/vitest run', 'yk tool -C ui ui/node_modules/.bin/vitest run'],
  ['yarn test', 'yk tool -C ui yarn test'],
  ["./gradlew :core:test --tests 'a.B.c d'", "yk gradle :core:test --tests 'a.B.c d'"],
  ['yk gradle :core:test', null],
  ['./gradlew test 2>&1 | tail -5', 'yk gradle test'],
  ['bun examples/11-dogfood/src/plan.ts', 'yk tool -C examples/11-dogfood bun examples/11-dogfood/src/plan.ts'],
  ['bunx tsc --noEmit', 'yk tool -C ui bunx tsc --noEmit'],
]

test.each(CASES)('%s', (cmd, use) => {
  expect(rewrite(cmd)).toBe(use)
})
