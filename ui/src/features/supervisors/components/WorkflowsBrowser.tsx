// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { Button, buttonVariants } from '@/components/ui/button';
import { Callout } from '@/components/common/Callout';
import { StateMessage } from '@/components/common/StateMessage';
import { Input } from '@/components/ui/input';
import { MultiSelect } from '@/components/ui/select';
import { Tip } from '@/components/ui/tooltip';
import { cn } from '@/lib/utils';
import { formatRelative } from '@/features/workflow/lib/timeFormat';
import { useSupervisors } from '../hooks/useSupervisorTypes';
import { useWorkflowIndex } from '../hooks/useWorkflowIndex';
import { parseScope } from '../lib/ownership';
import { workflowKey } from '../lib/supervisionGraph';
import type { WorkflowRow } from '../lib/workflowIndex';
import { Chip, SupervisorShell } from './SupervisorCard';
import { AssignmentCard } from './SupervisorDetailTabs';
import { SupervisionBody } from './WorkflowSupervision';

const name = (row: Pick<WorkflowRow, 'sessionId' | 'workflowId'>) =>
  row.sessionId ? `${row.sessionId} / ${row.workflowId}` : row.workflowId;

/** One workflow in the list: who controls it, whether something is switched off, and what was done lately. */
function WorkflowListItem({
  row,
  selected,
  onSelect,
}: {
  row: WorkflowRow;
  selected: boolean;
  onSelect: (row: WorkflowRow) => void;
}) {
  const [first, ...rest] = row.controllers;
  return (
    <button
      type="button"
      aria-current={selected}
      onClick={() => onSelect(row)}
      className={cn(
        'transition-standard flex w-full items-center gap-2.5 rounded-lg px-2.5 py-2 text-left',
        selected
          ? 'bg-accent text-accent-foreground'
          : 'text-on-surface-variant hover:bg-muted hover:text-on-surface'
      )}
    >
      <span
        aria-hidden="true"
        className={cn(
          'flex size-8 shrink-0 items-center justify-center rounded-lg',
          first
            ? 'bg-primary/10 text-primary'
            : 'bg-muted text-on-surface-variant'
        )}
      >
        <span className="material-symbols-outlined text-lg">
          {first ? 'account_tree' : 'person'}
        </span>
      </span>
      <span className="min-w-0 flex-1">
        <span className="text-body-md block truncate font-medium">
          {row.sessionId ? name(row) : <i>any session</i>}
          {!row.sessionId && ` / ${row.workflowId}`}
        </span>
        <span className="text-label-sm block truncate">
          {first
            ? `${first.supervisorId}${rest.length > 0 ? ` +${rest.length} more` : ''}`
            : 'Direct control'}
        </span>
      </span>
      {row.controllers.some((c) => !c.enabled) && (
        <Chip tone="attention">Disabled</Chip>
      )}
      {row.actions.count > 0 && row.actions.newest && (
        <Tip
          label={`${row.actions.count} actions in the newest 200 decisions; last ${formatRelative(row.actions.newest)}`}
        >
          <span className="text-label-sm tabular-nums">
            {row.actions.count}
          </span>
        </Tip>
      )}
    </button>
  );
}

/** One workflow: who controls it and what they may do (the run sheet's body, with no run chosen), then its assignments. */
function WorkflowSupervisionDetail({
  row,
  assignments,
}: {
  row: WorkflowRow;
  assignments: ReturnType<typeof useWorkflowIndex>['attachments'];
}) {
  const mine = assignments.filter((a) => {
    const t = parseScope(a.scope);
    return (
      (t.kind === 'workflow' || t.kind === 'run') && workflowKey(t) === row.key
    );
  });
  return (
    <article className="flex min-w-0 flex-col gap-4">
      <SupervisorShell direct={row.controllers.length === 0} className="p-4">
        <div className="relative flex flex-wrap items-start gap-3">
          <span
            aria-hidden="true"
            className="bg-primary/15 text-primary ring-primary/30 shadow-card flex size-12 shrink-0 items-center justify-center rounded-xl ring-1"
          >
            <span className="material-symbols-outlined text-[26px]!">
              account_tree
            </span>
          </span>
          <div className="min-w-0 flex-1">
            <p className="text-overline text-on-surface-variant">Workflow</p>
            <h2 className="text-headline-md text-on-surface break-all">
              {name(row)}
            </h2>
            <p className="text-body-sm text-on-surface-variant mt-1">
              Who controls every run of this workflow, and what they did
            </p>
          </div>
          {row.sessionId && (
            <Link
              to={`/sessions/${encodeURIComponent(row.sessionId)}/workflow/${encodeURIComponent(row.workflowId)}?supervision=1`}
              className={buttonVariants({ variant: 'outline', size: 'sm' })}
            >
              <span
                aria-hidden="true"
                className="material-symbols-outlined text-base"
              >
                open_in_new
              </span>
              Open workflow
            </Link>
          )}
        </div>
      </SupervisorShell>
      {row.sessionId ? (
        <SupervisionBody
          sessionId={row.sessionId}
          workflowId={row.workflowId}
          executionId={null}
        />
      ) : (
        <Callout kind="info" compact title="Applies in every session">
          This assignment covers workflow {row.workflowId} wherever it runs.
          Open the workflow in a session to see who controls each node.
        </Callout>
      )}
      <section aria-label="Assignments" className="flex flex-col gap-2">
        <h3 className="text-overline text-on-surface-variant flex items-center gap-2">
          Assignments
          <span className="tabular-nums">{mine.length}</span>
        </h3>
        {mine.length === 0 ? (
          <p className="text-body-sm text-on-surface-variant">
            No supervisor is assigned: people and agents control it directly.
          </p>
        ) : (
          <div className="grid gap-3 xl:grid-cols-2">
            {mine.map((a) => (
              <AssignmentCard
                key={a.id}
                supervisorId={a.supervisorId}
                assignment={a}
                showSupervisor
              />
            ))}
          </div>
        )}
      </section>
    </article>
  );
}

/** The list of workflows on the left, the chosen one on the right: the other way to look at supervision. */
export function WorkflowsBrowser() {
  const index = useWorkflowIndex();
  const supervisors = useSupervisors();
  const [params, setParams] = useSearchParams();
  const [filter, setFilter] = useState('');
  const [controllers, setControllers] = useState<string[]>([]);
  if (index.isPending) {
    return <StateMessage tone="loading" title="Loading workflows…" />;
  }
  if (index.failed) {
    return (
      <StateMessage
        tone="error"
        title="Failed to load the workflows."
        onRetry={index.refetch}
      >
        {index.error?.message}
      </StateMessage>
    );
  }
  if (index.rows.length === 0 && index.across.length === 0) {
    return (
      <StateMessage icon="account_tree" title="No workflows yet">
        Workflows appear here once a session defines them.
      </StateMessage>
    );
  }
  const workflowId = params.get('workflow');
  const sessionId = params.get('session');
  const wanted = workflowId ? workflowKey({ sessionId, workflowId }) : null;
  // a workflow nobody listed (reached from a run) still opens, with nothing assigned
  const selected: WorkflowRow | undefined = wanted
    ? (index.rows.find((r) => r.key === wanted) ?? {
        key: wanted,
        sessionId,
        workflowId: workflowId as string,
        controllers: [],
        direct: false,
        actions: { count: 0, newest: null },
      })
    : index.rows[0];
  const select = (row: WorkflowRow) => {
    const next = new URLSearchParams(params);
    if (row.sessionId) next.set('session', row.sessionId);
    else next.delete('session');
    next.set('workflow', row.workflowId);
    setParams(next, { replace: true });
  };
  const needle = filter.trim().toLowerCase();
  const shown = index.rows.filter(
    (r) =>
      (!needle || name(r).toLowerCase().includes(needle)) &&
      (controllers.length === 0 ||
        r.controllers.some((c) => controllers.includes(c.supervisorId)) ||
        (r.direct && controllers.includes('default')))
  );
  const options = [
    ...(supervisors.data ?? []).map((s) => ({ value: s.id, label: s.id })),
    { value: 'default', label: 'Direct control' },
  ];
  return (
    <div className="flex flex-col gap-4 md:flex-row">
      <div className="flex shrink-0 flex-col gap-3 md:sticky md:top-18 md:max-h-[calc(100vh-5.5rem)] md:w-64 md:self-start md:overflow-y-auto xl:w-72">
        <div className="flex flex-col gap-2">
          <Input
            type="search"
            aria-label="Filter workflows"
            placeholder="Search by session or workflow"
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
          />
          <MultiSelect
            ariaLabel="Filter by controller"
            placeholder="Any controller"
            values={controllers}
            onValuesChange={setControllers}
            options={options}
          />
        </div>
        <nav aria-label="Workflows" className="flex flex-col gap-2">
          <ul className="flex flex-col gap-0.5">
            {shown.map((r) => (
              <li key={r.key}>
                <WorkflowListItem
                  row={r}
                  selected={r.key === selected?.key}
                  onSelect={select}
                />
              </li>
            ))}
          </ul>
          {index.unowned > index.rows.filter((r) => r.direct).length && (
            <p className="text-label-sm text-on-surface-variant px-2.5">
              Showing {index.rows.filter((r) => r.direct).length} of{' '}
              {index.unowned} workflows nobody owns.
            </p>
          )}
          {index.across.length > 0 && (
            <div className="border-border mt-1 flex flex-col gap-0.5 border-t pt-2">
              <p className="text-overline text-on-surface-variant px-2.5">
                Across sessions
              </p>
              {index.across.map((a) => (
                <Link
                  key={a.id}
                  to={`/supervisors?supervisor=${encodeURIComponent(a.supervisorId)}`}
                  className="text-body-sm text-on-surface-variant hover:bg-muted hover:text-on-surface rounded-lg px-2.5 py-1.5"
                >
                  {a.label} · {a.supervisorId}
                </Link>
              ))}
            </div>
          )}
        </nav>
        {shown.length === 0 && index.rows.length > 0 && (
          <StateMessage compact icon="search" title="No workflow matches">
            Try another name or clear the filters.
            <Button
              variant="ghost"
              size="sm"
              onClick={() => {
                setFilter('');
                setControllers([]);
              }}
            >
              Clear filters
            </Button>
          </StateMessage>
        )}
      </div>
      <div className="min-w-0 flex-1">
        {selected ? (
          <WorkflowSupervisionDetail
            key={selected.key}
            row={selected}
            assignments={index.attachments}
          />
        ) : (
          <StateMessage icon="account_tree" title="Pick a workflow">
            Only assignments across sessions exist. Pick one on the left.
          </StateMessage>
        )}
      </div>
    </div>
  );
}
