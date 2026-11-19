// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.model.workflow;

/**
 * One workflow of one session, as a key for maps and per-workflow serialisation. A record, so ids
 * that happen to contain a separator character cannot make two workflows collide.
 *
 * @param sessionId the session
 * @param workflowId the workflow
 */
public record WorkflowKey(String sessionId, String workflowId) {}
