// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { buttonVariants } from '@/components/ui/button';
import { cn } from '@/lib/utils';
import { StatusPill } from './StatusPill';
import { formatDateTime } from '../lib/timeFormat';
import { isTerminalExecStatus } from '../lib/statusMapper';
import type { ExecutionSummary } from '../types/workflow';

/** One run, the same in the closed trigger and the open list: status icon, full id, date and time. */
function RunRow({ execution }: { execution: ExecutionSummary }) {
  return (
    <span className="flex items-center gap-2">
      <StatusPill
        status={execution.status}
        iconOnly
        testId="run-status"
        className="size-6 shrink-0 [&_.material-symbols-outlined]:text-[18px]!"
      />
      <span className="text-label-md font-mono whitespace-nowrap">
        {execution.executionId}
      </span>
      <span className="text-body-md text-on-surface-variant whitespace-nowrap tabular-nums">
        {formatDateTime(execution.startTime)}
      </span>
    </span>
  );
}

/** Finished runs listed; running and queued runs are always listed. */
export const FINISHED_RUNS_SHOWN = 25;

/** The run picker. With no runs yet it is disabled and reads "Not Yet Run". */
export function RunHistoryDropdown({
  executions,
  selectedExecutionId,
  onSelect,
  onViewAll,
}: {
  executions: ExecutionSummary[];
  selectedExecutionId: string | null;
  onSelect: (executionId: string) => void;
  /** Opens the Runs page for this workflow, where older runs are. */
  onViewAll?: () => void;
}) {
  // As tall as the run controls beside it (a toolbar group is a 32px segment in 4px padding)
  const triggerClass = cn(
    buttonVariants({ variant: 'outline', size: 'sm' }),
    'h-10 gap-2 rounded-full pr-2 pl-2'
  );

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
  // ponytail: the list is newest first; the cap is on the client, the server sends up to 500
  let finished = 0;
  const listed = executions.filter(
    (execution) =>
      !isTerminalExecStatus(execution.status) ||
      finished++ < FINISHED_RUNS_SHOWN
  );
  const hidden = executions.length - listed.length;
  return (
    <DropdownMenu>
      <DropdownMenuTrigger className={triggerClass} aria-label="Run history">
        {selected ? (
          <RunRow execution={selected} />
        ) : (
          <span className="text-body-md">—</span>
        )}
        <span className="material-symbols-outlined text-xl">expand_more</span>
      </DropdownMenuTrigger>
      <DropdownMenuContent
        align="start"
        className="max-h-96 w-max overflow-y-auto"
      >
        {listed.map((execution) => (
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
        {onViewAll && (
          <>
            <DropdownMenuSeparator />
            <DropdownMenuItem onClick={onViewAll}>
              {hidden > 0
                ? `View all runs (${hidden} older not shown)`
                : 'View all runs'}
            </DropdownMenuItem>
          </>
        )}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
