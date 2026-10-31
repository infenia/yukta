// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import type { SessionListItem } from '../types/session';
import { lastRunLabel } from './SessionsFilterBar.helpers';
import type { SessionFilters } from './SessionsFilterBar.helpers';

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
        className="flex h-8 items-center gap-1.5 rounded-full border border-border bg-card px-3 text-label-md text-on-surface hover:bg-muted"
      >
        <span className="material-symbols-outlined text-base">{icon}</span>
        {label}: {summary(selected)}
        <span className="material-symbols-outlined text-base">expand_more</span>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        {options.length === 0 ? (
          <div className="px-3 py-2 text-body-sm text-on-surface-variant">
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
      <label className="relative flex max-w-105 min-w-70 flex-1 items-center">
        <span className="material-symbols-outlined pointer-events-none absolute left-3 text-lg text-on-surface-variant">
          search
        </span>
        <input
          type="text"
          placeholder="Filter by name, ID or description"
          value={globalFilter}
          onChange={(e) => onGlobalFilterChange(e.target.value)}
          aria-label="Search sessions by name or session ID"
          className="h-8 w-full rounded-full border-none bg-muted pr-3 pl-9 text-body-md text-on-surface placeholder:text-on-surface-variant focus:outline-hidden focus-visible:ring-2 focus-visible:ring-primary/40"
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
      <span className="ml-auto hidden text-body-sm text-on-surface-variant sm:inline">
        Filters are kept in the URL
      </span>
      <button
        type="button"
        onClick={onReset}
        aria-label="Reset filters"
        title="Reset filters"
        className="flex size-8 items-center justify-center rounded-full text-on-surface-variant transition-colors hover:bg-muted hover:text-on-surface"
      >
        <span className="material-symbols-outlined text-lg">restart_alt</span>
      </button>
    </div>
  );
}
