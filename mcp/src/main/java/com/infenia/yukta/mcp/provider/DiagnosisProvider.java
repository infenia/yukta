// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.provider;

import com.infenia.yukta.logging.api.LogStream;
import com.infenia.yukta.mcp.dto.ExecutionLogs;
import com.infenia.yukta.mcp.dto.FailureDigestResult;
import com.infenia.yukta.mcp.dto.LogSearchResult;
import com.infenia.yukta.mcp.util.LogSanitizer;
import com.infenia.yukta.plugin.core.LogLevel;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.diagnosis.FailureDigest;
import com.infenia.yukta.service.diagnosis.FailureDigestService;
import com.infenia.yukta.service.diagnosis.LogSearchMode;
import com.infenia.yukta.service.diagnosis.RunLogQuery;
import com.infenia.yukta.service.diagnosis.RunLogSearch;
import com.infenia.yukta.service.diagnosis.RunLogSearcher;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * The token-efficient reads of a run for agents: the failure digest and the log search. Both check
 * that the run belongs to the caller's session and redact every line like {@link LogProvider}.
 */
@Component
@RegisterReflectionForBinding({RunLogSearch.class, FailureDigest.class})
public class DiagnosisProvider {

  private final ControlBusGateway controlBus;
  private final RunLogSearcher searcher;
  private final FailureDigestService digests;
  private final LogSanitizer sanitizer;

  /**
   * Creates the provider.
   *
   * @param controlBus resolves which session owns a run
   * @param searcher searches run logs
   * @param digests builds failure digests
   * @param redactPatterns extra secret patterns, as for {@code get_execution_logs}
   * @param maxLineChars longest line kept, as for {@code get_execution_logs}
   */
  @Autowired
  public DiagnosisProvider(
      final ControlBusGateway controlBus,
      final RunLogSearcher searcher,
      final FailureDigestService digests,
      @Value("${yukta.mcp.log-redact-patterns:}") final List<String> redactPatterns,
      @Value("${yukta.mcp.log-max-line-chars:2000}") final int maxLineChars) {
    this.controlBus = controlBus;
    this.searcher = searcher;
    this.digests = digests;
    this.sanitizer = new LogSanitizer(redactPatterns, maxLineChars);
  }

  /**
   * The failure digest of a run.
   *
   * @param sessionId the caller's session
   * @param executionId the run
   * @return the digest; "Execution not found" for a run of another session
   */
  public Mono<FailureDigestResult> getFailureDigest(
      final String sessionId, final String executionId) {
    return ExecutionOwnership.require(controlBus, sessionId, executionId)
        .flatMap(progress -> digests.digest(progress, sanitizer::clean))
        .map(digest -> new FailureDigestResult(digest, ExecutionLogs.UNTRUSTED_NOTICE));
  }

  /**
   * Searches the logs of a run.
   *
   * @param sessionId the caller's session
   * @param executionId the run
   * @param includeChildren whether its sub-runs are searched too
   * @param nodes comma-separated node ids, or {@code null} for all
   * @param stream {@code stdout}, {@code stderr} or {@code all}/{@code null}
   * @param level {@code ERROR}, {@code WARN}, {@code INFO} or {@code null}
   * @param grep a regular expression, or {@code null}
   * @param context context lines around a match
   * @param mode what to return, {@code null} for COUNT
   * @param maxMatches the most matching lines returned
   * @param cursor the previous answer's {@code nextCursor}
   * @return the answer; an error for a bad argument or a run of another session
   */
  @SuppressWarnings({"PMD.ExcessiveParameterList", "PMD.UseObjectForClearerAPI"})
  public Mono<LogSearchResult> searchLogs(
      final String sessionId,
      final String executionId,
      @Nullable final Boolean includeChildren,
      @Nullable final String nodes,
      @Nullable final String stream,
      @Nullable final String level,
      @Nullable final String grep,
      @Nullable final Integer context,
      @Nullable final LogSearchMode mode,
      @Nullable final Integer maxMatches,
      @Nullable final String cursor) {
    return Mono.defer(() -> {
      final RunLogQuery query =
          RunLogQuery.builder()
              .executionId(executionId)
              .includeChildren(Boolean.TRUE.equals(includeChildren))
              .nodes(nodes(nodes))
              .stream(stream(stream))
              .level(level(level))
              .grep(grep)
              .context(context == null ? 0 : context)
              .mode(mode)
              .maxMatches(maxMatches == null ? 0 : maxMatches)
              .cursor(cursor)
              .build();
      return ExecutionOwnership.require(controlBus, sessionId, executionId)
          .then(Mono.defer(() -> searcher.search(query, sanitizer::clean)))
          .map(search -> new LogSearchResult(search, ExecutionLogs.UNTRUSTED_NOTICE));
    });
  }

  private static Set<String> nodes(@Nullable final String nodes) {
    return nodes == null
        ? Set.of()
        : Arrays.stream(nodes.split(","))
            .map(String::strip)
            .filter(node -> !node.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
  }

  private static @Nullable LogStream stream(@Nullable final String stream) {
    final String which = stream == null ? "all" : stream.strip().toLowerCase(Locale.ROOT);
    return switch (which) {
      case "all" -> null;
      case "stdout" -> LogStream.STDOUT;
      case "stderr" -> LogStream.STDERR;
      default -> throw new IllegalArgumentException("stream must be stdout, stderr or all");
    };
  }

  private static @Nullable LogLevel level(@Nullable final String level) {
    if (level == null || level.isBlank()) {
      return null;
    }
    try {
      return LogLevel.valueOf(level.strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("level must be ERROR, WARN, INFO or DEBUG", e);
    }
  }
}
