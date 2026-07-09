// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.model.workflow;

import com.infenia.yukta.model.session.TaskResponse;
import reactor.core.publisher.Mono;

/**
 * Result of triggering a workflow execution.
 *
 * @param executionId the unique execution identifier
 * @param result Mono that emits the final task response when execution finishes
 */
public record WorkflowExecution(String executionId, Mono<TaskResponse> result) {}
