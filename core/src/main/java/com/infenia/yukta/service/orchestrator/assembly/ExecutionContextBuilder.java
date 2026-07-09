// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.assembly;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

/**
 * Fluent builder for managing execution context in reactive streams.
 *
 * <p>ExecutionContextBuilder centralizes context key management and provides a fluent API for
 * building and applying context to reactive streams. All context values are stored internally and
 * immutable snapshots are created on each build() call.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * ExecutionContextBuilder builder = new ExecutionContextBuilder()
 *     .sessionId("session-123")
 *     .workflowId("workflow-456")
 *     .executionId("exec-789");
 *
 * Mono<Result> result = builder.applyContextTo(myMono);
 * }</pre>
 */
@NoArgsConstructor
public class ExecutionContextBuilder {

  /** Context key for session identifier. */
  public static final String CTX_SESSION_ID = "sessionId";

  /** Context key for workflow identifier. */
  public static final String CTX_WORKFLOW_ID = "workflowId";

  /** Context key for execution identifier. */
  public static final String CTX_EXECUTION_ID = "executionId";

  /** Context key for node identifier. */
  public static final String CTX_NODE_ID = "nodeId";

  /** Context key for payload data. */
  public static final String CTX_PAYLOAD = "payload";

  /** The session ID value. */
  @Nullable private String sessionIdValue;

  /** The workflow ID value. */
  @Nullable private String workflowIdValue;

  /** The execution ID value. */
  @Nullable private String executionIdValue;

  /** The node ID value. */
  @Nullable private String nodeIdValue;

  /** The payload data. */
  @Nullable private Map<String, Object> payloadValue;

  /**
   * Sets the session identifier.
   *
   * @param sessionId the session identifier
   * @return this builder for fluent chaining
   */
  public ExecutionContextBuilder sessionId(final String sessionId) {
    this.sessionIdValue = sessionId;
    return this;
  }

  /**
   * Sets the workflow identifier.
   *
   * @param workflowId the workflow identifier
   * @return this builder for fluent chaining
   */
  public ExecutionContextBuilder workflowId(final String workflowId) {
    this.workflowIdValue = workflowId;
    return this;
  }

  /**
   * Sets the execution identifier.
   *
   * @param executionId the execution identifier
   * @return this builder for fluent chaining
   */
  public ExecutionContextBuilder executionId(final String executionId) {
    this.executionIdValue = executionId;
    return this;
  }

  /**
   * Sets the node identifier.
   *
   * @param nodeId the node identifier
   * @return this builder for fluent chaining
   */
  public ExecutionContextBuilder nodeId(final String nodeId) {
    this.nodeIdValue = nodeId;
    return this;
  }

  /**
   * Sets the payload data.
   *
   * @param payload the payload as a map of key-value pairs
   * @return this builder for fluent chaining
   */
  public ExecutionContextBuilder payload(final Map<String, Object> payload) {
    // Make a defensive copy to prevent external modifications
    if (payload != null) {
      this.payloadValue = new ConcurrentHashMap<>(payload);
    }
    return this;
  }

  /**
   * Builds an immutable Context snapshot from the current builder state.
   *
   * <p>Each call to build() creates a new Context with a snapshot of the current values. This
   * allows multiple contexts to be built from the same builder without interference.
   *
   * @return a Context containing all set values
   */
  public Context build() {
    final Map<String, Object> contextMap = new ConcurrentHashMap<>();

    if (sessionIdValue != null) {
      contextMap.put(CTX_SESSION_ID, sessionIdValue);
    }
    if (workflowIdValue != null) {
      contextMap.put(CTX_WORKFLOW_ID, workflowIdValue);
    }
    if (executionIdValue != null) {
      contextMap.put(CTX_EXECUTION_ID, executionIdValue);
    }
    if (nodeIdValue != null) {
      contextMap.put(CTX_NODE_ID, nodeIdValue);
    }
    if (payloadValue != null) {
      contextMap.put(CTX_PAYLOAD, Collections.unmodifiableMap(payloadValue));
    }

    return Context.of(contextMap);
  }

  /**
   * Applies the built context to a Mono stream.
   *
   * @param mono the Mono to apply context to
   * @param <T> the type of values in the Mono
   * @return a Mono with the context applied
   */
  public <T> Mono<T> applyContextTo(final Mono<T> mono) {
    return mono.contextWrite(build());
  }

  /**
   * Applies the built context to a Flux stream.
   *
   * @param flux the Flux to apply context to
   * @param <T> the type of values in the Flux
   * @return a Flux with the context applied
   */
  public <T> Flux<T> applyContextTo(final Flux<T> flux) {
    return flux.contextWrite(build());
  }
}
