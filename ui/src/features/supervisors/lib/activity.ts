// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { Decision } from '../types';

/** The decisions of one directive: one, or one per target when it named several. */
export interface DecisionGroup {
  id: string;
  items: Decision[];
}

/**
 * Decisions grouped by directive, in the order of their newest: a directive for several targets
 * is sent once per target with the key `<key>#<target>`, so those share the key before `#`. A
 * decision without a key is a group of its own.
 */
export function groupDecisions(decisions: Decision[]): DecisionGroup[] {
  const groups = new Map<string, DecisionGroup>();
  decisions.forEach((decision, index) => {
    const id = decision.key
      ? `${decision.supervisorId} ${decision.key.split('#')[0]}`
      : `#${index}`;
    const group = groups.get(id) ?? { id, items: [] };
    group.items.push(decision);
    groups.set(id, group);
  });
  return [...groups.values()];
}
