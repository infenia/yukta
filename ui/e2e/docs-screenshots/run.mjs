// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

/*
 * Entry point of `pnpm docs:screenshots`. Flags (also after `--`):
 *   --only <name[,name]>  capture only these shots
 *   --check               write nothing; list shots whose committed image is stale
 *   --strict              with --check, exit non-zero when any shot is stale
 * Stale shots are collected from the spec's stdout (lines starting with STALE).
 */
import { spawnSync } from 'node:child_process';

const args = process.argv.slice(2);
const flag = (name) => args.includes(name);
const value = (name) => {
  const i = args.indexOf(name);
  return i >= 0 ? args[i + 1] : undefined;
};
const only = value('--only');
const check = flag('--check');
const strict = flag('--strict');

const env = {
  ...process.env,
  DOCS_SHOT_CHECK: check ? '1' : '0',
  ...(only ? { DOCS_SHOT_ONLY: only } : {}),
};
const result = spawnSync(
  'node_modules/.bin/playwright',
  ['test', '-c', 'playwright.docs.config.ts', '--reporter=list'],
  { env, encoding: 'utf8', stdio: ['inherit', 'pipe', 'inherit'] }
);
process.stdout.write(result.stdout ?? '');
if (result.status !== 0) {
  console.error(
    'A shot failed to capture; the failing shot is named above ("Shot ... failed").'
  );
  process.exit(result.status ?? 1);
}
if (check) {
  const stale = [...(result.stdout ?? '').matchAll(/STALE (\S+)/g)].map(
    (m) => m[1]
  );
  console.log(
    stale.length === 0
      ? 'All checked shots are current.'
      : `Stale: ${stale.join(', ')}`
  );
  if (stale.length > 0 && strict) process.exit(2);
}
