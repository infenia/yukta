// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { SupervisorCategory, SupervisorTypeSummary } from '../types';

/** The catalog's groups, in order, with the colour of their dot. */
export const DECISION_MAKER_CATEGORIES: {
  key: SupervisorCategory;
  label: string;
  color: string;
}[] = [
  { key: 'RULES', label: 'Rules', color: 'var(--color-cat-processor)' },
  {
    key: 'EXTERNAL_SYSTEM',
    label: 'External system',
    color: 'var(--color-cat-trigger)',
  },
  { key: 'ON_THIS_SERVER', label: 'On this server', color: 'var(--success)' },
];

/** The colour of a decision maker's category; neutral for one the UI does not know. */
export const categoryColor = (category: string | undefined) =>
  DECISION_MAKER_CATEGORIES.find((c) => c.key === category)?.color ??
  'var(--muted-foreground)';

/** A decision maker's name and colour, from the catalog; the type id when it is not listed. */
export function decisionMaker(
  type: string,
  catalog: SupervisorTypeSummary[] | undefined
) {
  const found = catalog?.find((t) => t.type === type);
  return {
    name: found?.displayName || type,
    color: categoryColor(found?.category),
    icon: found?.icon ?? 'shield_person',
  };
}
