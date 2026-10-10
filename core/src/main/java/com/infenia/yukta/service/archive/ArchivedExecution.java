// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.archive;

import com.infenia.yukta.model.workflow.PreparedWorkflow;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * What is needed to run an execution again after it has ended.
 *
 * @param executionId the execution
 * @param sessionId the session that owns it
 * @param workflowId the workflow it ran
 * @param prepared the exact graph it ran, so a restart uses the same definition even if the
 *     workflow was edited since
 * @param definitionVersion content hash of that graph
 * @param payload the trigger payload it started with
 */
public record ArchivedExecution(
    String executionId,
    String sessionId,
    String workflowId,
    PreparedWorkflow prepared,
    String definitionVersion,
    Map<String, Object> payload) {

  /**
   * Copies the payload, keeping null values.
   *
   * @param executionId the execution
   * @param sessionId the session that owns it
   * @param workflowId the workflow it ran
   * @param prepared the exact graph it ran
   * @param definitionVersion content hash of that graph
   * @param payload the trigger payload it started with
   */
  public ArchivedExecution {
    payload = Collections.unmodifiableMap(new HashMap<>(payload));
  }
}
