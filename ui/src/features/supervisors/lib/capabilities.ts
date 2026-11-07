// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { SupervisorView } from '../types';

export interface Capabilities {
  /** Command names it may send; null means every command. */
  commands: string[] | null;
  canAnswer: boolean;
  maxRisk: 'LOW' | 'MEDIUM' | 'HIGH';
  dryRun: boolean;
}

/** What a supervisor may do, read from its settings with the server's defaults. */
export function capabilities(
  supervisor: Pick<SupervisorView, 'settings' | 'dryRun'>,
  typeDefaults: string[] | null = null
): Capabilities {
  const { allowedCommands, canAnswer, maxRisk } = supervisor.settings;
  const risk = String(maxRisk ?? 'LOW').toUpperCase();
  return {
    commands: Array.isArray(allowedCommands)
      ? allowedCommands.map(String)
      : typeDefaults,
    canAnswer: canAnswer === true,
    maxRisk: risk === 'MEDIUM' || risk === 'HIGH' ? risk : 'LOW',
    dryRun: supervisor.dryRun,
  };
}

/** `PauseWorkflowCommand` as "pause workflow". */
export const commandLabel = (name: string) =>
  name
    .replace(/Command$/, '')
    .replace(/([a-z])([A-Z])/g, '$1 $2')
    .toLowerCase();
