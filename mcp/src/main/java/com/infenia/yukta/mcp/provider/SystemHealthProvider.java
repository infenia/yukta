// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.provider;

import com.infenia.yukta.mcp.dto.ControlBusStatus;
import com.infenia.yukta.mcp.dto.ExecutionRecord;
import com.infenia.yukta.mcp.dto.PluginRegistryEntry;
import com.infenia.yukta.mcp.dto.SessionExecutionInfo;
import com.infenia.yukta.mcp.dto.SystemHealthMetrics;
import com.infenia.yukta.mcp.util.UptimeFormatter;
import com.infenia.yukta.model.execution.TaskStatus;
import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.plugin.PluginRegistry;
import com.infenia.yukta.service.session.SessionService;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Aggregates live session, execution, plugin, and JVM data into a control bus status snapshot. */
@Slf4j
@Component
@RequiredArgsConstructor
public class SystemHealthProvider {

  /** Legacy status names that also mean an execution no longer runs. */
  private static final Set<String> LEGACY_TERMINAL_STATUSES =
      Set.of("COMPLETED", "FAILED", "WORKFLOW_STOPPED");

  /** Sessions read in parallel when aggregating the status. */
  private static final int SESSION_CONCURRENCY = 8;

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

  /**
   * Get control bus status with comprehensive monitoring information.
   *
   * @param filterType optional filter ("sessions", "plugins", "health", "executions")
   * @return Mono containing control bus status with monitoring data
   */
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

  @SuppressWarnings("PMD.NPathComplexity")
  private Mono<ControlBusStatus> buildStatus(final boolean all, final String filterType) {
    final boolean wantSessions = all || "sessions".equalsIgnoreCase(filterType);
    final boolean wantExecutions = all || "executions".equalsIgnoreCase(filterType);
    // One pass over the sessions feeds both the session list and the recent executions.
    final Mono<List<SessionSnapshot>> snapshots =
        wantSessions || wantExecutions
            ? snapshotSessions(wantSessions).cache()
            : Mono.just(List.of());
    final Mono<List<SessionExecutionInfo>> sessions =
        wantSessions ? snapshots.map(this::toSessionInfos) : Mono.just(List.of());
    final Mono<List<ExecutionRecord>> executions =
        wantExecutions ? snapshots.map(this::toRecentExecutions) : Mono.just(List.of());
    final Mono<List<PluginRegistryEntry>> plugins =
        all || "plugins".equalsIgnoreCase(filterType)
            ? Mono.fromCallable(this::buildPluginRegistryInfo)
            : Mono.just(List.of());
    final SystemHealthMetrics health =
        all || "health".equalsIgnoreCase(filterType)
            ? buildSystemHealthMetrics()
            : new SystemHealthMetrics("0", "0", "0s");

    return Mono.zip(sessions, plugins, executions)
        .map(parts -> new ControlBusStatus(parts.getT1(), parts.getT2(), health, parts.getT3()));
  }

  /** Reads each session's config and history once, a few sessions at a time. */
  private Mono<List<SessionSnapshot>> snapshotSessions(final boolean withConfig) {
    return sessionService
        .getSessionIds()
        .flatMap(
            sessionId ->
                workflowCount(sessionId, withConfig)
                    .flatMap(
                        count ->
                            controlBus
                                .getHistory(sessionId)
                                .map(history -> new SessionSnapshot(sessionId, count, history)))
                    .onErrorResume(
                        e -> {
                          log.atWarn()
                              .setCause(e)
                              .log("Skipping session {} in control bus status", sessionId);
                          return Mono.empty();
                        }),
            SESSION_CONCURRENCY)
        .collectList();
  }

  private Mono<Integer> workflowCount(final String sessionId, final boolean withConfig) {
    return withConfig
        ? sessionService.getSessionConfig(sessionId).map(config -> config.workflows().size())
        : Mono.just(0);
  }

  private List<SessionExecutionInfo> toSessionInfos(final List<SessionSnapshot> snapshots) {
    return snapshots.stream()
        .map(
            s ->
                new SessionExecutionInfo(
                    s.sessionId(), countActive(s.history()), s.workflowCount()))
        .toList();
  }

  private List<ExecutionRecord> toRecentExecutions(final List<SessionSnapshot> snapshots) {
    return snapshots.stream()
        .flatMap(s -> s.history().stream().map(h -> new SessionExecution(s.sessionId(), h)))
        .sorted(
            Comparator.comparing(
                (SessionExecution execution) -> execution.summary().startTime(),
                Comparator.nullsLast(Comparator.reverseOrder())))
        .limit(RECENT_EXECUTION_LIMIT)
        .map(execution -> toExecutionRecord(execution.sessionId(), execution.summary()))
        .toList();
  }

  /** A session's workflow count and execution history, read once per status call. */
  private record SessionSnapshot(
      String sessionId, int workflowCount, List<WorkflowExecutionSummary> history) {}

  /** Pairs an execution summary with its owning session for cross-session ordering. */
  private record SessionExecution(String sessionId, WorkflowExecutionSummary summary) {}

  private int countActive(final List<WorkflowExecutionSummary> history) {
    return (int) history.stream().filter(s -> !isTerminal(s.status())).count();
  }

  private static boolean isTerminal(final String status) {
    return TaskStatus.isTerminalName(status) || LEGACY_TERMINAL_STATUSES.contains(status);
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
    return new SystemHealthMetrics(
        usedMemory + " MB", maxMemory + " MB", UptimeFormatter.getSystemUptime());
  }
}
