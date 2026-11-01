// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.model.session;

import com.infenia.yukta.model.workflow.WorkflowDefinition;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Map;

/**
 * Everything a session store keeps for one session, as read back by {@code getAllConfigs}.
 *
 * @param sessionId the session identifier
 * @param name the human-readable name
 * @param description the description
 * @param initiator who created the session
 * @param initiatedTime when the session was created
 * @param tags additional tags
 * @param projectPath the working directory
 * @param executionTimeout the execution timeout in seconds
 * @param fileLogDir the modified-files log directory
 * @param resultLogDir the results log directory
 * @param workflows the workflow definitions
 */
@SuppressFBWarnings("EI_EXPOSE_REP")
public record SessionSnapshot(
    String sessionId,
    String name,
    String description,
    String initiator,
    String initiatedTime,
    Map<String, String> tags,
    String projectPath,
    Long executionTimeout,
    String fileLogDir,
    String resultLogDir,
    Map<String, WorkflowDefinition> workflows) {

  /**
   * Builds the record from the nine values the stores read one by one, in the order projectPath,
   * executionTimeout, fileLogDir, resultLogDir, initiator, initiatedTime, tags, description,
   * workflows.
   *
   * @param sessionId the session identifier
   * @param name the session name
   * @param v the nine values
   * @return the record
   */
  @SuppressWarnings("unchecked")
  public static SessionSnapshot fromValues(
      final String sessionId, final String name, final Object... v) {
    return new SessionSnapshot(
        sessionId,
        name,
        (String) v[7],
        (String) v[4],
        (String) v[5],
        (Map<String, String>) v[6],
        (String) v[0],
        (Long) v[1],
        (String) v[2],
        (String) v[3],
        (Map<String, WorkflowDefinition>) v[8]);
  }
}
