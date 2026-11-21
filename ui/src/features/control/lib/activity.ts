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
    sessionId?: string;
    workflowId?: string;
    nodeId?: string;
    status?: string;
    command?: string;
    applied?: boolean;
    reason?: string | null;
    line?: string;
    count?: number;
    /** The input events: the request snapshot (InputRequested, InputEscalated) or its id. */
    request?: {
      nodeId: string;
      tier: string;
      spec: { kind: string; risk: string };
    };
    requestId?: string;
    decision?: string;
  };
}

/** A buffered event with a number that never repeats, for list keys. */
export interface Entry extends ActivityEvent {
  seq: number;
}

/** Most events kept; the oldest are dropped first and counted. */
export const MAX_EVENTS = 2000;

/**
 * The kinds of event, grouped the way a person looks for them. The log lines are a flood next to
 * the rest, so they are off until asked for.
 */
export const GROUPS = [
  { id: 'runs', label: 'Runs', types: ['WorkflowStatusChanged'] },
  { id: 'nodes', label: 'Nodes', types: ['NodeStatusChanged'] },
  { id: 'control', label: 'Control', types: ['ControlOutcome'] },
  {
    id: 'inputs',
    label: 'Inputs',
    types: [
      'InputRequested',
      'InputEscalated',
      'InputAnswered',
      'InputExpired',
      'InputCancelled',
    ],
  },
  { id: 'logs', label: 'Logs', types: ['LogLine'] },
  { id: 'gaps', label: 'Gaps', types: ['EventsMissed'] },
] as const;
export type GroupId = (typeof GROUPS)[number]['id'];

/** What is shown when no group was chosen: everything but the log lines. */
export const DEFAULT_GROUPS: readonly GroupId[] = GROUPS.filter(
  (g) => g.id !== 'logs'
).map((g) => g.id);

/** The group an event type belongs to. */
export const groupOf = (type: string): GroupId | undefined =>
  GROUPS.find((g) => (g.types as readonly string[]).includes(type))?.id;

/** The event types of the chosen groups. */
export const typesOf = (ids: readonly string[]): string[] =>
  GROUPS.filter((g) => ids.includes(g.id)).flatMap((g) => g.types);
export const SEVERITIES = ['INFO', 'WARN', 'ERROR'] as const;

/** One line saying what happened; the workflow and node have their own columns. */
export function describe(e: ActivityEvent): string {
  const { event } = e;
  switch (e.type) {
    case 'NodeStatusChanged':
      return String(event.status);
    case 'WorkflowStatusChanged':
      return String(event.status);
    case 'ControlOutcome':
      return `${event.command} ${event.applied ? 'applied' : `refused: ${event.reason}`}`;
    case 'LogLine':
      return String(event.line);
    case 'InputRequested':
      return `${event.request?.nodeId} asked for input (${event.request?.spec.kind}, ${event.request?.spec.risk} risk)`;
    case 'InputEscalated':
      return `input of ${event.request?.nodeId} escalated to ${event.request?.tier}`;
    case 'InputAnswered':
      return `input answered: ${event.decision}`;
    case 'InputExpired':
      return 'input request expired';
    case 'InputCancelled':
      return `input request cancelled: ${event.reason}`;
    default:
      return `${event.count} events missed`;
  }
}

/** What narrows the list; empty means everything. `text` matches the run, workflow or node. */
export interface ActivityFilters {
  types: string[];
  severities: string[];
  text: string;
}

/** Whether the run id, workflow or node of the event contains `text` (ignoring case). */
export function matchesText(e: ActivityEvent, text: string): boolean {
  if (text === '') return true;
  const needle = text.toLowerCase();
  return [e.event.executionId, e.event.workflowId, e.event.nodeId].some((v) =>
    v?.toLowerCase().includes(needle)
  );
}

export function matches(e: ActivityEvent, f: ActivityFilters): boolean {
  return (
    (f.types.length === 0 || f.types.includes(e.type)) &&
    (f.severities.length === 0 || f.severities.includes(e.severity)) &&
    matchesText(e, f.text)
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
