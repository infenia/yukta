// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { createContext, type ReactNode } from 'react';
import type { WorkflowGraphEdge, WorkflowGraphNode } from '../types/workflow';

/**
 * What the editor draws under a node to add a step after it. The canvas draws it as given and
 * knows nothing of what it does; the run view gives none.
 */
export const NodeAdder = createContext<
  ((node: WorkflowGraphNode) => ReactNode) | null
>(null);

/** What the editor draws on an edge to add a step between its two nodes. */
export const EdgeAdder = createContext<
  ((edge: WorkflowGraphEdge) => ReactNode) | null
>(null);
