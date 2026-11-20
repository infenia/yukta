// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { PluginSummary } from '@/features/plugins/types';
import { pluginLabel } from '@/features/workflow/lib/pluginIcons';
import type { PluginCategory } from '@/features/workflow/types/workflow';

export const STEP_CATEGORIES: readonly PluginCategory[] = [
  'TRIGGER',
  'PROCESSOR',
  'TERMINAL',
];

const TITLES: Record<PluginCategory, string> = {
  TRIGGER: 'Triggers',
  PROCESSOR: 'Processors',
  TERMINAL: 'Terminals',
};

const RECENT_KEY = 'yukta.recentSteps';
const MAX_RECENT = 5;

/** The step types picked last, newest first. Storage can be blocked, so it never throws. */
export function readRecent(): string[] {
  try {
    const stored: unknown = JSON.parse(
      localStorage.getItem(RECENT_KEY) ?? '[]'
    );
    return Array.isArray(stored)
      ? stored.filter((t): t is string => typeof t === 'string')
      : [];
  } catch {
    return [];
  }
}

export function rememberRecent(type: string): void {
  try {
    const next = [type, ...readRecent().filter((t) => t !== type)];
    localStorage.setItem(RECENT_KEY, JSON.stringify(next.slice(0, MAX_RECENT)));
  } catch {
    // storage is blocked: recents are a convenience
  }
}

export interface StepGroup {
  title: string;
  plugins: PluginSummary[];
}

/**
 * What the picker lists: the plugins whose category is allowed and whose type, name or
 * description holds every word of the query, grouped by category. With no query, the recently
 * picked ones come first.
 */
export function groupSteps(
  plugins: readonly PluginSummary[],
  allowed: readonly PluginCategory[],
  query: string,
  recent: readonly string[]
): StepGroup[] {
  const words = query.toLowerCase().split(/\s+/).filter(Boolean);
  const matches = (p: PluginSummary) => {
    const text =
      `${p.type} ${pluginLabel(p.type)} ${p.description ?? ''}`.toLowerCase();
    return words.every((w) => text.includes(w));
  };
  const shown = plugins.filter(
    (p) => allowed.includes(p.category) && matches(p)
  );
  const groups: StepGroup[] = [];
  const recents = recent.flatMap((type) =>
    shown.filter((p) => p.type === type)
  );
  if (words.length === 0 && recents.length > 0) {
    groups.push({ title: 'Recent', plugins: recents });
  }
  allowed.forEach((category) => {
    const members = shown.filter((p) => p.category === category);
    if (members.length > 0) {
      groups.push({ title: TITLES[category], plugins: members });
    }
  });
  return groups;
}
