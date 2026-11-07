// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { buttonVariants } from '@/components/ui/button';
import { StatusPill } from './StatusPill';
import { formatDateTime } from '../lib/timeFormat';
import type { ExecutionSummary } from '../types/workflow';

/** One run, the same in the closed trigger and the open list: status icon, full id, date and time. */
function RunRow({ execution }: { execution: ExecutionSummary }) {
  return (
    <span className="flex items-center gap-2">
      <StatusPill
        status={execution.status}
        iconOnly
        testId="run-status"
        className="shrink-0"
      />
      <span className="text-code-sm font-mono whitespace-nowrap">
        {execution.executionId}
      </span>
      <span className="text-body-sm text-on-surface-variant whitespace-nowrap tabular-nums">
        {formatDateTime(execution.startTime)}
      </span>
    </span>
  );
}

/** The run picker. With no runs yet it is disabled and reads "Not Yet Run". */
export function RunHistoryDropdown({
  executions,
  selectedExecutionId,
  onSelect,
}: {
  executions: ExecutionSummary[];
  selectedExecutionId: string | null;
  onSelect: (executionId: string) => void;
}) {
  const triggerClass = buttonVariants({ variant: 'outline', size: 'sm' });

  if (executions.length === 0) {
    return (
      <button
        type="button"
        disabled
        aria-label="Run history"
        className={triggerClass}
        data-testid="run-history-empty"
      >
        Not Yet Run
      </button>
    );
  }

  const selected = executions.find(
    (execution) => execution.executionId === selectedExecutionId
  );
  return (
    <DropdownMenu>
      <DropdownMenuTrigger className={triggerClass} aria-label="Run history">
        {selected ? (
          <RunRow execution={selected} />
        ) : (
          <span className="text-body-sm">—</span>
        )}
        <span className="material-symbols-outlined text-base">expand_more</span>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start" className="w-max">
        {executions.map((execution) => (
          <DropdownMenuItem
            key={execution.executionId}
            aria-current={
              execution.executionId === selectedExecutionId ? 'true' : undefined
            }
            onClick={() => onSelect(execution.executionId)}
          >
            <RunRow execution={execution} />
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
