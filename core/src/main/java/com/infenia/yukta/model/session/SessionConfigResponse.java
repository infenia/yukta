// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.model.session;

import com.infenia.yukta.model.workflow.WorkflowDefinition;
import java.util.Collections;
import java.util.Map;

/**
 * Response record containing complete session configuration data.
 *
 * @param sessionId the session identifier
 * @param name a human-readable name for the session
 * @param description a human-readable description of the session
 * @param initiator the initiator name
 * @param tags additional tags for the session
 * @param projectPath the project path
 * @param workflows map of workflow definitions (DAGs)
 */
public record SessionConfigResponse(
    String sessionId,
    String name,
    String description,
    String initiator,
    Map<String, String> tags,
    String projectPath,
    Map<String, WorkflowDefinition> workflows) {
  /** Compact constructor for defensive copying and immutability. */
  public SessionConfigResponse {
    tags = tags == null ? Map.of() : Map.copyOf(tags);
    workflows = workflows != null ? Map.copyOf(workflows) : Map.of();
  }

  @Override
  public Map<String, String> tags() {
    return Collections.unmodifiableMap(tags);
  }

  @Override
  public Map<String, WorkflowDefinition> workflows() {
    return Collections.unmodifiableMap(workflows);
  }
}
