// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.provider;

import com.infenia.yukta.mcp.dto.ExecutionResultView;
import com.infenia.yukta.mcp.util.LogSanitizer;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.result.ExecutionResultService;
import com.infenia.yukta.service.result.ExecutionResults;
import com.infenia.yukta.service.result.ResultEvent;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Reads what reached the terminal nodes of an execution, with the same session-ownership check as
 * the web layer's results endpoint. The payloads are plugin and external output, so secrets are
 * redacted as in the logs ({@code yukta.mcp.log-redact-patterns}) and the result carries the
 * untrusted-data notice.
 */
@Component
public class ResultProvider {

  /** Records the results. */
  private final ExecutionResultService results;

  /** Gateway used to resolve execution ownership. */
  private final ControlBusGateway controlBus;

  /** Removes secrets from the payloads. */
  private final LogSanitizer sanitizer;

  /**
   * Create the provider.
   *
   * @param results records the results
   * @param controlBus resolves execution ownership
   * @param redactPatterns regexes whose matches are replaced in every payload; empty uses defaults
   */
  @Autowired
  @SuppressFBWarnings("EI_EXPOSE_REP2")
  public ResultProvider(
      final ExecutionResultService results,
      final ControlBusGateway controlBus,
      @Value("${yukta.mcp.log-redact-patterns:}") final List<String> redactPatterns) {
    this.results = results;
    this.controlBus = controlBus;
    this.sanitizer = new LogSanitizer(redactPatterns, 0);
  }

  /**
   * Create a provider with the default redaction.
   *
   * @param results records the results
   * @param controlBus resolves execution ownership
   */
  public ResultProvider(final ExecutionResultService results, final ControlBusGateway controlBus) {
    this(results, controlBus, List.of());
  }

  /**
   * Get the results recorded for an execution so far.
   *
   * @param sessionId the session that owns the execution
   * @param executionId the execution identifier
   * @param afterCursor the {@code nextCursor} of the previous call, or null (or negative) for
   *     everything kept
   * @return Mono containing the results; errors if the execution is unknown
   */
  public Mono<ExecutionResultView> getExecutionResult(
      final String sessionId, final String executionId, final Long afterCursor) {
    final long from = afterCursor == null ? 0 : Math.max(0, afterCursor);
    return ExecutionOwnership.require(controlBus, sessionId, executionId)
        .map(_ -> fence(results.read(executionId, from)));
  }

  private ExecutionResultView fence(final ExecutionResults read) {
    final List<ResultEvent> events =
        read.events().stream()
            .map(
                event ->
                    new ResultEvent(
                        event.kind(),
                        event.nodeId(),
                        event.sequence(),
                        sanitizer.redact(event.payload()),
                        event.status(),
                        event.truncated(),
                        event.timestamp()))
            .toList();
    return new ExecutionResultView(
        read.executionId(),
        read.complete(),
        read.status(),
        read.truncated(),
        read.nextCursor(),
        events,
        ExecutionResultView.UNTRUSTED_NOTICE);
  }
}
