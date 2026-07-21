// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { SessionListItem } from '../types/session';

interface SessionsFilterBarProps {
  data: SessionListItem[];
  globalFilter: string;
  onGlobalFilterChange: (value: string) => void;
  onReset: () => void;
}

export function SessionsFilterBar({
  globalFilter,
  onGlobalFilterChange,
  onReset,
}: SessionsFilterBarProps) {
  return (
    <div className="px-6 py-4">
      <div className="flex flex-wrap items-center gap-px border border-outline-variant bg-surface-container">
        <div className="ring-primary flex h-10 min-w-[300px] flex-1 items-center bg-surface-container px-4 focus-within:ring-1 focus-within:ring-inset">
          <span className="material-symbols-outlined mr-3 text-on-surface-variant">
            search
          </span>
          <input
            type="text"
            placeholder="Search by name or session ID..."
            value={globalFilter}
            onChange={(e) => onGlobalFilterChange(e.target.value)}
            aria-label="Search sessions by name or session ID"
            className="w-full border-none bg-transparent text-sm text-on-surface placeholder:text-outline focus:ring-0 focus:outline-hidden"
          />
        </div>

        <div className="flex flex-wrap items-center">
          <div className="group flex h-10 cursor-not-allowed items-center gap-2 border-l border-outline-variant px-4 opacity-60">
            <span className="text-xs font-semibold tracking-wider text-on-surface-variant uppercase">
              Tag
            </span>
            <span className="material-symbols-outlined text-on-surface-variant">
              filter_alt
            </span>
          </div>
          <div className="flex h-10 cursor-not-allowed items-center gap-2 border-l border-outline-variant px-4 opacity-60">
            <span className="text-xs font-semibold tracking-wider text-on-surface-variant uppercase">
              Initiator
            </span>
            <span className="material-symbols-outlined text-on-surface-variant">
              person
            </span>
          </div>
          <button
            type="button"
            onClick={onReset}
            aria-label="Reset filters"
            className="flex h-10 items-center border-l border-outline-variant px-4 text-on-surface-variant transition-colors hover:bg-error-container/20 hover:text-error"
          >
            <span className="material-symbols-outlined">restart_alt</span>
          </button>
        </div>
      </div>
    </div>
  );
}
