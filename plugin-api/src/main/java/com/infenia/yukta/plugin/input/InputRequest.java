// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.input;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * An input request as the engine holds and shows it. The broker builds it; a plugin only builds an
 * {@link InputRequestSpec}. The prompt and context in {@code spec} are already redacted.
 *
 * @param id the request id, the handle for answering and escalating
 * @param key the idempotency key of the spec
 * @param sessionId the session of the run that asks
 * @param workflowId the workflow of that run
 * @param executionId the run that asks
 * @param nodeId the node that asks
 * @param spec the question, redacted
 * @param state where the request is in its life
 * @param version counts every change, so two answers at once produce one winner
 * @param tier the name of the tier of the route that holds the request now
 * @param targets the selectors the current tier targets
 * @param advice text a supervisor added when it escalated, for the next tier
 * @param deadline when the request expires, or {@code null} for none
 * @param createdAt when it was asked
 * @param updatedAt when it last changed
 * @since 0.1.0-alpha.4
 */
public record InputRequest(
    String id,
    String key,
    String sessionId,
    String workflowId,
    String executionId,
    String nodeId,
    InputRequestSpec spec,
    InputState state,
    long version,
    String tier,
    List<String> targets,
    @Nullable String advice,
    @Nullable Instant deadline,
    Instant createdAt,
    Instant updatedAt) {

  /**
   * Checks the parts and keeps an immutable copy of the targets.
   *
   * @param id the request id
   * @param key the idempotency key
   * @param sessionId the session
   * @param workflowId the workflow
   * @param executionId the run
   * @param nodeId the node
   * @param spec the question
   * @param state the state
   * @param version the version, zero or more
   * @param tier the current tier
   * @param targets the selectors of the current tier
   * @param advice advice for the next tier, or {@code null}
   * @param deadline the deadline, or {@code null}
   * @param createdAt when it was asked
   * @param updatedAt when it last changed
   */
  public InputRequest {
    InputLimits.requireId("id", id);
    InputLimits.requireId("key", key);
    InputLimits.requireId("sessionId", sessionId);
    InputLimits.requireId("workflowId", workflowId);
    InputLimits.requireId("executionId", executionId);
    InputLimits.requireId("nodeId", nodeId);
    Objects.requireNonNull(spec, "spec");
    Objects.requireNonNull(state, "state");
    if (version < 0) {
      throw new IllegalArgumentException("version must not be negative");
    }
    InputLimits.requireId("tier", tier);
    targets = targets == null ? List.of() : List.copyOf(targets);
    InputLimits.optionalText("advice", advice, InputLimits.TEXT_MAX_BYTES);
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
  }
}
