// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What the control bus knows about one running supervisor.
 *
 * @param id the id in the audit log
 * @param kind {@code CONTROL_PLANE} for a control-plane plugin, otherwise {@code SUPERVISOR}
 * @param type the implementing class
 * @param order the review order, lowest first
 * @param dryRun whether its commands are only recorded
 * @param maxCommandsPerSecond how many commands it may issue per second
 * @param applied commands applied since startup
 * @param rejected commands that were refused
 * @param recorded commands only recorded because of dry-run
 * @param rateLimited commands dropped by the rate limit
 * @param scope the runs it watches: {@code GLOBAL}, {@code SESSION}, {@code WORKFLOW} or {@code
 *     SECTION}
 * @param scopeSessionId the session it is limited to, or {@code null}
 * @param scopeWorkflowId the workflow it is limited to, or {@code null}
 * @param scopeNodes the node selectors of a {@code SECTION} scope (node ids and {@code
 *     <prefix>.*}), or {@code null} for any other scope
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification = "the compact constructor stores an unmodifiable copy")
public record SupervisorInfo(
    String id,
    String kind,
    String type,
    int order,
    boolean dryRun,
    int maxCommandsPerSecond,
    long applied,
    long rejected,
    long recorded,
    long rateLimited,
    String scope,
    @Nullable String scopeSessionId,
    @Nullable String scopeWorkflowId,
    @Nullable List<String> scopeNodes) {

  /**
   * Keeps an immutable copy of the selectors.
   *
   * @param id the id in the audit log
   * @param kind the kind
   * @param type the implementing class
   * @param order the review order
   * @param dryRun whether its commands are only recorded
   * @param maxCommandsPerSecond how many commands it may issue per second
   * @param applied commands applied
   * @param rejected commands refused
   * @param recorded commands only recorded
   * @param rateLimited commands dropped by the rate limit
   * @param scope the scope kind
   * @param scopeSessionId the session, or {@code null}
   * @param scopeWorkflowId the workflow, or {@code null}
   * @param scopeNodes the selectors of a section, or {@code null}
   */
  public SupervisorInfo {
    scopeNodes = scopeNodes == null ? null : List.copyOf(scopeNodes);
  }
}
