// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useRef, useState, type ReactNode } from 'react';
import { Link, useSearchParams } from 'react-router';
import { useQuery } from '@tanstack/react-query';
import { IconButton } from '@/components/common/IconButton';
import { Input } from '@/components/ui/input';
import { MultiSelect } from '@/components/ui/select';
import { StateMessage } from '@/components/common/StateMessage';
import { Tip } from '@/components/ui/tooltip';
import { cn } from '@/lib/utils';
import { getSupervisionSummary } from '../api/supervision';
import {
  useSupervisors,
  useSupervisorTypes,
} from '../hooks/useSupervisorTypes';
import { decisionMaker } from '../lib/decisionMakers';
import type { SupervisorView } from '../types';
import { StaleNotice } from './StaleNotice';
import { ListToggle, useListDisclosure } from './ListDisclosure';
import { Chip } from './SupervisorCard';
import {
  DirectControlDetail,
  DETAIL_SECTIONS,
  SupervisorDetail,
  type DetailSection,
} from './SupervisorDetail';

const STATES = [
  { value: 'live', label: 'Live' },
  { value: 'dry-run', label: 'Dry run' },
  { value: 'built-in', label: 'Built in' },
];

const stateOf = (s: SupervisorView) => [
  s.dryRun ? 'dry-run' : 'live',
  ...(s.system ? ['built-in'] : []),
];

/** One row of the list: an icon tile with its state dot, the name, its type, and small counts. */
function SupervisorListItem({
  id,
  label = id,
  sub,
  selected,
  onSelect,
  live,
  system,
  owns,
  refused,
  ownsTip,
}: {
  id: string;
  /** What is shown when it is not the id itself, such as "Direct control". */
  label?: string;
  sub: ReactNode;
  selected: boolean;
  onSelect: (id: string) => void;
  /** The Live or Dry-run dot; none for Direct control. */
  live?: boolean;
  system?: boolean;
  owns?: number;
  refused?: number;
  ownsTip?: string;
}) {
  const direct = live === undefined;
  return (
    <button
      type="button"
      aria-current={selected}
      onClick={() => onSelect(id)}
      className={cn(
        'transition-standard flex w-full items-center gap-2.5 rounded-lg px-2.5 py-2 text-left',
        selected
          ? 'bg-accent text-accent-foreground'
          : 'text-on-surface-variant hover:bg-muted hover:text-on-surface'
      )}
    >
      <span className="relative shrink-0">
        <span
          aria-hidden="true"
          className={cn(
            'flex size-8 items-center justify-center rounded-lg ring-1',
            direct
              ? 'bg-muted text-on-surface-variant ring-border'
              : 'bg-primary/10 text-primary ring-primary/20'
          )}
        >
          <span className="material-symbols-outlined text-lg">
            {direct ? 'person' : 'shield_person'}
          </span>
        </span>
        {live !== undefined && (
          <Tip label={live ? 'Live' : 'Dry run: recorded, not applied'}>
            <span
              role="img"
              aria-label={live ? 'Live' : 'Dry run'}
              className={cn(
                'ring-card absolute -right-0.5 -bottom-0.5 size-2.5 rounded-full ring-2',
                live ? 'bg-success' : 'bg-attention'
              )}
            />
          </Tip>
        )}
      </span>
      <span className="min-w-0 flex-1">
        <span className="text-body-md block truncate font-medium">{label}</span>
        <span className="text-label-sm block truncate">{sub}</span>
      </span>
      {system && <Chip>Built in</Chip>}
      {refused !== undefined && refused > 0 && (
        <Tip label={`${refused} refused`}>
          <span className="bg-attention/10 text-label-sm text-attention rounded-full px-2 tabular-nums">
            {refused}
          </span>
        </Tip>
      )}
      {owns !== undefined && owns > 0 && (
        <Tip
          label={ownsTip ?? `Owns ${owns} ${owns === 1 ? 'target' : 'targets'}`}
        >
          <span className="bg-muted text-label-sm text-on-surface-variant rounded-full px-2 tabular-nums">
            {owns}
          </span>
        </Tip>
      )}
    </button>
  );
}

/** The flat list of supervisors on the left, the chosen one on the right. */
export function SupervisorsBrowser() {
  const supervisors = useSupervisors();
  const types = useSupervisorTypes();
  const summary = useQuery({
    queryKey: ['supervision', 'summary'],
    queryFn: getSupervisionSummary,
  });
  const [params, setParams] = useSearchParams();
  const [filter, setFilter] = useState('');
  const [typeFilter, setTypeFilter] = useState<string[]>([]);
  const [stateFilter, setStateFilter] = useState<string[]>([]);
  const detailRef = useRef<HTMLDivElement>(null);
  const phone = useListDisclosure(params.get('supervisor'), detailRef);
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
  // the old Settings tab is now part of Decision maker
  const named =
    params.get('section') === 'settings'
      ? 'decision-maker'
      : params.get('section');
  const section = DETAIL_SECTIONS.find((t) => t === named) ?? null;
  const update = (change: (next: URLSearchParams) => void) => {
    const next = new URLSearchParams(params);
    change(next);
    setParams(next, { replace: true });
  };
  const select = (id: string) => {
    phone.onPick();
    update((next) => {
      next.set('supervisor', id);
      next.delete('section');
    });
  };
  const setSection = (target: DetailSection) =>
    update((next) => next.set('section', target));
  const needle = filter.trim().toLowerCase();
  const typeName = (type: string) => decisionMaker(type, types.data).name;
  const shown = all.filter(
    (s) =>
      (!needle ||
        [s.id, s.type, typeName(s.type)].some((t) =>
          t.toLowerCase().includes(needle)
        )) &&
      (typeFilter.length === 0 || typeFilter.includes(s.type)) &&
      (stateFilter.length === 0 ||
        stateOf(s).some((state) => stateFilter.includes(state)))
  );
  const clear = () => {
    setFilter('');
    setTypeFilter([]);
    setStateFilter([]);
  };
  const typeOptions = [...new Set(all.map((s) => s.type))].map((type) => ({
    value: type,
    label: typeName(type),
  }));
  const unowned = summary.data?.unowned ?? 0;
  return (
    <>
      <StaleNotice query={supervisors} />
      <div className="flex flex-col gap-4 md:flex-row">
        <div className="flex shrink-0 flex-col gap-3 md:sticky md:top-18 md:max-h-[calc(100vh-5.5rem)] md:w-64 md:self-start md:overflow-y-auto xl:w-72">
          <ListToggle
            label={`All supervisors (${all.length})`}
            open={phone.open}
            controls="supervisor-list"
            onToggle={phone.toggle}
          />
          <div className="flex flex-col gap-2">
            <Input
              type="search"
              aria-label="Filter supervisors"
              placeholder="Search by name or type"
              value={filter}
              onChange={(e) => setFilter(e.target.value)}
            />
            <div className="flex gap-2">
              <MultiSelect
                ariaLabel="Filter by type"
                placeholder="Any type"
                values={typeFilter}
                onValuesChange={setTypeFilter}
                options={typeOptions}
                className="min-w-0 flex-1"
              />
              <MultiSelect
                ariaLabel="Filter by state"
                placeholder="Any state"
                values={stateFilter}
                onValuesChange={setStateFilter}
                options={STATES}
                className="min-w-0 flex-1"
              />
            </div>
          </div>
          <nav
            id="supervisor-list"
            aria-label="Supervisors"
            className={cn('flex flex-col gap-2', phone.listClass)}
          >
            <ul className="flex flex-col gap-0.5">
              {shown.map((s) => (
                <li key={s.id}>
                  <SupervisorListItem
                    id={s.id}
                    sub={
                      typeName(s.type) === s.type ? (
                        <code className="text-code-sm">{s.type}</code>
                      ) : (
                        <>
                          {typeName(s.type)} ·{' '}
                          <code className="text-code-sm">{s.type}</code>
                        </>
                      )
                    }
                    live={!s.dryRun}
                    system={s.system}
                    owns={s.attachments}
                    refused={s.rejected}
                    selected={!showDefault && s.id === selected?.id}
                    onSelect={select}
                  />
                </li>
              ))}
            </ul>
            <div className="border-border mt-1 border-t pt-2">
              <SupervisorListItem
                id="default"
                label="Direct control"
                sub="For workflows nobody owns"
                owns={unowned}
                ownsTip={`${unowned} workflows nobody supervises`}
                selected={showDefault}
                onSelect={select}
              />
            </div>
          </nav>
          {all.length > 0 && shown.length === 0 && (
            <StateMessage compact icon="search" title="No supervisor matches">
              Try another name or clear the filters.
              <IconButton icon="close" label="Clear filters" onClick={clear} />
            </StateMessage>
          )}
          {all.length === 0 && (
            <p className="border-border text-body-sm text-on-surface-variant rounded-xl border border-dashed p-3">
              No supervisors yet. An agent adds them with MCP{' '}
              <code className="text-code-sm">save_supervisor</code>, the CLI (
              <code className="text-code-sm">yukta supervisors</code>) or the
              API. Or create one by hand with{' '}
              <Link to="/supervisors/new" className="text-primary">
                New supervisor
              </Link>
              .
            </p>
          )}
        </div>
        <div ref={detailRef} className="min-w-0 flex-1">
          {showDefault || !selected ? (
            <DirectControlDetail section={section} />
          ) : (
            <SupervisorDetail
              key={selected.id}
              supervisor={selected}
              all={all}
              onSelect={select}
              section={section}
              setSection={setSection}
            />
          )}
        </div>
      </div>
    </>
  );
}
