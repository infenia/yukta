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
        className="border-border bg-surface text-body-md h-8 rounded-lg border px-2"
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
        className="border-border text-label-md h-8 rounded-lg border px-2.5"
      >
        {live ? 'Live' : 'Paused'}
      </button>
    </div>
  );
}
