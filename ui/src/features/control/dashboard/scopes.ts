// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { GLOBAL, type Scope } from '@/features/metrics/api';

export interface ScopeOption {
  value: Scope;
  label: string;
  /** the `<optgroup>` it is listed under; none for the global scope */
  group?: 'Sessions' | 'Workflows';
}

/**
 * The choices of the scope filter: the whole server, every session, and the workflows the page
 * knows of (the chosen session's workflows, the last run of each session and the runs going now),
 * each listed once. A scope from the URL that matches none of them is still listed, so it shows as
 * chosen.
 */
export function scopeOptions({
  sessions,
  workflowIds,
  current,
}: {
  sessions: { sessionId: string; name?: string | null }[];
  workflowIds: (string | null | undefined)[];
  current: Scope;
}): ScopeOption[] {
  const options: ScopeOption[] = [{ value: GLOBAL, label: 'Global' }];
  const seen = new Set<string>([GLOBAL]);
  const add = (option: ScopeOption) => {
    if (!seen.has(option.value)) {
      seen.add(option.value);
      options.push(option);
    }
  };
  for (const s of sessions) {
    add({
      value: `session:${s.sessionId}`,
      label: `Session ${s.name || s.sessionId}`,
      group: 'Sessions',
    });
  }
  const workflows = [...new Set(workflowIds)].filter((id): id is string =>
    Boolean(id)
  );
  for (const id of workflows.sort()) {
    add({
      value: `workflow:${id}`,
      label: `Workflow ${id}`,
      group: 'Workflows',
    });
  }
  if (current.startsWith('session:')) {
    add({
      value: current,
      label: `Session ${current.slice('session:'.length)}`,
      group: 'Sessions',
    });
  } else if (current.startsWith('workflow:')) {
    add({
      value: current,
      label: `Workflow ${current.slice('workflow:'.length)}`,
      group: 'Workflows',
    });
  } else {
    add({ value: current, label: current });
  }
  return options;
}

/** Whether an item of the given session and workflow belongs to the scope. */
export const inScope = (
  scope: Scope,
  item: { sessionId: string; workflowId: string | null }
) =>
  scope === GLOBAL ||
  scope === `session:${item.sessionId}` ||
  scope === `workflow:${item.workflowId}`;

/** The session id of a `session:<id>` scope, or an empty string. */
export const sessionOf = (scope: Scope) =>
  scope.startsWith('session:') ? scope.slice('session:'.length) : '';
