// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited

/** The run view of one execution. */
export const runPath = (
  sessionId: string,
  workflowId: string,
  executionId: string
) =>
  `/sessions/${encodeURIComponent(sessionId)}/workflow/${encodeURIComponent(workflowId)}?exec=${encodeURIComponent(executionId)}`;
