// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.provider;

import com.infenia.yukta.logging.api.PluginLogStore;
import com.infenia.yukta.mcp.dto.ExecutionLogs;
import com.infenia.yukta.mcp.util.LogSanitizer;
import com.infenia.yukta.mcp.util.RegexPatternValidator;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Reads persisted plugin logs from the log store, applying the same session-ownership check as the
 * web layer's log endpoint.
 */
@Component
public class LogProvider {

  /** Hard cap on lines returned by one call. */
  public static final int MAX_LINES = 5_000;

  /** Store of persisted plugin log entries. */
  private final PluginLogStore logStore;

  /** Gateway used to resolve execution ownership. */
  private final ControlBusGateway controlBus;

  /** Removes secrets and control characters from every line before the filter sees it. */
  private final LogSanitizer sanitizer;

  /** Most characters one call returns; the oldest lines are dropped first. 0 means no cap. */
  private final int maxChars;

  /**
   * Create the provider.
   *
   * @param logStore store of persisted plugin log entries
   * @param controlBus resolves execution ownership
   * @param redactPatterns regexes whose matches are replaced in every line; empty uses defaults
   * @param maxLineChars longest line returned, 0 for no cut
   * @param maxChars most characters one call returns, 0 for no cap
   */
  @Autowired
  @SuppressFBWarnings("EI_EXPOSE_REP2")
  public LogProvider(
      final PluginLogStore logStore,
      final ControlBusGateway controlBus,
      @Value("${yukta.mcp.log-redact-patterns:}") final List<String> redactPatterns,
      @Value("${yukta.mcp.log-max-line-chars:2000}") final int maxLineChars,
      @Value("${yukta.mcp.log-max-chars:200000}") final int maxChars) {
    this.logStore = logStore;
    this.controlBus = controlBus;
    this.sanitizer = new LogSanitizer(redactPatterns, maxLineChars);
    this.maxChars = maxChars;
  }

  /**
   * Create a provider with the default redaction, the default line cut and no total cap.
   *
   * @param logStore store of persisted plugin log entries
   * @param controlBus resolves execution ownership
   */
  public LogProvider(final PluginLogStore logStore, final ControlBusGateway controlBus) {
    this(logStore, controlBus, List.of(), 2_000, 0);
  }

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
    return getExecutionLogs(sessionId, executionId, tailLines, filterPattern, null);
  }

  /**
   * Get the logs of a workflow execution written after a cursor, so a caller following a running
   * execution reads each line once.
   *
   * @param sessionId the session that owns the execution
   * @param executionId the execution identifier
   * @param tailLines optional maximum number of trailing lines to return
   * @param filterPattern optional regex applied to each formatted log line
   * @param afterCursor the {@code nextCursor} of the previous call, or null to start at the
   *     beginning
   * @return Mono containing the execution logs; errors if the execution is unknown or the pattern
   *     is invalid
   */
  public Mono<ExecutionLogs> getExecutionLogs(
      final String sessionId,
      final String executionId,
      final Integer tailLines,
      final String filterPattern,
      final Integer afterCursor) {
    return Mono.defer(
        () -> {
          final Pattern pattern = RegexPatternValidator.compile(filterPattern);
          return ExecutionOwnership.require(controlBus, sessionId, executionId)
              .then(readTail(executionId, pattern, limit(tailLines), cursor(afterCursor)));
        });
  }

  private Mono<ExecutionLogs> readTail(
      final String executionId, final Pattern pattern, final int limit, final int after) {
    return Mono.defer(
        () -> {
          final AtomicInteger total = new AtomicInteger();
          final AtomicInteger stored = new AtomicInteger();
          return logStore
              .readExecution(executionId)
              .doOnNext(_ -> stored.incrementAndGet())
              .skip(after)
              .map(entry -> sanitizer.clean(entry.format()))
              .filter(line -> RegexPatternValidator.matches(line, pattern))
              .doOnNext(_ -> total.incrementAndGet())
              .takeLast(limit)
              .collectList()
              .map(
                  all -> {
                    final List<String> lines = capChars(all);
                    return new ExecutionLogs(
                        executionId,
                        total.get(),
                        lines.size(),
                        lines,
                        Math.max(stored.get(), after));
                  });
        });
  }

  /** Keeps the newest lines that fit in {@code maxChars}. */
  private List<String> capChars(final List<String> lines) {
    int from = lines.size();
    long used = 0;
    while (from > 0 && (maxChars <= 0 || used + lines.get(from - 1).length() <= maxChars)) {
      from--;
      used += lines.get(from).length();
    }
    return lines.subList(from, lines.size());
  }

  /** Entries to skip: a missing or negative cursor starts at the beginning. */
  private static int cursor(final Integer afterCursor) {
    return afterCursor == null || afterCursor < 0 ? 0 : afterCursor;
  }

  /** Requested tail size, bounded so one call cannot flood an agent's context. */
  private static int limit(final Integer tailLines) {
    return tailLines == null || tailLines <= 0 ? MAX_LINES : Math.min(tailLines, MAX_LINES);
  }
}
