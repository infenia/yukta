// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { buttonVariants } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { CodeBlock } from '@/components/common/CodeBlock';
import { StateMessage } from '@/components/common/StateMessage';
import { cn } from '@/lib/utils';
import {
  useAttachments,
  useSupervisorTypes,
  useSupervisors,
} from '../hooks/useSupervisorTypes';
import { decisionMaker } from '../lib/decisionMakers';
import { hierarchyRows } from '../lib/hierarchy';
import { parseScope, targetLabel } from '../lib/ownership';
import type { AttachmentView, SupervisorView } from '../types';
import { HierarchyTab, OperationsTab, OwnsTab } from './SupervisorDetailTabs';
import { Activity } from './Activity';
import { HistoryTab, SettingsTab } from './SettingsHistoryTabs';
import { StaleNotice } from './StaleNotice';
import { DefaultPeoplePanel } from './DefaultPeoplePanel';

const DETAIL_TABS = [
  'Owns',
  'Allowed operations',
  'Reports to',
  'Settings',
  'Changes',
  'Decisions',
] as const;
type DetailTab = (typeof DETAIL_TABS)[number];

function NavItem({
  id,
  label = id,
  sub,
  selected,
  onSelect,
}: {
  id: string;
  /** What is shown when it is not the id itself, such as "Default (people)". */
  label?: string;
  sub: string;
  selected: boolean;
  onSelect: (id: string) => void;
}) {
  return (
    <button
      type="button"
      aria-current={selected}
      onClick={() => onSelect(id)}
      className={cn(
        'text-body-md transition-standard w-full rounded-lg px-3 py-1.5 text-left',
        selected
          ? 'bg-accent text-accent-foreground font-semibold'
          : 'text-on-surface-variant hover:bg-muted hover:text-on-surface'
      )}
    >
      <span className="block">{label}</span>
      <span className="text-code-sm block text-[11px] opacity-75">{sub}</span>
    </button>
  );
}

/** The workflows that supervisors own, each with its supervisors; the server and session rest. */
function byWorkflow(assignments: AttachmentView[]) {
  const groups = new Map<string, Set<string>>();
  for (const a of assignments) {
    const t = parseScope(a.scope);
    const key =
      t.kind === 'workflow' || t.kind === 'run'
        ? [t.sessionId, t.workflowId].filter(Boolean).join(' / ')
        : targetLabel(t);
    groups.set(key, (groups.get(key) ?? new Set()).add(a.supervisorId));
  }
  return [...groups.entries()].sort(([a], [b]) => a.localeCompare(b));
}

/** Edit link for the supervisor, and the agent or CLI commands for changes made outside the UI. */
function ChangeSupervisor({ id }: { id: string }) {
  return (
    <div className="flex flex-col gap-3">
      <div>
        <Link
          to={`/supervisors/${encodeURIComponent(id)}/edit`}
          className={buttonVariants({ variant: 'outline', size: 'sm' })}
        >
          <span
            aria-hidden="true"
            className="material-symbols-outlined text-base"
          >
            edit
          </span>
          Edit supervisor
        </Link>
      </div>
      <details className="text-body-sm text-on-surface-variant">
        <summary className="cursor-pointer select-none">
          Other ways to change it
        </summary>
        <div className="mt-2">
          <CodeBlock
            label="Commands"
            code={[
              `MCP  save_supervisor {"id": "${id}", …}`,
              `CLI  yukta supervisors show ${id}`,
              `CLI  yukta supervisors assign ${id} <target>`,
              `API  GET /api/supervisors/${id}`,
            ].join('\n')}
          />
        </div>
      </details>
    </div>
  );
}

function SupervisorPanel({
  supervisor,
  all,
  onSelect,
}: {
  supervisor: SupervisorView;
  all: SupervisorView[];
  onSelect: (id: string) => void;
}) {
  const [tab, setTab] = useState<DetailTab>('Owns');
  const types = useSupervisorTypes();
  const maker = decisionMaker(supervisor.type, types.data);
  // a type the catalog does not list (a plugin's) has no page to open
  const listed = types.data?.some((t) => t.type === supervisor.type) ?? false;
  const makerLabel = (
    <>
      <span
        aria-hidden="true"
        className="size-2 rounded-full"
        style={{ background: maker.color }}
      />
      {maker.name}
    </>
  );
  const parents = supervisor.parents ?? [];
  return (
    <article className="mx-auto flex max-w-5xl min-w-0 flex-col gap-5">
      <header>
        <h2 className="text-headline-lg text-on-surface">{supervisor.id}</h2>
        <div className="text-body-md text-on-surface-variant mt-1 flex flex-wrap items-center gap-x-3 gap-y-1">
          {listed ? (
            <Link
              to={`/supervisors?tab=decision-makers&type=${encodeURIComponent(supervisor.type)}`}
              className="text-primary inline-flex items-center gap-2"
            >
              {makerLabel}
            </Link>
          ) : (
            <span className="inline-flex items-center gap-2">{makerLabel}</span>
          )}
          {supervisor.dryRun ? (
            <span className="text-attention inline-flex items-center gap-1">
              <span
                aria-hidden="true"
                className="material-symbols-outlined text-base"
              >
                filter_alt
              </span>
              Dry run: recorded, not applied
            </span>
          ) : (
            <span className="text-success inline-flex items-center gap-1">
              <span
                aria-hidden="true"
                className="material-symbols-outlined text-base"
              >
                check_circle
              </span>
              Live
            </span>
          )}
          {supervisor.system && <span>Built in (read only)</span>}
        </div>
      </header>
      {supervisor.description && (
        <p className="text-body-md text-on-surface">{supervisor.description}</p>
      )}
      <section
        aria-label="At a glance"
        className="border-border bg-card text-body-md shadow-card flex flex-col gap-2 rounded-xl border p-4 md:flex-row md:items-center md:gap-6"
      >
        <p>
          <span className="text-muted-foreground">Reports to </span>
          <b>{parents.length === 0 ? 'nobody' : parents.join(', ')}</b>
        </p>
        <p className="flex items-center gap-2">
          <span
            aria-hidden="true"
            className="material-symbols-outlined"
            style={{ color: maker.color }}
          >
            {maker.icon}
          </span>
          decides with <b>{maker.name}</b>
        </p>
        <p>
          <span className="text-muted-foreground">Owns </span>
          <button
            type="button"
            className="text-primary font-semibold"
            title="A target is a workflow, node or session this supervisor is assigned to"
            onClick={() => setTab('Owns')}
          >
            {supervisor.attachments}{' '}
            {supervisor.attachments === 1 ? 'target' : 'targets'}
          </button>
        </p>
      </section>
      <div
        role="tablist"
        aria-label={`${supervisor.id} details`}
        className="border-border flex flex-wrap gap-x-5 border-b"
      >
        {DETAIL_TABS.map((name) => (
          <button
            key={name}
            type="button"
            role="tab"
            aria-selected={tab === name}
            onClick={() => setTab(name)}
            className={cn(
              'text-label-md -mb-px shrink-0 border-b-2 py-2',
              tab === name
                ? 'border-primary text-on-surface'
                : 'text-on-surface-variant hover:text-on-surface border-transparent'
            )}
          >
            {name}
          </button>
        ))}
      </div>
      <div role="tabpanel" aria-label={tab}>
        {tab === 'Owns' && <OwnsTab supervisor={supervisor} />}
        {tab === 'Allowed operations' && (
          <OperationsTab supervisor={supervisor} />
        )}
        {tab === 'Reports to' && (
          <HierarchyTab supervisor={supervisor} all={all} onSelect={onSelect} />
        )}
        {tab === 'Settings' && <SettingsTab supervisor={supervisor} />}
        {tab === 'Changes' && <HistoryTab supervisor={supervisor} />}
        {tab === 'Decisions' && <Activity supervisor={supervisor.id} />}
      </div>
      <ChangeSupervisor id={supervisor.id} />
    </article>
  );
}

/** The Supervisors tab: the hierarchy or the workflows on the left, the chosen supervisor right. */
export function SupervisorsBrowser() {
  const supervisors = useSupervisors();
  const assignments = useAttachments();
  const types = useSupervisorTypes();
  const [params, setParams] = useSearchParams();
  const [filter, setFilter] = useState('');
  if (supervisors.isPending) {
    return <StateMessage tone="loading" title="Loading supervisors…" />;
  }
  // pending is handled above, so no data means the first fetch failed
  if (!supervisors.data) {
    return (
      <StateMessage
        tone="error"
        title="Failed to load the supervisors."
        onRetry={() => void supervisors.refetch()}
      >
        {supervisors.error?.message}
      </StateMessage>
    );
  }
  const all = supervisors.data;
  if (all.length === 0) {
    return (
      <StateMessage title="No supervisors yet" icon="shield_person">
        People control every workflow through Default (people). An agent adds
        supervisors with MCP{' '}
        <code className="text-code-sm">save_supervisor</code> or{' '}
        <code className="text-code-sm">yukta supervisors</code>.
      </StateMessage>
    );
  }
  const view = params.get('view') === 'workflow' ? 'workflow' : 'hierarchy';
  const selectedId = params.get('supervisor') ?? all[0].id;
  const selected = all.find((s) => s.id === selectedId) ?? all[0];
  const showDefault = selectedId === 'default';
  const select = (id: string) => {
    const next = new URLSearchParams(params);
    next.set('supervisor', id);
    setParams(next, { replace: true });
  };
  const showView = (next: 'hierarchy' | 'workflow') => {
    const updated = new URLSearchParams(params);
    if (next === 'workflow') updated.set('view', next);
    else updated.delete('view');
    setParams(updated, { replace: true });
  };
  const needle = filter.trim().toLowerCase();
  const matches = (id: string) => id.toLowerCase().includes(needle);
  const sub = (s: SupervisorView) => decisionMaker(s.type, types.data).name;
  const byId = new Map(all.map((s) => [s.id, s]));
  return (
    <>
      <StaleNotice query={supervisors} />
      <div className="flex flex-col gap-6 md:flex-row">
        <nav
          aria-label="Supervisors"
          className="flex shrink-0 flex-col gap-4 md:sticky md:top-18 md:max-h-[calc(100vh-5.5rem)] md:w-70 md:self-start md:overflow-y-auto"
        >
          <Input
            type="search"
            aria-label="Filter supervisors"
            placeholder="Filter by name"
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
          />
          <div role="group" aria-label="Show" className="flex gap-1">
            {(['hierarchy', 'workflow'] as const).map((v) => (
              <button
                key={v}
                type="button"
                aria-pressed={view === v}
                onClick={() => showView(v)}
                className={cn(
                  'text-label-md rounded-full px-3 py-1',
                  view === v
                    ? 'bg-accent text-accent-foreground'
                    : 'text-on-surface-variant hover:bg-muted'
                )}
              >
                {v === 'hierarchy' ? 'Reporting tree' : 'By workflow'}
              </button>
            ))}
          </div>
          <p className="text-body-sm text-muted-foreground px-3">
            {view === 'hierarchy'
              ? 'Who reports to whom.'
              : 'Which supervisor owns each workflow.'}
          </p>
          {view === 'hierarchy' ? (
            <ul className="flex flex-col gap-0.5">
              {hierarchyRows(all)
                .filter((row) => matches(row.id))
                .map((row, i) => (
                  <li
                    key={`${row.id}-${i}`}
                    className={cn(row.depth > 0 && 'border-border border-l')}
                    style={{ marginLeft: `${Math.max(0, row.depth) * 14}px` }}
                  >
                    <NavItem
                      id={row.id}
                      sub={
                        sub(byId.get(row.id) as SupervisorView) +
                        (row.alsoUnder.length > 0
                          ? ` · also under ${row.alsoUnder.join(', ')}`
                          : '')
                      }
                      selected={row.id === selected.id}
                      onSelect={select}
                    />
                  </li>
                ))}
            </ul>
          ) : (
            byWorkflow(assignments.data ?? []).map(([where, ids]) => (
              <section key={where}>
                <h3 className="text-label-md text-on-surface mb-1 truncate">
                  {where}
                </h3>
                <ul className="flex flex-col gap-0.5">
                  {[...ids].filter(matches).map((id) => (
                    <li key={id}>
                      <NavItem
                        id={id}
                        sub={
                          byId.has(id)
                            ? sub(byId.get(id) as SupervisorView)
                            : 'not configured'
                        }
                        selected={id === selected.id}
                        onSelect={select}
                      />
                    </li>
                  ))}
                </ul>
              </section>
            ))
          )}
          <NavItem
            id="default"
            label="Default (people)"
            sub="People, for workflows nobody owns"
            selected={selectedId === 'default'}
            onSelect={select}
          />
        </nav>
        <div className="min-w-0 flex-1">
          {showDefault ? (
            <DefaultPeoplePanel />
          ) : (
            <SupervisorPanel
              key={selected.id}
              supervisor={selected}
              all={all}
              onSelect={select}
            />
          )}
        </div>
      </div>
    </>
  );
}
