// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState } from 'react';
import { useSearchParams } from 'react-router';
import { Input } from '@/components/ui/input';
import { StateMessage } from '@/components/common/StateMessage';
import { cn } from '@/lib/utils';
import { useSupervisors } from '../hooks/useSupervisorTypes';
import { StaleNotice } from './StaleNotice';
import {
  DirectControlDetail,
  DETAIL_TABS,
  SupervisorDetail,
  type DetailTab,
} from './SupervisorDetail';

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
    DETAIL_TABS.find((t) => t === params.get('section')) ?? 'owns';
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
            <DirectControlDetail tab={section} setTab={setSection} />
          ) : (
            <SupervisorDetail
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
