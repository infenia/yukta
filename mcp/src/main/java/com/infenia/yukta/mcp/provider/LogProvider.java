// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.provider;

import com.infenia.yukta.logging.api.PluginLogStore;
import com.infenia.yukta.mcp.dto.ExecutionLogs;
import com.infenia.yukta.mcp.util.LogSanitizer;
import com.infenia.yukta.mcp.util.RegexPatternValidator;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
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
              .then(readTail(executionId, pattern, limit(tailLines), afterCursor));
        });
  }

  /**
   * Without a cursor the newest {@code limit} matching lines are returned (a tail). With one, the
   * oldest {@code limit} matching lines after it, and the next cursor stops after the last line
   * returned, so a follower that falls behind catches up on the next call instead of losing lines.
   */
  private Mono<ExecutionLogs> readTail(
      final String executionId,
      final Pattern pattern,
      final int limit,
      @Nullable final Integer afterCursor) {
    final boolean forward = afterCursor != null;
    final int after = cursor(afterCursor);
    return Mono.defer(
        () -> {
          final AtomicInteger total = new AtomicInteger();
          final AtomicInteger stored = new AtomicInteger();
          final Deque<Line> kept = new ArrayDeque<>();
          return logStore
              .readExecution(executionId)
              .doOnNext(_ -> stored.incrementAndGet())
              .index()
              .skip(after)
              .map(t -> new Line(t.getT1().intValue(), sanitizer.clean(t.getT2().format())))
              .filter(line -> RegexPatternValidator.matches(line.text(), pattern))
              .doOnNext(
                  line -> {
                    total.incrementAndGet();
                    if (!forward || kept.size() < limit) {
                      kept.addLast(line);
                    }
                    if (!forward && kept.size() > limit) {
                      kept.removeFirst();
                    }
                  })
              .then(
                  Mono.fromSupplier(
                      () -> result(executionId, forward, total.get(), stored.get(), after, kept)));
        });
  }

  private ExecutionLogs result(
      final String executionId,
      final boolean forward,
      final int total,
      final int stored,
      final int after,
      final Deque<Line> kept) {
    final List<Line> shown = capChars(List.copyOf(kept), forward);
    final List<String> lines = shown.stream().map(Line::text).toList();
    final int end = Math.max(stored, after);
    // Following: stop after the last line returned while matching lines remain, else after the
    // scan.
    final int cursor =
        forward && shown.size() < total ? shown.get(shown.size() - 1).index() + 1 : end;
    return new ExecutionLogs(
        executionId,
        total,
        lines.size(),
        total - lines.size(),
        lines,
        cursor,
        ExecutionLogs.UNTRUSTED_NOTICE);
  }

  /**
   * Keeps the lines that fit in {@code maxChars}: the oldest ones when following, the newest ones
   * for a tail. The first one in that order is always kept, cut to {@code maxChars} if it alone is
   * longer, so a very long line cannot stop a follower.
   */
  private List<Line> capChars(final List<Line> lines, final boolean forward) {
    final List<Line> ordered = forward ? lines : lines.reversed();
    final List<Line> out = new ArrayList<>();
    long used = 0;
    for (final Line line : ordered) {
      final int length = line.text().length();
      if (maxChars > 0 && !out.isEmpty() && used + length > maxChars) {
        break;
      }
      final boolean cut = maxChars > 0 && length > maxChars;
      out.add(cut ? new Line(line.index(), line.text().substring(0, maxChars)) : line);
      used += cut ? maxChars : length;
    }
    return forward ? out : out.reversed();
  }

  /** A returned line and the position of its entry in the store. */
  private record Line(int index, String text) {}

  /** Entries to skip: a missing or negative cursor starts at the beginning. */
  private static int cursor(final Integer afterCursor) {
    return afterCursor == null || afterCursor < 0 ? 0 : afterCursor;
  }

  /** Requested tail size, bounded so one call cannot flood an agent's context. */
  private static int limit(final Integer tailLines) {
    return tailLines == null || tailLines <= 0 ? MAX_LINES : Math.min(tailLines, MAX_LINES);
  }
}
