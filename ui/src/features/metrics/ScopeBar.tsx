// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { Select } from '@/components/ui/select';
import { RANGES } from './api';
import { useDashboardParams } from './hooks';

interface ScopeChoice {
  value: string;
  label: string;
  /** listed under this `<optgroup>`; ungrouped choices come first */
  group?: string;
}

/** The scope, range and live/pause controls shared by every dashboard panel. */
export function ScopeBar({
  scopes = [{ value: 'global', label: 'Global' }],
}: {
  scopes?: ScopeChoice[];
}) {
  const { scope, range, live, setScope, setRange, setLive } =
    useDashboardParams();
  return (
    <div
      className="flex flex-wrap items-center gap-2"
      role="toolbar"
      aria-label="Dashboard view"
    >
      <Select
        searchable={scopes.length > 8}
        ariaLabel="Scope"
        value={scope}
        onValueChange={setScope}
        options={scopes}
        className="max-w-full min-w-0 sm:max-w-xs"
      />
      <div role="group" aria-label="Range" className="flex gap-1">
        {RANGES.map((r) => (
          <button
            key={r}
            type="button"
            aria-pressed={r === range}
            onClick={() => setRange(r)}
            className={`text-label-md h-8 rounded-lg px-2.5 ${r === range ? 'bg-accent text-accent-foreground' : 'text-muted-foreground hover:bg-muted'}`}
          >
            {r}
          </button>
        ))}
      </div>
      <button
        type="button"
        aria-pressed={live}
        onClick={() => setLive(!live)}
        className="border-border text-label-md flex h-8 items-center gap-1.5 rounded-lg border px-2.5"
      >
        <span
          aria-hidden="true"
          className={`size-2 rounded-full ${live ? 'bg-success motion-safe:animate-pulse' : 'bg-muted-foreground'}`}
        />
        {live ? 'Live' : 'Paused'}
      </button>
    </div>
  );
}
