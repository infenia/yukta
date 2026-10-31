// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

import java.util.List;

/**
 * The supervisors that run and the bindings that attach profiles to a scope.
 *
 * @param running every running supervisor, those configured at startup and those attached since
 * @param bindings the bindings attached at run time
 * @param types the supervisor types a profile may use
 */
public record SupervisorListing(
    List<RunningSupervisor> running, List<SupervisorBindingInfo> bindings, List<String> types) {

  /** Keeps immutable copies of the lists. */
  public SupervisorListing {
    running = List.copyOf(running);
    bindings = List.copyOf(bindings);
    types = List.copyOf(types);
  }

  /**
   * One running supervisor.
   *
   * @param id the id in the audit log; the binding id for an attached one
   * @param kind {@code SUPERVISOR} or {@code CONTROL_PLANE}
   * @param scope {@code GLOBAL}, {@code SESSION} or {@code WORKFLOW}
   * @param scopeSessionId the session it is limited to, or null
   * @param scopeWorkflowId the workflow it is limited to, or null
   * @param dryRun whether its commands are only recorded
   * @param applied commands applied since startup
   * @param rejected commands that were refused
   */
  public record RunningSupervisor(
      String id,
      String kind,
      String scope,
      String scopeSessionId,
      String scopeWorkflowId,
      boolean dryRun,
      long applied,
      long rejected) {}
}
