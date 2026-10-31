// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { RANGES } from './api';
import { useDashboardParams } from './hooks';

/** The scope, range and live/pause controls shared by every dashboard panel. */
export function ScopeBar({
  scopes = [{ value: 'global', label: 'Global' }],
}: {
  scopes?: { value: string; label: string }[];
}) {
  const { scope, range, live, setScope, setRange, setLive } =
    useDashboardParams();
  return (
    <div
      className="flex flex-wrap items-center gap-2"
      role="toolbar"
      aria-label="Dashboard view"
    >
      <select
        aria-label="Scope"
        value={scope}
        onChange={(e) => setScope(e.target.value)}
        className="h-8 rounded-lg border border-border bg-surface px-2 text-body-md"
      >
        {scopes.map((s) => (
          <option key={s.value} value={s.value}>
            {s.label}
          </option>
        ))}
      </select>
      <div role="group" aria-label="Range" className="flex gap-1">
        {RANGES.map((r) => (
          <button
            key={r}
            type="button"
            aria-pressed={r === range}
            onClick={() => setRange(r)}
            className={`h-8 rounded-lg px-2.5 text-label-md ${r === range ? 'bg-accent text-accent-foreground' : 'text-muted-foreground hover:bg-muted'}`}
          >
            {r}
          </button>
        ))}
      </div>
      <button
        type="button"
        aria-pressed={live}
        onClick={() => setLive(!live)}
        className="h-8 rounded-lg border border-border px-2.5 text-label-md"
      >
        {live ? 'Live' : 'Paused'}
      </button>
    </div>
  );
}
