// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

/** The commands a supervisor may send, in the groups people think in. */
export const COMMAND_GROUPS: { label: string; commands: string[] }[] = [
  {
    label: 'Pause and resume',
    commands: [
      'PauseWorkflowCommand',
      'ResumeWorkflowCommand',
      'PauseNodeCommand',
      'ResumeNodeCommand',
    ],
  },
  { label: 'Stop', commands: ['StopWorkflowCommand', 'StopNodeCommand'] },
  {
    label: 'Skip and step',
    commands: [
      'SkipNodeCommand',
      'StepNodeCommand',
      'EnableStepModeCommand',
      'DisableStepModeCommand',
      'SetBreakpointCommand',
    ],
  },
  {
    label: 'Restart and recover',
    commands: ['RestartCommand', 'RestartFromNodeCommand', 'RecoverCommand'],
  },
  {
    label: 'Start runs',
    commands: ['StartWorkflowCommand', 'SetPriorityCommand'],
  },
  { label: 'Change limits', commands: ['SetLimitsCommand', 'DrainCommand'] },
  {
    label: 'Answer input requests',
    commands: ['AnswerInputCommand', 'EscalateInputCommand'],
  },
];

/** How much of a group a list allows. */
export type Reach = 'all' | 'some' | 'none';

const reach = (allowed: (command: string) => boolean, commands: string[]) => {
  const count = commands.filter(allowed).length;
  return count === commands.length ? 'all' : count === 0 ? 'none' : 'some';
};

const listOf = (value: unknown): string[] | null =>
  Array.isArray(value) ? value.map(String) : null;

/** One row of the allowed operations table. */
export interface OperationRow {
  label: string;
  send: Reach;
  onItsOwn: Reach;
  people: Reach;
}

/**
 * What a supervisor may do, by group: `allowedCommands` (absent: the engine's default, and a
 * default of null is every command), `initiative` (a subset it may send on its own) and
 * `operatorAccess` (what people may still send through it).
 */
export function operationRows(
  settings: Record<string, unknown>,
  engineDefault: string[] | null
): OperationRow[] {
  const allowed =
    'allowedCommands' in settings
      ? listOf(settings.allowedCommands)
      : engineDefault;
  const mayCommand = (command: string) =>
    allowed === null || allowed.includes(command);
  const initiative = listOf(settings.initiative) ?? [];
  const people = listOf(settings.operatorAccess) ?? [];
  return COMMAND_GROUPS.map(({ label, commands }) => ({
    label,
    send: reach(mayCommand, commands),
    onItsOwn: reach((c) => mayCommand(c) && initiative.includes(c), commands),
    people: reach((c) => people.includes(c), commands),
  }));
}
