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

import com.infenia.yukta.mcp.util.RegexPatternValidator;
import com.infenia.yukta.model.monitoring.WorkflowExecutionSummary;
import com.infenia.yukta.service.TaskTrackerService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Default implementation of LogProvider. Handles log streaming and filtering with centralized regex
 * pattern validation.
 */
@Component
@RequiredArgsConstructor
@SuppressWarnings("PMD.UseObjectForClearerAPI")
public class DefaultLogProvider implements LogProvider {

  private final TaskTrackerService trackerService;

  @Override
  public Flux<String> streamSessionLogs(
      final String sessionId,
      final String workflowId,
      final String executionId,
      final String filterPattern) {
    return Mono.fromCallable(
            () -> {
              RegexPatternValidator.validatePattern(filterPattern);
              return trackerService.getHistory(sessionId);
            })
        .flatMapIterable(
            history ->
                history.stream()
                    .filter(
                        exec ->
                            (workflowId == null || exec.workflowId().equals(workflowId))
                                && (executionId == null || exec.executionId().equals(executionId)))
                    .map(this::formatExecution)
                    .filter(line -> RegexPatternValidator.matches(line, filterPattern))
                    .toList());
  }

  @Override
  public Mono<String> getWorkflowExecutionLogs(
      final String sessionId, final String executionId, final String filterPattern) {
    return Mono.fromCallable(
            () -> {
              RegexPatternValidator.validatePattern(filterPattern);
              return trackerService.getHistory(sessionId);
            })
        .map(
            history ->
                history.stream()
                    .filter(exec -> exec.executionId().equals(executionId))
                    .findFirst()
                    .orElseThrow(
                        () -> new IllegalArgumentException("Execution not found: " + executionId)))
        .map(this::formatExecution)
        .map(logs -> filterLogsByContent(logs, filterPattern));
  }

  private String formatExecution(final WorkflowExecutionSummary exec) {
    return String.format(
        "Execution: %s | Workflow: %s | Status: %s | Start: %s | End: %s",
        exec.executionId(), exec.workflowId(), exec.status(), exec.startTime(), exec.endTime());
  }

  private String filterLogsByContent(final String logs, final String pattern) {
    return RegexPatternValidator.matches(logs, pattern) ? logs : "";
  }
}
