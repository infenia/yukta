// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, it, expect } from 'vitest';
import { isTerminalExecStatus, toStatusDisplay } from '../statusMapper';

/** The server's status vocabulary: core's TaskStatus enum. */
const TASK_STATUS = resolve(
  process.cwd(),
  '../core/src/main/java/com/infenia/yukta/model/execution/TaskStatus.java'
);
const NON_TERMINAL = new Set(['PENDING', 'QUEUED', 'RUNNING']);

/** Names of the enum constants: the `NAME,` / `NAME;` lines at the top of the enum body. */
function serverStatuses(): string[] {
  const source = readFileSync(TASK_STATUS, 'utf8');
  const body = source.slice(source.indexOf('public enum TaskStatus'));
  return [...body.matchAll(/^ {2}([A-Z][A-Z_]*)[,;]/gm)].map((m) => m[1]!);
}

// Skipped when the UI is built without the rest of the repository
describe.skipIf(!existsSync(TASK_STATUS))(
  'the UI knows every status the server can publish',
  () => {
    it('reads the enum', () => {
      expect(serverStatuses()).toContain('RUNNING');
    });

    it('shows every server status with its own label, not the unknown-status fallback', () => {
      const unknown = serverStatuses().filter(
        (name) => toStatusDisplay(name).icon === 'help'
      );
      // a new TaskStatus needs an entry in STATUS_DISPLAY (and the maps beside it)
      expect(unknown).toEqual([]);
    });

    it('agrees with the server on which statuses are terminal', () => {
      const wrong = serverStatuses().filter(
        (name) => isTerminalExecStatus(name) === NON_TERMINAL.has(name)
      );
      expect(wrong).toEqual([]);
    });
  }
);
