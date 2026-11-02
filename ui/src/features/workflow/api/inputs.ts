// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

import { api, fetchAllPages, fetchApi } from '@/lib/apiClient';

export type InputState =
  'OPEN' | 'ESCALATED' | 'ANSWERED' | 'EXPIRED' | 'CANCELLED';
export type InputKind = 'APPROVAL' | 'CHOICE' | 'TEXT' | 'STRUCTURED' | 'QUERY';
export type InputRisk = 'LOW' | 'MEDIUM' | 'HIGH';

export interface InputAnswer {
  decision: string;
  text: string | null;
  data: Record<string, unknown>;
  responderType: string;
  responderId: string;
  rationale: string | null;
  confidence: number | null;
  answeredAt: string;
}

/** An input request as GET /api/sessions/{id}/inputs returns it. */
export interface InputRequest {
  id: string;
  sessionId: string;
  workflowId: string;
  executionId: string;
  nodeId: string;
  state: InputState;
  version: number;
  kind: InputKind;
  prompt: string;
  context: Record<string, unknown>;
  options: { id: string; label: string }[];
  responseSchema: string | null;
  risk: InputRisk;
  tier: string;
  advice: string | null;
  deadline: string | null;
  createdAt: string;
  updatedAt: string;
  answer: InputAnswer | null;
}

export interface AnswerBody {
  decision: string;
  text?: string;
  data?: Record<string, unknown>;
  rationale?: string;
  expectedVersion?: number;
}

export interface EscalateBody {
  reason: string;
  advice?: string;
}

export const OPEN_STATES: InputState[] = ['OPEN', 'ESCALATED'];
export const HISTORY_STATES: InputState[] = [
  'ANSWERED',
  'EXPIRED',
  'CANCELLED',
];

export const listInputs = (
  sessionId: string,
  filters: { state: InputState[]; workflowId?: string }
) =>
  fetchAllPages<InputRequest>(
    api`/api/sessions/${sessionId}/inputs` +
      `?state=${filters.state.join(',')}` +
      (filters.workflowId
        ? `&workflowId=${encodeURIComponent(filters.workflowId)}`
        : ''),
    200,
    5
  );

export const getInput = (executionId: string, requestId: string) =>
  fetchApi<InputRequest>(
    api`/api/executions/${executionId}/inputs/${requestId}`
  );

const post = (
  executionId: string,
  requestId: string,
  action: 'answer' | 'escalate',
  body: AnswerBody | EscalateBody
) =>
  fetchApi<InputRequest>(
    api`/api/executions/${executionId}/inputs/${requestId}/` + action,
    { method: 'POST', body: JSON.stringify(body) }
  );

export const answerInput = (
  executionId: string,
  requestId: string,
  body: AnswerBody
) => post(executionId, requestId, 'answer', body);

export const escalateInput = (
  executionId: string,
  requestId: string,
  body: EscalateBody
) => post(executionId, requestId, 'escalate', body);
