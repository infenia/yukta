// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { WorkflowSummary } from '../types/session';
import { StateMessage } from '@/components/common/StateMessage';
import { WorkflowCard } from './WorkflowCard';

interface WorkflowGridProps {
  sessionId: string;
  workflows: WorkflowSummary[];
  filter: string;
  onFilterChange: (filter: string) => void;
}

export function WorkflowGrid({
  sessionId,
  workflows,
  filter,
  onFilterChange,
}: WorkflowGridProps) {
  const filteredWorkflows = workflows.filter(
    (workflow) =>
      workflow.workflowId.toLowerCase().includes(filter.toLowerCase()) ||
      workflow.description.toLowerCase().includes(filter.toLowerCase())
  );

  return (
    <section className="px-4 py-6 sm:px-6">
      <div className="mb-6 flex flex-wrap items-center justify-between gap-3">
        <h2 className="text-headline-md text-on-surface">
          Associated Workflows ({workflows.length})
        </h2>
        <div className="flex w-full items-center gap-2 rounded-lg border border-border bg-card px-3 py-1.5 shadow-card transition-standard focus-within:border-ring sm:w-auto">
          <span
            className="material-symbols-outlined text-sm text-on-surface-variant"
            aria-hidden="true"
          >
            search
          </span>
          <input
            type="text"
            placeholder="Filter workflows..."
            value={filter}
            onChange={(e) => onFilterChange(e.target.value)}
            className="min-w-0 flex-1 border-none bg-transparent p-0 text-body-md outline-none focus:ring-0"
          />
        </div>
      </div>

      {filteredWorkflows.length === 0 ? (
        <StateMessage
          title={
            workflows.length === 0
              ? 'No workflows'
              : 'No workflows match your filter'
          }
          icon={workflows.length === 0 ? 'account_tree' : 'search_off'}
        />
      ) : (
        <div className="grid grid-cols-[repeat(auto-fill,minmax(18rem,1fr))] gap-4">
          {filteredWorkflows.map((workflow) => (
            <WorkflowCard
              key={workflow.workflowId}
              sessionId={sessionId}
              workflow={workflow}
            />
          ))}
        </div>
      )}
    </section>
  );
}
