// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.model.workflow;

import java.util.Map;
import reactor.core.publisher.Mono;

/**
 * A functional interface for a pre-compiled workflow template that can be instantiated for
 * execution.
 */
@FunctionalInterface
public interface WorkflowTemplate {
  /**
   * Instantiates the workflow for a specific execution.
   *
   * @param executionId the unique execution identifier
   * @param payload the initial trigger payload
   * @return a Mono that completes when the workflow execution is finished
   */
  Mono<Void> instantiate(String executionId, Map<String, Object> payload);
}
