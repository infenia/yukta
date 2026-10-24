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
          <div className="group border-outline-variant flex h-10 cursor-not-allowed items-center gap-2 border-l px-4 opacity-60">
            <span className="text-label-sm text-on-surface-variant">Tag</span>
            <span className="material-symbols-outlined text-on-surface-variant text-lg">
              filter_alt
            </span>
          </div>
          <div className="border-outline-variant flex h-10 cursor-not-allowed items-center gap-2 border-l px-4 opacity-60">
            <span className="text-label-sm text-on-surface-variant">
              Initiator
            </span>
            <span className="material-symbols-outlined text-on-surface-variant text-lg">
              person
            </span>
          </div>
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
