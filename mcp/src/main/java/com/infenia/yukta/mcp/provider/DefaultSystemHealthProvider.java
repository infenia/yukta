// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.provider;

import com.infenia.yukta.mcp.dto.ControlBusStatus;
import com.infenia.yukta.mcp.dto.ExecutionRecord;
import com.infenia.yukta.mcp.dto.PluginRegistryEntry;
import com.infenia.yukta.mcp.dto.SessionExecutionInfo;
import com.infenia.yukta.mcp.dto.SystemHealthMetrics;
import com.infenia.yukta.mcp.util.UptimeFormatter;
import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.plugin.PluginRegistry;
import com.infenia.yukta.service.session.SessionService;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Default implementation of SystemHealthProvider. Aggregates live session, execution, plugin, and
 * JVM data into a control bus status snapshot.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultSystemHealthProvider implements SystemHealthProvider {

  /** Statuses in which an execution no longer runs; mirrors the web log endpoint's set. */
  private static final Set<String> TERMINAL_STATUSES =
      Set.of("COMPLETED", "FAILED", "CANCELLED", "WORKFLOW_STOPPED");

  /** Maximum number of recent executions included in the status. */
  private static final int RECENT_EXECUTION_LIMIT = 20;

  /** Filter values accepted by getControlBusStatus. */
  private static final Set<String> VALID_FILTERS =
      Set.of("sessions", "executions", "plugins", "health");

  /** Registry for accessing all available plugins. */
  private final PluginRegistry registry;

  /** Service for enumerating sessions and their configurations. */
  private final SessionService sessionService;

  /** Gateway providing per-session execution history. */
  private final ControlBusGateway controlBus;

  @Override
  @SuppressWarnings("PMD.OnlyOneReturn")
  public Mono<ControlBusStatus> getControlBusStatus(final String filterType) {
    final boolean all = filterType == null || filterType.isBlank();
    if (!all && !VALID_FILTERS.contains(filterType.toLowerCase(Locale.ROOT))) {
      return Mono.error(
          new IllegalArgumentException(
              "Unsupported filter: "
                  + filterType
                  + ". Valid filters: sessions, executions, plugins, health; omit the filter "
                  + "for the full status."));
    }
    return buildStatus(all, filterType);
  }

  private Mono<ControlBusStatus> buildStatus(final boolean all, final String filterType) {
    final Mono<List<SessionExecutionInfo>> sessions =
        all || "sessions".equalsIgnoreCase(filterType)
            ? buildSessionExecutionInfo()
            : Mono.just(List.of());
    final Mono<List<ExecutionRecord>> executions =
        all || "executions".equalsIgnoreCase(filterType)
            ? buildRecentExecutions()
            : Mono.just(List.of());
    final Mono<List<PluginRegistryEntry>> plugins =
        all || "plugins".equalsIgnoreCase(filterType)
            ? Mono.fromCallable(this::buildPluginRegistryInfo)
                .subscribeOn(Schedulers.boundedElastic())
            : Mono.just(List.of());
    final SystemHealthMetrics health =
        all || "health".equalsIgnoreCase(filterType)
            ? buildSystemHealthMetrics()
            : new SystemHealthMetrics(0, 0, "0", "0", "0s");

    return Mono.zip(sessions, plugins, executions)
        .map(parts -> new ControlBusStatus(parts.getT1(), parts.getT2(), health, parts.getT3()));
  }

  private Mono<List<SessionExecutionInfo>> buildSessionExecutionInfo() {
    return sessionService
        .getSessionIds()
        .flatMap(
            sessionId ->
                sessionService
                    .getSessionConfig(sessionId)
                    .map(this::workflowCount)
                    .flatMap(
                        workflowCount ->
                            historyOf(sessionId)
                                .map(
                                    history ->
                                        new SessionExecutionInfo(
                                            sessionId, countActive(history), workflowCount)))
                    .onErrorResume(
                        e -> {
                          log.atWarn()
                              .setCause(e)
                              .log("Skipping session {} in control bus status", sessionId);
                          return Mono.empty();
                        }))
        .collectList();
  }

  private Mono<List<ExecutionRecord>> buildRecentExecutions() {
    return sessionService
        .getSessionIds()
        .flatMap(
            sessionId ->
                historyOf(sessionId)
                    .flatMapMany(Flux::fromIterable)
                    .map(summary -> new SessionExecution(sessionId, summary))
                    .onErrorResume(
                        e -> {
                          log.atWarn()
                              .setCause(e)
                              .log("Skipping executions of session {} in status", sessionId);
                          return Flux.empty();
                        }))
        .sort(
            Comparator.comparing(
                (SessionExecution execution) -> execution.summary().startTime(),
                Comparator.nullsLast(Comparator.reverseOrder())))
        .take(RECENT_EXECUTION_LIMIT)
        .map(execution -> toExecutionRecord(execution.sessionId(), execution.summary()))
        .collectList();
  }

  /** Pairs an execution summary with its owning session for cross-session ordering. */
  private record SessionExecution(String sessionId, WorkflowExecutionSummary summary) {}

  private Mono<List<WorkflowExecutionSummary>> historyOf(final String sessionId) {
    return Mono.fromCallable(() -> controlBus.getHistory(sessionId))
        .subscribeOn(Schedulers.boundedElastic());
  }

  @SuppressWarnings("unchecked")
  private int workflowCount(final Map<String, Object> config) {
    return ((Map<String, Object>) config.getOrDefault("workflows", Map.of())).size();
  }

  private int countActive(final List<WorkflowExecutionSummary> history) {
    return (int) history.stream().filter(s -> !TERMINAL_STATUSES.contains(s.status())).count();
  }

  private ExecutionRecord toExecutionRecord(
      final String sessionId, final WorkflowExecutionSummary summary) {
    final String duration =
        summary.endTime() == null
            ? "running"
            : Duration.between(summary.startTime(), summary.endTime()).toMillis() + "ms";
    return new ExecutionRecord(sessionId, summary.executionId(), summary.status(), duration);
  }

  private List<PluginRegistryEntry> buildPluginRegistryInfo() {
    return registry.listPlugins().stream()
        .map(p -> new PluginRegistryEntry(p.getType(), p.getCategory().toString(), "ACTIVE"))
        .toList();
  }

  private SystemHealthMetrics buildSystemHealthMetrics() {
    final Runtime runtime = Runtime.getRuntime();
    final long maxMemory = runtime.maxMemory() / (1024 * 1024);
    final long usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
    final double threadPoolUtilization =
        Thread.activeCount() / (double) runtime.availableProcessors() * 100;

    return new SystemHealthMetrics(
        threadPoolUtilization,
        0,
        usedMemory + " MB",
        maxMemory + " MB",
        UptimeFormatter.getSystemUptime());
  }
}
