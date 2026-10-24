// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.gateway;

import java.io.Serial;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Mono;

/** Lets a plugin run another workflow of the same session and wait for what it produced. */
@FunctionalInterface
public interface SubWorkflowGateway {

  /** Payload key under which a child run records how deep it is nested. */
  String DEPTH_KEY = "yukta.depth";

  /** Payload key under which a child run records the execution that started it. */
  String PARENT_KEY = "yukta.parentExecutionId";

  /** Deepest nesting allowed, so a workflow cannot start itself forever. */
  int MAX_DEPTH = 5;

  /**
   * What a finished child run produced.
   *
   * @param executionId the child run
   * @param results the payload of every message that reached a terminal node, in order
   * @param status how the child run ended (SUCCESS for a run that completed)
   * @param outcome the outcome of the OUTCOME_TERMINAL the child reached (SUCCESS, RETRY, ESCALATE,
   *     FAILED), or null when it reached none
   * @param reason why it ended there: the outcome terminal's reason, or the failure message
   */
  record Result(
      String executionId,
      List<Object> results,
      String status,
      @Nullable String outcome,
      @Nullable String reason) {

    /** Keeps an immutable copy of the results. */
    public Result {
      results = List.copyOf(results);
    }

    /**
     * A child run that completed without reaching an outcome terminal.
     *
     * @param executionId the child run
     * @param results the payload of every message that reached a terminal node, in order
     */
    public Result(final String executionId, final List<Object> results) {
      this(executionId, results, "SUCCESS", null, null);
    }
  }

  /**
   * A child run that failed. It keeps the failure's message, so a caller that does not route on the
   * outcome fails with the child's own error, and tells which run failed and how.
   */
  final class ChildFailed extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    /** The child run. */
    private final String childExecutionId;

    /** How it ended: FAILURE, or TIMED_OUT. */
    private final String endStatus;

    /**
     * Wraps the failure of a child run.
     *
     * @param executionId the child run
     * @param status how it ended
     * @param cause the failure
     */
    public ChildFailed(final String executionId, final String status, final Throwable cause) {
      super(cause.getMessage(), cause);
      this.childExecutionId = executionId;
      this.endStatus = status;
    }

    /**
     * The child run.
     *
     * @return its execution id
     */
    public String executionId() {
      return childExecutionId;
    }

    /**
     * How the child run ended.
     *
     * @return FAILURE or TIMED_OUT
     */
    public String status() {
      return endStatus;
    }
  }

  /**
   * Runs a workflow and completes when it has ended.
   *
   * @param sessionId the session that owns the workflow
   * @param workflowId the workflow to run
   * @param payload the start payload of the child run
   * @param parentExecutionId the run that starts it, recorded in the child payload
   * @param depth how deep the child is nested (1 for a child of a top-level run)
   * @return the result; errors if the workflow is unknown or nested too deep, and with {@link
   *     ChildFailed} if the run fails
   */
  Mono<Result> run(
      String sessionId,
      String workflowId,
      Map<String, Object> payload,
      String parentExecutionId,
      int depth);
}
