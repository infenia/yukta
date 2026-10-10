// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.provider;

import com.infenia.yukta.logging.api.PluginLogEntry;
import com.infenia.yukta.logging.api.PluginLogStore;
import com.infenia.yukta.mcp.dto.ExecutionLogs;
import com.infenia.yukta.mcp.util.RegexPatternValidator;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Reads persisted plugin logs from the log store, applying the same session-ownership check as the
 * web layer's log endpoint.
 */
@Component
@RequiredArgsConstructor
public class LogProvider {

  /** Hard cap on lines returned by one call. */
  public static final int MAX_LINES = 5_000;

  /** Store of persisted plugin log entries. */
  private final PluginLogStore logStore;

  /** Gateway used to resolve execution ownership. */
  private final ControlBusGateway controlBus;

  /**
   * Get the logs of a workflow execution.
   *
   * @param sessionId the session that owns the execution
   * @param executionId the execution identifier
   * @param tailLines optional maximum number of trailing lines to return
   * @param filterPattern optional regex applied to each formatted log line
   * @return Mono containing the execution logs; errors if the execution is unknown or the pattern
   *     is invalid
   */
  public Mono<ExecutionLogs> getExecutionLogs(
      final String sessionId,
      final String executionId,
      final Integer tailLines,
      final String filterPattern) {
    return Mono.defer(
        () -> {
          final Pattern pattern = RegexPatternValidator.compile(filterPattern);
          return ExecutionOwnership.require(controlBus, sessionId, executionId)
              .then(readTail(executionId, pattern, limit(tailLines)));
        });
  }

  private Mono<ExecutionLogs> readTail(
      final String executionId, final Pattern pattern, final int limit) {
    return Mono.defer(
        () -> {
          final AtomicInteger total = new AtomicInteger();
          return logStore
              .readExecution(executionId)
              .map(PluginLogEntry::format)
              .filter(line -> RegexPatternValidator.matches(line, pattern))
              .doOnNext(_ -> total.incrementAndGet())
              .takeLast(limit)
              .collectList()
              .map(lines -> new ExecutionLogs(executionId, total.get(), lines.size(), lines));
        });
  }

  /** Requested tail size, bounded so one call cannot flood an agent's context. */
  private static int limit(final Integer tailLines) {
    return tailLines == null || tailLines <= 0 ? MAX_LINES : Math.min(tailLines, MAX_LINES);
  }
}
