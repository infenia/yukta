// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The supervisors that run, the configured supervisors and the attachments that run them.
 *
 * @param running every running supervisor, those configured at startup and those attached since
 * @param supervisors the configured supervisors (secrets are not shown)
 * @param attachments the attachments, made by hand or declared by definitions
 * @param types the supervisor types a supervisor may have
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SupervisorListing(
    List<RunningSupervisor> running,
    List<ConfiguredSupervisor> supervisors,
    List<SupervisorAttachmentInfo> attachments,
    List<String> types) {

  /**
   * One configured supervisor.
   *
   * @param id the supervisor id
   * @param type the supervisor type
   * @param description what it is for, or null
   * @param dryRun whether its commands are only recorded
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ConfiguredSupervisor(
      String id, String type, @Nullable String description, boolean dryRun) {}

  /** Keeps immutable copies of the lists. */
  public SupervisorListing {
    running = List.copyOf(running);
    supervisors = List.copyOf(supervisors);
    attachments = List.copyOf(attachments);
    types = List.copyOf(types);
  }

  /**
   * One running supervisor.
   *
   * @param id the id in the audit log; the binding id for an attached one
   * @param kind {@code SUPERVISOR} or {@code CONTROL_PLANE}
   * @param scope {@code GLOBAL}, {@code SESSION}, {@code WORKFLOW} or {@code SECTION}
   * @param scopeSessionId the session it is limited to, or null
   * @param scopeWorkflowId the workflow it is limited to, or null
   * @param scopeNodes the node selectors of a {@code SECTION} scope, or null
   * @param dryRun whether its commands are only recorded
   * @param applied commands applied since startup
   * @param rejected commands that were refused
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "the compact constructor stores an unmodifiable copy")
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record RunningSupervisor(
      String id,
      String kind,
      String scope,
      @Nullable String scopeSessionId,
      @Nullable String scopeWorkflowId,
      @Nullable List<String> scopeNodes,
      boolean dryRun,
      long applied,
      long rejected) {

    /** Keeps an immutable copy of the selectors. */
    public RunningSupervisor {
      if (scopeNodes != null) {
        scopeNodes = List.copyOf(scopeNodes);
      }
    }
  }
}
