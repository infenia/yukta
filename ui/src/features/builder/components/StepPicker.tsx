// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { useState, type ReactElement } from 'react';
import { Input } from '@/components/ui/input';
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from '@/components/ui/popover';
import type { PluginSummary } from '@/features/plugins/types';
import {
  categoryAccent,
  categoryTint,
} from '@/features/workflow/lib/categoryAccent';
import { pluginIcon, pluginLabel } from '@/features/workflow/lib/pluginIcons';
import type { PluginCategory } from '@/features/workflow/types/workflow';
import {
  STEP_CATEGORIES,
  groupSteps,
  readRecent,
  rememberRecent,
} from './StepPicker.helpers';
import { Tip } from '@/components/ui/tooltip';

/**
 * A searchable list of the plugins a step can be, opened from `children` (the button). Rows show
 * the plugin's icon, name and description in its category's colour; one the operator has not
 * enabled is greyed out with how to enable it. Enter picks the first match.
 */
export function StepPicker({
  plugins,
  allowed = STEP_CATEGORIES,
  onPick,
  children,
}: {
  plugins: readonly PluginSummary[];
  /** Which categories can be picked here. */
  allowed?: readonly PluginCategory[];
  onPick: (type: string) => void;
  children: ReactElement;
}) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [recent, setRecent] = useState<string[]>([]);
  const groups = groupSteps(plugins, allowed, query, recent);
  const first = groups
    .flatMap((g) => g.plugins)
    .find((p) => p.enabled !== false);

  const pick = (type: string) => {
    rememberRecent(type);
    setOpen(false);
    onPick(type);
  };

  return (
    <Popover
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        if (next) {
          setQuery('');
          setRecent(readRecent());
        }
      }}
    >
      <PopoverTrigger render={children} />
      <PopoverContent className="flex w-96 flex-col gap-2 p-2" align="start">
        <form
          onSubmit={(event) => {
            event.preventDefault();
            if (first) pick(first.type);
          }}
        >
          <Input
            type="search"
            aria-label="Search steps"
            placeholder="Search steps"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
          />
        </form>
        {groups.length === 0 && (
          <p className="text-body-md text-muted-foreground px-2 py-4 text-center">
            {query.trim()
              ? `No step matches \u201c${query.trim()}\u201d.`
              : 'No plugins are available.'}
          </p>
        )}
        {groups.map((group) => (
          <section key={group.title} aria-label={group.title}>
            <h3 className="text-overline text-muted-foreground px-2 py-1">
              {group.title}
            </h3>
            <ul className="flex flex-col">
              {group.plugins.map((p) => {
                const disabled = p.enabled === false;
                return (
                  <li key={p.type}>
                    <Tip
                      label={disabled ? (p.enableWith ?? undefined) : undefined}
                      disabled={disabled}
                    >
                      <button
                        type="button"
                        onClick={() => pick(p.type)}
                        className="hover:bg-accent focus-visible:bg-accent flex w-full items-start gap-3 rounded-xl px-2 py-1.5 text-left outline-none disabled:cursor-not-allowed disabled:opacity-50 disabled:hover:bg-transparent"
                      >
                        <span
                          aria-hidden="true"
                          className="material-symbols-outlined mt-0.5 flex size-8 shrink-0 items-center justify-center rounded-lg text-xl"
                          style={{
                            color: categoryAccent(p.category),
                            background: categoryTint(p.category),
                          }}
                        >
                          {pluginIcon(p.type, p.category)}
                        </span>
                        <span className="min-w-0">
                          <span className="text-label-md text-on-surface block">
                            {pluginLabel(p.type)}
                          </span>
                          <span className="text-body-sm text-muted-foreground line-clamp-2 block">
                            {disabled
                              ? `Not enabled${p.enableWith ? `: ${p.enableWith}` : ''}`
                              : p.description}
                          </span>
                        </span>
                      </button>
                    </Tip>
                  </li>
                );
              })}
            </ul>
          </section>
        ))}
      </PopoverContent>
    </Popover>
  );
}
