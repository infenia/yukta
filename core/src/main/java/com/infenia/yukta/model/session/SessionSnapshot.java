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
    Map<String, WorkflowDefinition> workflows) {

  /**
   * Builds the record from the six values the stores read one by one, in the order projectPath,
   * initiator, initiatedTime, tags, description, workflows.
   *
   * @param sessionId the session identifier
   * @param name the session name
   * @param v the six values
   * @return the record
   */
  @SuppressWarnings("unchecked")
  public static SessionSnapshot fromValues(
      final String sessionId, final String name, final Object... v) {
    return new SessionSnapshot(
        sessionId,
        name,
        (String) v[4],
        (String) v[1],
        (String) v[2],
        (Map<String, String>) v[3],
        (String) v[0],
        (Map<String, WorkflowDefinition>) v[5]);
  }
}
