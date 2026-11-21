// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import type { Me } from '@/lib/identity';
import type { InputAnswer, InputRequest } from '../api/inputs';

/**
 * The plugins that can raise an input request, and what each one does in plain words.
 * ponytail: MCP_TOOL_CALL counts by type only (the graph carries no config), so a node that never
 * asks still gets the tab; read the node config if that false positive annoys.
 */
export const ASKING_KINDS: Record<string, string> = {
  ASK_INPUT: 'Asks a question',
  WAIT_FOR_SIGNAL: 'Waits for approval',
  AGENT_SESSION: 'Agent asks before using a tool',
  MCP_TOOL_CALL: 'May ask before a tool call',
};

export interface AskingNode {
  nodeId: string;
  type: string;
  label: string;
}

/** The nodes of a workflow that can ask for input. */
export function askingNodes(
  nodes: { nodeId: string; type: string }[]
): AskingNode[] {
  return nodes
    .filter((node) => node.type in ASKING_KINDS)
    .map((node) => ({ ...node, label: ASKING_KINDS[node.type] }));
}

export type OutcomeTone = 'success' | 'destructive' | 'muted';

/** How an ended request reads in the history: a label, an icon and a tone. */
export function outcome(request: InputRequest): {
  label: string;
  icon: string;
  tone: OutcomeTone;
} {
  if (request.state === 'EXPIRED') {
    return { label: 'Expired', icon: 'timer_off', tone: 'destructive' };
  }
  if (request.state === 'CANCELLED') {
    return { label: 'Cancelled', icon: 'cancel', tone: 'muted' };
  }
  const decision = request.answer?.decision;
  if (decision === 'reject') {
    return { label: 'Rejected', icon: 'block', tone: 'destructive' };
  }
  if (decision === 'approve') {
    return { label: 'Approved', icon: 'check_circle', tone: 'success' };
  }
  return { label: 'Answered', icon: 'check_circle', tone: 'success' };
}

/** Who answered, for a person: "You", "Anonymous (no API key)" or the key; a supervisor says so. */
export function responderLabel(answer: InputAnswer, me: Me | null): string {
  if (answer.responderType === 'AUTO')
    return `${answer.responderId} (supervisor)`;
  if (me?.keyName && me.keyName === answer.responderId) return 'You';
  if (answer.responderId === 'anonymous') return 'Anonymous (no API key)';
  return answer.responderId;
}

/** A tier as a phrase: the built-in one-tier route lets anyone allowed answer. */
export function tierLabel(tier: string): string {
  return tier === 'default' ? 'anyone allowed to answer' : `the ${tier} tier`;
}

/**
 * The share of the time to answer that is left, 0 to 1. A tier change resets the deadline, so the
 * span starts at the last step of the chain, or at creation.
 */
export function deadlineShare(request: InputRequest, now: number): number {
  if (!request.deadline) return 1;
  const start = Date.parse(
    request.asked?.[request.asked.length - 1]?.at ?? request.createdAt
  );
  const end = Date.parse(request.deadline);
  if (Number.isNaN(start) || Number.isNaN(end) || end <= start) return 0;
  return Math.min(1, Math.max(0, (end - now) / (end - start)));
}
