/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.infenia.yukta.mcp.provider;

import com.infenia.yukta.model.monitoring.WorkflowExecutionSummary;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.service.SessionService;
import com.infenia.yukta.service.TaskTrackerService;
import com.infenia.yukta.service.WorkflowService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Default implementation of WorkflowExecutionProvider. Handles workflow execution operations
 * including definition retrieval, trigger operations, and status monitoring.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultWorkflowExecutionProvider implements WorkflowExecutionProvider {

  private final WorkflowService workflowService;
  private final SessionService sessionService;
  private final TaskTrackerService trackerService;
  private final ObjectMapper objectMapper;

  @Override
  public Mono<WorkflowDefinition> getWorkflowDetails(
      final String sessionId, final String workflowId) {
    return sessionService.getSessionWorkflow(sessionId, workflowId);
  }

  @Override
  public Mono<String> triggerWorkflow(
      final String sessionId, final String workflowId, final String payloadJson) {
    final Mono<String> result;
    if (payloadJson != null && !payloadJson.isBlank()) {
      result = parseAndTrigger(sessionId, workflowId, payloadJson);
    } else {
      result =
          Mono.just(workflowService.runWorkflow(sessionId, workflowId, Map.of()).executionId());
    }
    return result;
  }

  @Override
  public Mono<WorkflowExecutionSummary> getWorkflowStatus(
      final String sessionId, final String executionId) {
    return Mono.fromCallable(() -> trackerService.getHistory(sessionId))
        .flatMapIterable(list -> list)
        .filter(s -> s.executionId().equals(executionId))
        .next();
  }

  private Mono<String> parseAndTrigger(
      final String sessionId, final String workflowId, final String payloadJson) {
    Mono<String> result;
    try {
      final Map<String, Object> payload =
          objectMapper.readValue(payloadJson, new TypeReference<>() {});
      result = Mono.just(workflowService.runWorkflow(sessionId, workflowId, payload).executionId());
    } catch (final tools.jackson.core.JacksonException e) {
      log.atWarn().setCause(e).log("Failed to parse workflow payload: {}", e.getMessage());
      result =
          Mono.error(new IllegalArgumentException("Invalid JSON payload: " + e.getMessage(), e));
    }
    return result;
  }
}
