// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { Button } from '@/components/ui/button';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
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
import { RequirePermission } from '@/components/common/RequirePermission';
import { StatusPill } from '@/features/workflow/components/StatusPill';
import { RunButton } from '@/features/workflow/components/RunButton';
import { DeleteWorkflowDialog } from './DeleteDialogs';
import type { WorkflowSummary } from '../types/session';

interface WorkflowTableProps {
  sessionId: string;
  workflows: WorkflowSummary[];
  filter: string;
  onFilterChange: (filter: string) => void;
}

/** Edit and Delete for one workflow, out of the way of the row's link. */
function WorkflowActionsMenu({
  sessionId,
  workflowId,
}: {
  sessionId: string;
  workflowId: string;
}) {
  const navigate = useNavigate();
  const [deleting, setDeleting] = useState(false);
  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        render={(state) => (
          <Button
            variant="ghost"
            size="icon-sm"
            aria-label={`Actions for workflow ${workflowId}`}
            {...state}
          >
            <span className="material-symbols-outlined" aria-hidden="true">
              more_vert
            </span>
          </Button>
        )}
      />
      <DropdownMenuContent align="end">
        <DropdownMenuItem
          onClick={() =>
            navigate(
              `/sessions/${encodeURIComponent(sessionId)}/workflow/${encodeURIComponent(workflowId)}/edit`
            )
          }
        >
          Edit
        </DropdownMenuItem>
        <RequirePermission perm="deleteSessions">
          <DropdownMenuItem onClick={() => setDeleting(true)}>
            Delete workflow…
          </DropdownMenuItem>
        </RequirePermission>
      </DropdownMenuContent>
      {deleting && (
        <DeleteWorkflowDialog
          sessionId={sessionId}
          workflowId={workflowId}
          onClose={() => setDeleting(false)}
        />
      )}
    </DropdownMenu>
  );
}

/** The session's workflows as a dense table: ID and description, size, last run, actions. */
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
        <span className="bg-muted text-label-sm text-muted-foreground rounded-full px-2 tabular-nums">
          {workflows.length}
        </span>
        <label className="bg-muted transition-standard focus-within:border-ring ml-auto flex w-full items-center gap-2 rounded-full border border-transparent px-3 py-1.5 sm:w-64">
          <span
            className="material-symbols-outlined text-muted-foreground text-base"
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
            className="text-body-md min-w-0 flex-1 border-none bg-transparent p-0 outline-none focus:ring-0"
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
                      className="text-code-sm text-primary font-semibold break-all"
                    >
                      {workflow.workflowId}
                    </Link>
                    {workflow.description && (
                      <p className="text-body-sm text-muted-foreground mt-0.5 line-clamp-2">
                        {workflow.description}
                      </p>
                    )}
                  </TableCell>
                  <TableCell className="text-body-sm text-muted-foreground px-4 whitespace-nowrap tabular-nums">
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
                      <WorkflowActionsMenu
                        sessionId={sessionId}
                        workflowId={workflow.workflowId}
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
