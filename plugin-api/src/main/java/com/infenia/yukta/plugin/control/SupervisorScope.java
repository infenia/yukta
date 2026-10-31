// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Which runs a supervisor watches and may veto: all of them, those of one session, or those of one
 * workflow.
 *
 * <p>A command that belongs to no run (such as {@code SetLimitsCommand}) has no session or
 * workflow, so only a {@link Global} supervisor reviews it.
 */
public sealed interface SupervisorScope {

  /** What kind of scope this is. */
  enum Kind {
    /** Every run. */
    GLOBAL,
    /** The runs of one session. */
    SESSION,
    /** The runs of one workflow. */
    WORKFLOW
  }

  /**
   * The kind of scope.
   *
   * @return the kind
   */
  Kind kind();

  /**
   * The session this scope is limited to.
   *
   * @return the session id, or {@code null} when the scope is not limited to a session
   */
  @Nullable String sessionId();

  /**
   * The workflow this scope is limited to.
   *
   * @return the workflow id, or {@code null} when the scope is not limited to a workflow
   */
  @Nullable String workflowId();

  /**
   * Tells whether a run falls within the scope.
   *
   * @param runSessionId the session of the run, or {@code null} when unknown or not a run
   * @param runWorkflowId the workflow of the run, or {@code null} when unknown or not a run
   * @return true if a supervisor with this scope watches the run
   */
  boolean matches(@Nullable String runSessionId, @Nullable String runWorkflowId);

  /** Every run, and the commands that belong to no run. */
  record Global() implements SupervisorScope {

    @Override
    public Kind kind() {
      return Kind.GLOBAL;
    }

    @Override
    public @Nullable String sessionId() {
      return null;
    }

    @Override
    public @Nullable String workflowId() {
      return null;
    }

    @Override
    public boolean matches(
        final @Nullable String runSessionId, final @Nullable String runWorkflowId) {
      return true;
    }
  }

  /**
   * The runs of one session.
   *
   * @param sessionId the session
   */
  record Session(String sessionId) implements SupervisorScope {

    /** Rejects a missing session id. */
    public Session {
      Objects.requireNonNull(sessionId, "sessionId");
    }

    @Override
    public Kind kind() {
      return Kind.SESSION;
    }

    @Override
    public @Nullable String workflowId() {
      return null;
    }

    @Override
    public boolean matches(
        final @Nullable String runSessionId, final @Nullable String runWorkflowId) {
      return sessionId.equals(runSessionId);
    }
  }

  /**
   * The runs of one workflow.
   *
   * @param workflowId the workflow
   * @param sessionId the session that owns it; {@code null} for the workflow of that id in any
   *     session
   */
  record Workflow(String workflowId, @Nullable String sessionId) implements SupervisorScope {

    /** Rejects a missing workflow id. */
    public Workflow {
      Objects.requireNonNull(workflowId, "workflowId");
    }

    @Override
    public Kind kind() {
      return Kind.WORKFLOW;
    }

    @Override
    public boolean matches(
        final @Nullable String runSessionId, final @Nullable String runWorkflowId) {
      return workflowId.equals(runWorkflowId)
          && (sessionId == null || sessionId.equals(runSessionId));
    }
  }
}
