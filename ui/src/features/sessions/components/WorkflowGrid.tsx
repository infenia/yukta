// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { WorkflowSummary } from '../types/session';
import { WorkflowCard } from './WorkflowCard';

interface WorkflowGridProps {
  sessionId: string;
  workflows: WorkflowSummary[];
  filter: string;
  onFilterChange: (filter: string) => void;
}

export function WorkflowGrid({ sessionId, workflows, filter, onFilterChange }: WorkflowGridProps) {
  const filteredWorkflows = workflows.filter(
    (workflow) =>
      workflow.workflowId.toLowerCase().includes(filter.toLowerCase()) ||
      workflow.description.toLowerCase().includes(filter.toLowerCase())
  );

  return (
    <section className="mx-auto mt-12 max-w-6xl px-8 pb-12">
      <div className="mb-6 flex items-center justify-between">
        <h2 className="text-xl font-bold text-on-surface">
          Associated Workflows ({workflows.length})
        </h2>
        <div className="flex items-center gap-2 border border-outline-variant bg-surface-container px-3 py-1.5">
          <span className="material-symbols-outlined text-sm text-on-surface-variant" aria-hidden="true">
            search
          </span>
          <input
            type="text"
            placeholder="Filter workflows..."
            value={filter}
            onChange={(e) => onFilterChange(e.target.value)}
            className="border-none bg-transparent p-0 text-xs focus:ring-0"
          />
        </div>
      </div>

      {filteredWorkflows.length === 0 ? (
        <div className="border border-outline-variant bg-surface-container py-12 text-center">
          <p className="text-body-md text-on-surface-variant">
            {workflows.length === 0 ? 'No workflows' : 'No workflows match your filter'}
          </p>
        </div>
      ) : (
        <div className="grid grid-cols-1 gap-px border border-outline-variant bg-outline-variant md:grid-cols-2 lg:grid-cols-3">
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
