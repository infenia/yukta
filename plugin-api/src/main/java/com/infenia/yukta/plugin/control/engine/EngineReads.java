// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control.engine;

import com.infenia.yukta.plugin.input.InputRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * What an engine may read: the executions and open input requests of what its supervisor owns,
 * newest first and bounded. It never returns node outputs or message payloads.
 */
public interface EngineReads {

  /** Reads nothing; what an engine gets before the supervisor is attached. */
  EngineReads NONE =
      new EngineReads() {
        @Override
        public Flux<RunView> executions(final @Nullable String status, final int limit) {
          return Flux.empty();
        }

        @Override
        public Flux<InputRequest> openInputs(final int limit) {
          return Flux.empty();
        }
      };

  /**
   * One execution.
   *
   * @param sessionId its session
   * @param executionId its id
   * @param workflowId its workflow
   * @param status its status name
   * @param startTime when it started, or {@code null}
   */
  record RunView(
      String sessionId,
      String executionId,
      String workflowId,
      String status,
      @Nullable Instant startTime) {}

  /**
   * One execution with the status of its nodes.
   *
   * @param run the execution
   * @param nodes the status of each node the supervisor owns, by node id
   */
  record RunDetail(RunView run, Map<String, String> nodes) {

    /** Keeps an unmodifiable copy. */
    public RunDetail {
      nodes = Map.copyOf(nodes);
    }
  }

  /**
   * The shape of a workflow: no configuration values, so no secret.
   *
   * @param workflowId the workflow
   * @param nodes the nodes the supervisor owns
   * @param edges the edges between them
   */
  record WorkflowView(String workflowId, List<NodeView> nodes, List<EdgeView> edges) {

    /** Keeps unmodifiable copies. */
    public WorkflowView {
      nodes = List.copyOf(nodes);
      edges = List.copyOf(edges);
    }
  }

  /**
   * One node of a workflow.
   *
   * @param nodeId its id
   * @param type its plugin type
   * @param group its stage, or {@code null}
   */
  record NodeView(String nodeId, String type, @Nullable String group) {}

  /**
   * One edge of a workflow.
   *
   * @param source the node it leaves
   * @param target the node it enters
   */
  record EdgeView(String source, String target) {}

  /**
   * The scheduler, for the owner of the server.
   *
   * @param maxConcurrent the most runs at once, 0 for no limit
   * @param maxPerWorkflow the most runs of one workflow, 0 for no limit
   * @param running runs going now
   * @param queued runs waiting
   */
  record SchedulerView(int maxConcurrent, int maxPerWorkflow, int running, int queued) {}

  /**
   * The owned executions.
   *
   * @param status only this status (a {@code TaskStatus} name), or {@code null} for all
   * @param limit most rows, at most 200
   * @return the executions
   */
  Flux<RunView> executions(@Nullable String status, int limit);

  /**
   * The open and escalated input requests of what is owned.
   *
   * @param limit most rows, at most 200
   * @return the requests
   */
  Flux<InputRequest> openInputs(int limit);

  /**
   * The executions this supervisor paused and has not resumed or stopped (its hold ledger).
   *
   * @return their ids; none by default
   */
  default Flux<String> held() {
    return Flux.empty();
  }

  /**
   * One owned execution with the status of its nodes.
   *
   * @param executionId the execution
   * @return it, or empty when it is unknown or not owned
   */
  default Mono<RunDetail> execution(final String executionId) {
    return Mono.empty();
  }

  /**
   * The shape of a workflow the supervisor owns something in.
   *
   * @param sessionId the session
   * @param workflowId the workflow
   * @return it, or empty when it is unknown or not owned
   */
  default Mono<WorkflowView> workflow(final String sessionId, final String workflowId) {
    return Mono.empty();
  }

  /**
   * The newest log lines of an owned execution, redacted and cut to a bounded length.
   *
   * @param executionId the execution
   * @param tail how many lines at most (up to 200)
   * @return the lines, oldest first; empty when the execution is unknown or not owned
   */
  default Flux<String> logs(final String executionId, final int tail) {
    return Flux.empty();
  }

  /**
   * The scheduler limits and load.
   *
   * @return them, or empty unless the supervisor owns the server
   */
  default Mono<SchedulerView> scheduler() {
    return Mono.empty();
  }
}
