// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.model.session;

import java.util.Map;

/**
 * What a list of sessions shows of one session: its settings and how many workflows it has, without
 * the workflow definitions.
 *
 * @param sessionId the session identifier
 * @param name the session name
 * @param description what it is for
 * @param initiator who created it
 * @param tags its tags
 * @param projectPath its working directory
 * @param workflowCount how many workflows it has
 */
public record SessionSummary(
    String sessionId,
    String name,
    String description,
    String initiator,
    Map<String, String> tags,
    String projectPath,
    int workflowCount) {

  /** Keeps an unmodifiable copy of the tags. */
  public SessionSummary {
    tags = Map.copyOf(tags);
  }

  /**
   * The summary of a whole session.
   *
   * @param snapshot the session with its workflows
   * @return its summary
   */
  public static SessionSummary from(final SessionSnapshot snapshot) {
    return new SessionSummary(
        snapshot.sessionId(),
        snapshot.name(),
        snapshot.description(),
        snapshot.initiator(),
        snapshot.tags(),
        snapshot.projectPath(),
        snapshot.workflows().size());
  }
}
