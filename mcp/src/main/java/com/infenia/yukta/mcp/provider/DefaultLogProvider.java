// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.provider;

import com.infenia.yukta.logging.api.PluginLogEntry;
import com.infenia.yukta.logging.api.PluginLogStore;
import com.infenia.yukta.mcp.dto.ExecutionLogs;
import com.infenia.yukta.mcp.util.RegexPatternValidator;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Default implementation of LogProvider. Reads persisted plugin logs from the log store, applying
 * the same session-ownership check as the web layer's log endpoint.
 */
@Component
@RequiredArgsConstructor
public class DefaultLogProvider implements LogProvider {

  /** Store of persisted plugin log entries. */
  private final PluginLogStore logStore;

  /** Gateway used to resolve execution ownership. */
  private final ControlBusGateway controlBus;

  @Override
  public Mono<ExecutionLogs> getExecutionLogs(
      final String sessionId,
      final String executionId,
      final Integer tailLines,
      final String filterPattern) {
    return Mono.fromRunnable(() -> RegexPatternValidator.validatePattern(filterPattern))
        .then(requireOwnership(sessionId, executionId))
        .thenMany(logStore.readExecution(executionId))
        .map(PluginLogEntry::format)
        .filter(line -> RegexPatternValidator.matches(line, filterPattern))
        .collectList()
        .map(lines -> toExecutionLogs(executionId, lines, tailLines));
  }

  private ExecutionLogs toExecutionLogs(
      final String executionId, final List<String> lines, final Integer tailLines) {
    final List<String> returned =
        tailLines == null || tailLines <= 0 || tailLines >= lines.size()
            ? lines
            : lines.subList(lines.size() - tailLines, lines.size());
    return new ExecutionLogs(executionId, lines.size(), returned.size(), returned);
  }

  /**
   * Verify that the execution exists and belongs to the session. A missing execution and a session
   * mismatch yield the same error so callers cannot probe executions of other sessions.
   */
  private Mono<WorkflowProgress> requireOwnership(
      final String sessionId, final String executionId) {
    return Mono.fromCallable(() -> controlBus.getCurrentProgress(executionId))
        .subscribeOn(Schedulers.boundedElastic())
        .filter(progress -> progress.sessionId().equals(sessionId))
        .switchIfEmpty(
            Mono.error(
                () ->
                    new IllegalArgumentException(
                        "Execution not found: "
                            + executionId
                            + ". Use get_workflow_history to list executions.")));
  }
}
