// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { buttonVariants } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { CodeBlock } from '@/components/common/CodeBlock';
import { StateMessage } from '@/components/common/StateMessage';
import { Tabs } from '@/components/ui/tabs';
import { cn } from '@/lib/utils';
import {
  useSupervisorTypes,
  useSupervisors,
} from '../hooks/useSupervisorTypes';
import { decisionMaker } from '../lib/decisionMakers';
import type { SupervisorView } from '../types';
import { HierarchyTab, OperationsTab, OwnsTab } from './SupervisorDetailTabs';
import { Activity } from './Activity';
import { HistoryTab, SettingsTab } from './SettingsHistoryTabs';
import { StaleNotice } from './StaleNotice';
import { DefaultPeoplePanel } from './DefaultPeoplePanel';

const DETAIL_TABS = [
  { id: 'owns', label: 'Owns' },
  { id: 'operations', label: 'Allowed operations' },
  { id: 'reports-to', label: 'Reports to' },
  { id: 'settings', label: 'Settings' },
  { id: 'changes', label: 'Changes' },
  { id: 'decisions', label: 'Decisions' },
] as const;
type DetailTab = (typeof DETAIL_TABS)[number]['id'];

function NavItem({
  id,
  label = id,
  sub,
  selected,
  onSelect,
  live,
  owns,
}: {
  id: string;
  /** What is shown when it is not the id itself, such as "Direct control". */
  label?: string;
  /** The engine id, in mono. */
  sub: string;
  selected: boolean;
  onSelect: (id: string) => void;
  /** A Live or Dry-run dot; none for Direct control. */
  live?: boolean;
  owns?: number;
}) {
  return (
    <button
      type="button"
      aria-current={selected}
      onClick={() => onSelect(id)}
      className={cn(
        'text-body-md transition-standard flex w-full items-center gap-2 rounded-lg px-3 py-1.5 text-left',
        selected
          ? 'bg-accent text-accent-foreground font-semibold'
          : 'text-on-surface-variant hover:bg-muted hover:text-on-surface'
      )}
    >
      {live !== undefined && (
        <span
          role="img"
          aria-label={live ? 'Live' : 'Dry run'}
          title={live ? 'Live' : 'Dry run: recorded, not applied'}
          className={cn(
            'size-2 shrink-0 rounded-full',
            live ? 'bg-success' : 'ring-attention ring-2 ring-inset'
          )}
        />
      )}
      <span className="min-w-0 flex-1">
        <span className="block truncate">{label}</span>
        <span className="text-code-sm block text-[11px] opacity-75">{sub}</span>
      </span>
      {owns !== undefined && owns > 0 && (
        <span
          title={`Owns ${owns} ${owns === 1 ? 'target' : 'targets'}`}
          className="bg-muted text-label-sm text-on-surface-variant rounded-full px-2 tabular-nums"
        >
          {owns}
        </span>
      )}
    </button>
  );
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
  tab,
  setTab,
}: {
  supervisor: SupervisorView;
  all: SupervisorView[];
  onSelect: (id: string) => void;
  tab: DetailTab;
  setTab: (tab: DetailTab) => void;
}) {
  const types = useSupervisorTypes();
  const maker = decisionMaker(supervisor.type, types.data);
  const parents = supervisor.parents ?? [];
  return (
    <article className="mx-auto flex max-w-5xl min-w-0 flex-col gap-5">
      <header>
        <h2 className="text-headline-lg text-on-surface">{supervisor.id}</h2>
        <div className="text-body-md text-on-surface-variant mt-1 flex flex-wrap items-center gap-x-3 gap-y-1">
          <Link
            to={`/supervisors?tab=decision-makers&type=${encodeURIComponent(supervisor.type)}`}
            className="text-primary inline-flex items-center gap-2"
          >
            <span
              aria-hidden="true"
              className="size-2 rounded-full"
              style={{ background: maker.color }}
            />
            {maker.name}
          </Link>
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
            onClick={() => setTab('owns')}
          >
            {supervisor.attachments}{' '}
            {supervisor.attachments === 1 ? 'target' : 'targets'}
          </button>
        </p>
      </section>
      <Tabs
        ariaLabel={`${supervisor.id} details`}
        items={DETAIL_TABS}
        value={tab}
        onChange={setTab}
      />
      <div
        role="tabpanel"
        aria-label={DETAIL_TABS.find((t) => t.id === tab)?.label}
      >
        {tab === 'owns' && <OwnsTab supervisor={supervisor} />}
        {tab === 'operations' && <OperationsTab supervisor={supervisor} />}
        {tab === 'reports-to' && (
          <HierarchyTab supervisor={supervisor} all={all} onSelect={onSelect} />
        )}
        {tab === 'settings' && <SettingsTab supervisor={supervisor} />}
        {tab === 'changes' && <HistoryTab supervisor={supervisor} />}
        {tab === 'decisions' && <Activity supervisor={supervisor.id} />}
      </div>
      <ChangeSupervisor id={supervisor.id} />
    </article>
  );
}

/** The flat list of supervisors on the left, the chosen one on the right. */
export function SupervisorsBrowser() {
  const supervisors = useSupervisors();
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
  // named supervisors alphabetically; Direct control comes last, below the list
  const all = [...supervisors.data].sort((a, b) => a.id.localeCompare(b.id));
  const selectedId = params.get('supervisor') ?? all[0]?.id ?? 'default';
  const selected = all.find((s) => s.id === selectedId) ?? all[0];
  const showDefault = selectedId === 'default' || !selected;
  const section =
    DETAIL_TABS.find((t) => t.id === params.get('section'))?.id ?? 'owns';
  const update = (change: (next: URLSearchParams) => void) => {
    const next = new URLSearchParams(params);
    change(next);
    setParams(next, { replace: true });
  };
  const select = (id: string) =>
    update((next) => {
      next.set('supervisor', id);
      next.delete('section');
    });
  const setSection = (tab: DetailTab) =>
    update((next) => {
      if (tab === 'owns') next.delete('section');
      else next.set('section', tab);
    });
  const needle = filter.trim().toLowerCase();
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
          <ul className="flex flex-col gap-0.5">
            {all
              .filter((s) => s.id.toLowerCase().includes(needle))
              .map((s) => (
                <li key={s.id}>
                  <NavItem
                    id={s.id}
                    sub={s.type}
                    live={!s.dryRun}
                    owns={s.attachments}
                    selected={!showDefault && s.id === selected?.id}
                    onSelect={select}
                  />
                </li>
              ))}
          </ul>
          {all.length === 0 && (
            <p className="text-body-sm text-on-surface-variant px-3">
              No supervisors yet. An agent adds them with MCP{' '}
              <code className="text-code-sm">save_supervisor</code>, the CLI (
              <code className="text-code-sm">yukta supervisors</code>) or the
              API.
            </p>
          )}
          <NavItem
            id="default"
            label="Direct control"
            sub="For workflows nobody owns"
            selected={showDefault}
            onSelect={select}
          />
        </nav>
        <div className="min-w-0 flex-1">
          {showDefault || !selected ? (
            <DefaultPeoplePanel />
          ) : (
            <SupervisorPanel
              key={selected.id}
              supervisor={selected}
              all={all}
              onSelect={select}
              tab={section}
              setTab={setSection}
            />
          )}
        </div>
      </div>
    </>
  );
}
