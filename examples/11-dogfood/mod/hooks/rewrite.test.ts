import { expect, test } from 'claude-code/testing'

import { rewrite } from './rewrite'

test('builds, tests and lint are sent to yk; the app and other commands pass', () => {
  const cases: [string, string | undefined][] = [
    ['./gradlew :core:test --tests X', 'yk gradle :core:test --tests X'],
    ['FOO=1 timeout 60 ./gradlew -q --offline :core:compileJava', 'yk gradle :core:compileJava'],
    ['cd ui && pnpm lint', 'yk tool -C ui pnpm lint'],
    ['go test ./...', 'yk tool -C cli go test ./...'],
    ['go version', undefined],
    ['./gradlew bootRun', undefined],
    ['./gradlew :boot:bootRun --args=x', undefined],
    ['echo bootRun; ./gradlew test', 'yk gradle test'],
    ["git commit -m 'fix bootRun' && npx prettier .", 'yk tool -C ui npx prettier .'],
    ['ls | grep gradle', undefined],
    ['git status', undefined],
  ]
  for (const [cmd, use] of cases) expect(rewrite(cmd)).toBe(use)
})
