// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { describe, expect, it } from 'vitest';
import type { components } from '../api.generated';
import type { SessionDetails, SessionListItem, WorkflowSummary } from '@/features/sessions/types/session';
import type {
  ExecutionSummary,
  TaskProgress,
  UiDesign,
  WorkflowGraph,
  WorkflowGraphEdge,
  WorkflowGraphNode,
  WorkflowProgress,
} from '@/features/workflow/types/workflow';

type Schemas = components['schemas'];

/** Resolves to `true` only when both types have exactly the same property names. */
type SameKeys<A, B> = [keyof A] extends [keyof B] ? ([keyof B] extends [keyof A] ? true : false) : false;
const assertTrue = <T extends true>(): T => true as T;

/**
 * Compile-time contract check: each hand-written UI type must have the same fields as the
 * server schema generated into api.generated.ts (`pnpm run api:types`). A field added, removed
 * or renamed on the server breaks `tsc` here instead of failing at runtime.
 */
describe('API contract', () => {
  it('hand-written types have the same fields as the generated server schemas', () => {
    expect(assertTrue<SameKeys<SessionListItem, Schemas['SessionListItem']>>()).toBe(true);
    expect(assertTrue<SameKeys<SessionDetails, Schemas['SessionDetails']>>()).toBe(true);
    expect(assertTrue<SameKeys<WorkflowSummary, Schemas['WorkflowSummary']>>()).toBe(true);
    expect(assertTrue<SameKeys<WorkflowGraph, Schemas['WorkflowGraph']>>()).toBe(true);
    expect(assertTrue<SameKeys<WorkflowGraphNode, Schemas['WorkflowGraphNode']>>()).toBe(true);
    expect(assertTrue<SameKeys<WorkflowGraphEdge, Schemas['WorkflowGraphEdge']>>()).toBe(true);
    expect(assertTrue<SameKeys<UiDesign, Schemas['UiDesign']>>()).toBe(true);
    expect(assertTrue<SameKeys<ExecutionSummary, Schemas['WorkflowExecutionSummary']>>()).toBe(true);
    expect(assertTrue<SameKeys<WorkflowProgress, Schemas['WorkflowProgress']>>()).toBe(true);
    expect(assertTrue<SameKeys<TaskProgress, Schemas['TaskProgress']>>()).toBe(true);
  });
});
