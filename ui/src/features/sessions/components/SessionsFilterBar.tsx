// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { toStatusDisplay } from '@/features/workflow/lib/statusMapper';
import type { SessionListItem } from '../types/session';

/** The selected Tag (all must match) and Initiator (any may match) values. */
export interface SessionFilters {
  tags: string[];
  initiators: string[];
  /** Labels of the last run's status ("Failed", "Running"…), or "Not run". */
  lastRuns: string[];
}

/** How the Last run filter names a session's last run. */
export const lastRunLabel = (session: SessionListItem) =>
  session.lastRun ? toStatusDisplay(session.lastRun.status).label : 'Not run';

/**
 * Sessions that carry every selected tag, were started by one of the selected initiators, and
 * whose last run is one of the selected kinds.
 */
export function filterSessions(
  sessions: SessionListItem[],
  { tags, initiators, lastRuns }: SessionFilters
): SessionListItem[] {
  return sessions.filter(
    (session) =>
      tags.every((tag) => session.tags.includes(tag)) &&
      (initiators.length === 0 || initiators.includes(session.initiator)) &&
      (lastRuns.length === 0 || lastRuns.includes(lastRunLabel(session)))
  );
}

const distinct = (values: string[]) => [...new Set(values)].sort();

/** What a filter chip says it is set to: any, the one value, or how many. */
const summary = (selected: string[]) =>
  selected.length === 0
    ? 'any'
    : selected.length === 1
      ? selected[0]
      : `${selected.length} selected`;

function MultiFilter({
  label,
  icon,
  options,
  selected,
  onChange,
}: {
  label: string;
  icon: string;
  options: string[];
  selected: string[];
  onChange: (values: string[]) => void;
}) {
  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        aria-label={`${label} filter`}
        className="border-border bg-card text-label-md text-on-surface hover:bg-muted flex h-8 items-center gap-1.5 rounded-full border px-3"
      >
        <span className="material-symbols-outlined text-base">{icon}</span>
        {label}: {summary(selected)}
        <span className="material-symbols-outlined text-base">expand_more</span>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        {options.length === 0 ? (
          <div className="text-body-sm text-on-surface-variant px-3 py-2">
            None in these sessions
          </div>
        ) : (
          options.map((option) => (
            <DropdownMenuCheckboxItem
              key={option}
              checked={selected.includes(option)}
              onCheckedChange={(checked) =>
                onChange(
                  checked
                    ? [...selected, option]
                    : selected.filter((value) => value !== option)
                )
              }
            >
              {option}
            </DropdownMenuCheckboxItem>
          ))
        )}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

interface SessionsFilterBarProps {
  data: SessionListItem[];
  globalFilter: string;
  onGlobalFilterChange: (value: string) => void;
  filters: SessionFilters;
  onFiltersChange: (filters: SessionFilters) => void;
  onReset: () => void;
}

export function SessionsFilterBar({
  data,
  filters,
  onFiltersChange,
  globalFilter,
  onGlobalFilterChange,
  onReset,
}: SessionsFilterBarProps) {
  return (
    <div className="flex flex-wrap items-center gap-2 px-4 pb-4 sm:px-6">
      <label className="relative flex max-w-[420px] min-w-[280px] flex-1 items-center">
        <span className="material-symbols-outlined text-on-surface-variant pointer-events-none absolute left-3 text-lg">
          search
        </span>
        <input
          type="text"
          placeholder="Filter by name, ID or description"
          value={globalFilter}
          onChange={(e) => onGlobalFilterChange(e.target.value)}
          aria-label="Search sessions by name or session ID"
          className="bg-muted text-body-md text-on-surface placeholder:text-on-surface-variant focus-visible:ring-primary/40 h-8 w-full rounded-full border-none pr-3 pl-9 focus:outline-hidden focus-visible:ring-2"
        />
      </label>
      <MultiFilter
        label="Tag"
        icon="label"
        options={distinct(data.flatMap((session) => session.tags))}
        selected={filters.tags}
        onChange={(tags) => onFiltersChange({ ...filters, tags })}
      />
      <MultiFilter
        label="Initiator"
        icon="person"
        options={distinct(data.map((session) => session.initiator))}
        selected={filters.initiators}
        onChange={(initiators) => onFiltersChange({ ...filters, initiators })}
      />
      <MultiFilter
        label="Last run"
        icon="history"
        options={distinct(data.map(lastRunLabel))}
        selected={filters.lastRuns}
        onChange={(lastRuns) => onFiltersChange({ ...filters, lastRuns })}
      />
      <span className="text-body-sm text-on-surface-variant ml-auto hidden sm:inline">
        Filters are kept in the URL
      </span>
      <button
        type="button"
        onClick={onReset}
        aria-label="Reset filters"
        title="Reset filters"
        className="text-on-surface-variant hover:bg-muted hover:text-on-surface flex size-8 items-center justify-center rounded-full transition-colors"
      >
        <span className="material-symbols-outlined text-lg">restart_alt</span>
      </button>
    </div>
  );
}
