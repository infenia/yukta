// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { toCli, type CommandAction } from '@/lib/commandEquivalents';
import type { Call } from '../types';

const VERBS = {
  PAUSE: 'pause',
  RESUME: 'resume',
  STOP: 'stop',
  RESTART: 'restart',
} as const;

/** The CLI command that does what a tool call does, for the calls the CLI has a command for. */
export function cliEquivalent(call: Call): string | null {
  const { sessionId, workflowId, executionId, action } = call.args as Record<
    string,
    string | undefined
  >;
  if (!sessionId) return null;
  let found: CommandAction | null = null;
  if (call.name === 'start_workflow' && workflowId) {
    found = { kind: 'start', sessionId, workflowId };
  } else if (call.name === 'control_workflow' && executionId && action) {
    const verb = VERBS[action as keyof typeof VERBS] as
      (typeof VERBS)[keyof typeof VERBS] | undefined;
    if (verb) found = { kind: 'run', verb, sessionId, executionId };
  }
  return found && toCli(found, window.location.origin);
}
