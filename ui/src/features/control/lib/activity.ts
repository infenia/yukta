// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

/** An event of /api/events/stream: the kind and severity wrap the event itself. */
export interface ActivityEvent {
  type: string;
  severity: 'INFO' | 'WARN' | 'ERROR';
  rootExecutionId: string;
  depth: number;
  event: {
    executionId: string;
    timestamp: string;
    workflowId?: string;
    nodeId?: string;
    status?: string;
    command?: string;
    applied?: boolean;
    reason?: string | null;
    line?: string;
    count?: number;
  };
}

/** A buffered event with a number that never repeats, for list keys. */
export interface Entry extends ActivityEvent {
  seq: number;
}

/** Most events kept; the oldest are dropped first and counted. */
export const MAX_EVENTS = 2000;

export const EVENT_TYPES = [
  'NodeStatusChanged',
  'WorkflowStatusChanged',
  'ControlOutcome',
  'LogLine',
  'EventsMissed',
] as const;
export const SEVERITIES = ['INFO', 'WARN', 'ERROR'] as const;

/** One line saying what happened. */
export function describe(e: ActivityEvent): string {
  const { event } = e;
  switch (e.type) {
    case 'NodeStatusChanged':
      return `${event.nodeId} → ${event.status}`;
    case 'WorkflowStatusChanged':
      return `${event.workflowId} → ${event.status}`;
    case 'ControlOutcome':
      return `${event.command} ${event.applied ? 'applied' : `refused: ${event.reason}`}`;
    case 'LogLine':
      return `${event.nodeId}: ${event.line}`;
    default:
      return `${event.count} events missed`;
  }
}

/** What narrows the list; empty means everything. */
export interface ActivityFilters {
  types: string[];
  severities: string[];
  execution: string;
}

export function matches(e: ActivityEvent, f: ActivityFilters): boolean {
  return (
    (f.types.length === 0 || f.types.includes(e.type)) &&
    (f.severities.length === 0 || f.severities.includes(e.severity)) &&
    (f.execution === '' || e.event.executionId.includes(f.execution))
  );
}

/** Adds events to a buffer of at most MAX_EVENTS, counting what falls off the front. */
export function appendBounded(
  buffer: Entry[],
  added: Entry[]
): { buffer: Entry[]; dropped: number } {
  const all = buffer.concat(added);
  const dropped = Math.max(0, all.length - MAX_EVENTS);
  return { buffer: dropped ? all.slice(dropped) : all, dropped };
}
