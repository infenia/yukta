// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.gateway;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
   */
  record Result(String executionId, List<Object> results) {

    /** Keeps an unmodifiable copy of the results. */
    public Result {
      results = Collections.unmodifiableList(new ArrayList<>(results));
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
   * @return the result; errors if the workflow is unknown, nested too deep, or the run fails
   */
  Mono<Result> run(
      String sessionId,
      String workflowId,
      Map<String, Object> payload,
      String parentExecutionId,
      int depth);
}
