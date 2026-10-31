// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { Link, useNavigate } from 'react-router';
import {
  Table,
  TableBody,
  TableCell,
  TableFrame,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { StateMessage } from '@/components/common/StateMessage';
import { StatusPill } from '@/features/workflow/components/StatusPill';
import { RunButton } from '@/features/workflow/components/RunButton';
import type { WorkflowSummary } from '../types/session';

interface WorkflowTableProps {
  sessionId: string;
  workflows: WorkflowSummary[];
  filter: string;
  onFilterChange: (filter: string) => void;
}

/** The session's workflows as a dense table: ID and description, size, last run, Run. */
export function WorkflowTable({
  sessionId,
  workflows,
  filter,
  onFilterChange,
}: WorkflowTableProps) {
  const navigate = useNavigate();
  const needle = filter.toLowerCase();
  const shown = workflows.filter(
    (workflow) =>
      workflow.workflowId.toLowerCase().includes(needle) ||
      workflow.description.toLowerCase().includes(needle)
  );

  return (
    <section className="flex flex-col gap-3 px-4 py-6 sm:px-6">
      <div className="flex flex-wrap items-center gap-3">
        <h2 className="text-headline-md text-on-surface">Workflows</h2>
        <span className="rounded-full bg-muted px-2 text-label-sm text-muted-foreground tabular-nums">
          {workflows.length}
        </span>
        <label className="ml-auto flex w-full items-center gap-2 rounded-full border border-transparent bg-muted px-3 py-1.5 transition-standard focus-within:border-ring sm:w-64">
          <span
            className="material-symbols-outlined text-base text-muted-foreground"
            aria-hidden="true"
          >
            search
          </span>
          <input
            type="text"
            aria-label="Filter workflows"
            placeholder="Filter workflows"
            value={filter}
            onChange={(e) => onFilterChange(e.target.value)}
            className="min-w-0 flex-1 border-none bg-transparent p-0 text-body-md outline-none focus:ring-0"
          />
        </label>
      </div>

      {shown.length === 0 ? (
        <StateMessage
          title={
            workflows.length === 0
              ? 'No workflows'
              : 'No workflows match your filter'
          }
          icon={workflows.length === 0 ? 'account_tree' : 'search_off'}
        />
      ) : (
        <TableFrame>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead className="px-4">Workflow</TableHead>
                <TableHead className="px-4">Size</TableHead>
                <TableHead className="px-4">Last run</TableHead>
                <TableHead className="px-4">
                  <span className="sr-only">Actions</span>
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {shown.map((workflow) => (
                <TableRow
                  key={workflow.workflowId}
                  data-testid={`workflow-row-${workflow.workflowId}`}
                >
                  <TableCell className="max-w-xl px-4 whitespace-normal">
                    <Link
                      to={`/sessions/${encodeURIComponent(sessionId)}/workflow/${encodeURIComponent(workflow.workflowId)}`}
                      className="text-code-sm font-semibold break-all text-primary"
                    >
                      {workflow.workflowId}
                    </Link>
                    {workflow.description && (
                      <p className="mt-0.5 line-clamp-2 text-body-sm text-muted-foreground">
                        {workflow.description}
                      </p>
                    )}
                  </TableCell>
                  <TableCell className="px-4 text-body-sm whitespace-nowrap text-muted-foreground tabular-nums">
                    {workflow.nodeCount} nodes · {workflow.edgeCount} edges
                  </TableCell>
                  <TableCell className="px-4">
                    <StatusPill status={workflow.status} />
                  </TableCell>
                  <TableCell className="px-4">
                    <div className="flex items-center justify-end gap-1">
                      <RunButton
                        size="sm"
                        label={`Run ${workflow.workflowId}`}
                        sessionId={sessionId}
                        workflowId={workflow.workflowId}
                        onStarted={(executionId) =>
                          void navigate(
                            `/sessions/${encodeURIComponent(sessionId)}/workflow/${encodeURIComponent(workflow.workflowId)}?exec=${encodeURIComponent(executionId)}`
                          )
                        }
                      />
                    </div>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableFrame>
      )}
    </section>
  );
}
