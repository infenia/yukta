// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import type { SessionListItem } from '../types/session';

/** The selected Tag (all must match) and Initiator (any may match) values. */
export interface SessionFilters {
  tags: string[];
  initiators: string[];
}

/** Sessions that carry every selected tag and were started by one of the selected initiators. */
export function filterSessions(
  sessions: SessionListItem[],
  { tags, initiators }: SessionFilters
): SessionListItem[] {
  return sessions.filter(
    (session) =>
      tags.every((tag) => session.tags.includes(tag)) &&
      (initiators.length === 0 || initiators.includes(session.initiator))
  );
}

const distinct = (values: string[]) => [...new Set(values)].sort();

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
        className="border-outline-variant text-label-sm text-on-surface-variant hover:bg-surface-container-high flex h-10 items-center gap-2 border-l px-4"
      >
        {selected.length > 0 ? `${label} · ${selected.length}` : label}
        <span className="material-symbols-outlined text-lg">{icon}</span>
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
    <div className="px-4 pb-4 sm:px-6">
      <div className="border-outline-variant bg-surface-container flex flex-wrap items-center gap-px overflow-hidden rounded-xl border">
        <div className="ring-primary bg-surface-container flex h-10 min-w-[300px] flex-1 items-center px-4 focus-within:ring-1 focus-within:ring-inset">
          <span className="material-symbols-outlined text-on-surface-variant mr-3">
            search
          </span>
          <input
            type="text"
            placeholder="Search by name or session ID..."
            value={globalFilter}
            onChange={(e) => onGlobalFilterChange(e.target.value)}
            aria-label="Search sessions by name or session ID"
            className="text-body-md text-on-surface placeholder:text-outline w-full border-none bg-transparent focus:ring-0 focus:outline-hidden"
          />
        </div>

        <div className="flex flex-wrap items-center">
          <MultiFilter
            label="Tag"
            icon="filter_alt"
            options={distinct(data.flatMap((session) => session.tags))}
            selected={filters.tags}
            onChange={(tags) => onFiltersChange({ ...filters, tags })}
          />
          <MultiFilter
            label="Initiator"
            icon="person"
            options={distinct(data.map((session) => session.initiator))}
            selected={filters.initiators}
            onChange={(initiators) =>
              onFiltersChange({ ...filters, initiators })
            }
          />
          <button
            type="button"
            onClick={onReset}
            aria-label="Reset filters"
            className="border-outline-variant text-on-surface-variant hover:bg-error-container/20 hover:text-error flex h-10 items-center border-l px-4 transition-colors"
          >
            <span className="material-symbols-outlined text-lg">
              restart_alt
            </span>
          </button>
        </div>
      </div>
    </div>
  );
}
