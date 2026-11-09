// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.engine;

import com.infenia.yukta.logging.api.PluginLogEntry;
import com.infenia.yukta.logging.api.PluginLogStore;
import com.infenia.yukta.model.execution.TaskStatus;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.plugin.control.engine.EngineReads;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.input.InputRequestQueries;
import com.infenia.yukta.service.redact.SecretRedactor;
import com.infenia.yukta.service.session.SessionService;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * What an engine may read: the executions and open input requests of the units its supervisor owns,
 * newest first and bounded. It reads no node output and no message payload.
 */
@SuppressWarnings({"PMD.CouplingBetweenObjects", "PMD.TooManyMethods", "PMD.UseConcurrentHashMap"})
@Component
public class SupervisorQueries {

  /** Most rows one read returns. */
  public static final int MAX_ROWS = 200;

  private final SessionService sessions;
  private final ControlBusGateway controlBus;
  private final InputRequestQueries inputs;

  /** The longest a log line is when an engine reads it. */
  private static final int MAX_LOG_CHARS = 2000;

  private final SecretRedactor redactor = new SecretRedactor(null, null);

  @Nullable private PluginLogStore logStore;

  /**
   * Creates the queries.
   *
   * @param sessions lists the sessions
   * @param controlBus holds the run history
   * @param inputs reads the input requests
   */
  public SupervisorQueries(
      final SessionService sessions,
      final ControlBusGateway controlBus,
      final InputRequestQueries inputs) {
    this.sessions = sessions;
    this.controlBus = controlBus;
    this.inputs = inputs;
  }

  /**
   * The reads of one supervisor, limited to what it owns.
   *
   * @param scope what the supervisor owns
   * @return the reads for an engine
   */
  public EngineReads readsFor(final SupervisorScope scope) {
    return readsFor(scope, List::of);
  }

  /**
   * The reads of a supervisor, with its hold ledger.
   *
   * @param scope what the supervisor owns
   * @param held the executions it paused and still holds
   * @return the reads
   */
  public EngineReads readsFor(final SupervisorScope scope, final Supplier<List<String>> held) {
    return new EngineReads() {
      @Override
      public Flux<String> held() {
        return Flux.defer(() -> Flux.fromIterable(held.get()));
      }

      @Override
      public Flux<RunView> executions(final @Nullable String status, final int limit) {
        return SupervisorQueries.this
            .executions(scope, status == null ? null : statusNamed(status), limit)
            .map(
                run ->
                    new RunView(
                        run.sessionId(),
                        run.executionId(),
                        run.workflowId(),
                        run.status(),
                        run.startTime()));
      }

      @Override
      public Flux<InputRequest> openInputs(final int limit) {
        return SupervisorQueries.this.openInputs(scope, limit);
      }

      @Override
      public Mono<RunDetail> execution(final String executionId) {
        return SupervisorQueries.this.detail(scope, executionId);
      }

      @Override
      public Mono<WorkflowView> workflow(final String sessionId, final String workflowId) {
        return SupervisorQueries.this.shape(scope, sessionId, workflowId);
      }

      @Override
      public Flux<String> logs(final String executionId, final int tail) {
        return SupervisorQueries.this.logLines(scope, executionId, tail);
      }

      @Override
      public Mono<SchedulerView> scheduler() {
        return scope instanceof SupervisorScope.Server
            ? controlBus
                .limits()
                .map(
                    limits ->
                        new SchedulerView(
                            limits.maxConcurrent(),
                            limits.maxPerWorkflow(),
                            limits.running(),
                            limits.queued()))
            : Mono.empty();
      }
    };
  }

  /**
   * One execution, without its node outputs.
   *
   * @param sessionId its session
   * @param executionId its id
   * @param workflowId its workflow
   * @param status its status name
   * @param startTime when it started
   */
  public record Run(
      String sessionId,
      String executionId,
      String workflowId,
      String status,
      @Nullable Instant startTime) {}

  /** A run status by name, in any case; an unknown name is a bad request that lists the names. */
  private static TaskStatus statusNamed(final String status) {
    try {
      return TaskStatus.valueOf(status.strip().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "Unknown status '" + status + "'; use one of " + List.of(TaskStatus.values()), e);
    }
  }

  /**
   * Whether the owner of a scope may see a workflow: it owns the run level or a node of it.
   *
   * @param scope what the supervisor owns
   * @param sessionId the session
   * @param workflowId the workflow
   * @return true if it may
   */
  public static boolean sees(
      final SupervisorScope scope, final String sessionId, final String workflowId) {
    if (scope instanceof SupervisorScope.Global) {
      return false; // Global owns nothing, so it reads nothing
    }
    return scope.matches(sessionId, workflowId)
        || (scope instanceof SupervisorScope.Section section
            && workflowId.equals(section.workflowId())
            && (section.sessionId() == null || sessionId.equals(section.sessionId())));
  }

  /**
   * The status of one execution.
   *
   * @param sessionId its session
   * @param executionId its id
   * @return the status name, or empty if the run is not in the history
   */
  public Mono<String> statusOf(final String sessionId, final String executionId) {
    return controlBus
        .getHistory(sessionId)
        .flatMapIterable(history -> history)
        .filter(run -> executionId.equals(run.executionId()))
        .next()
        .map(run -> run.status().name());
  }

  /**
   * The executions of the owned workflows, newest first.
   *
   * @param scope what the supervisor owns
   * @param status only executions in this status, or {@code null} for all
   * @param limit most rows; capped at {@value #MAX_ROWS}
   * @return the executions
   */
  public Flux<Run> executions(
      final SupervisorScope scope, final @Nullable TaskStatus status, final int limit) {
    // a scope that names its session reads only that one; the others look through every session
    final Flux<String> sessionIds =
        scope.sessionId() != null ? Flux.just(scope.sessionId()) : sessions.getSessionIds(false);
    return sessionIds
        .concatMap(
            id ->
                controlBus
                    .getHistory(id)
                    .flatMapIterable(history -> history)
                    .filter(run -> sees(scope, id, run.workflowId()))
                    .filter(run -> status == null || run.status() == status)
                    .map(
                        run ->
                            new Run(
                                id,
                                run.executionId(),
                                run.workflowId(),
                                run.status().name(),
                                run.startTime())))
        .sort(Comparator.comparing(Run::startTime, Comparator.nullsLast(Comparator.reverseOrder())))
        .take(Math.clamp(limit, 1, MAX_ROWS));
  }

  /**
   * The open and escalated input requests of the owned workflows, newest first.
   *
   * @param scope what the supervisor owns
   * @param limit most rows; capped at {@value #MAX_ROWS}
   * @return the requests, with the content the input API already shows
   */
  public Flux<InputRequest> openInputs(final SupervisorScope scope, final int limit) {
    return inputs
        .list(null, null, null, InputRequestQueries.WAITING)
        .filter(
            request ->
                !(scope instanceof SupervisorScope.Global)
                    && scope.matches(request.sessionId(), request.workflowId(), request.nodeId()))
        .take(Math.clamp(limit, 1, MAX_ROWS));
  }

  /**
   * Gives the queries the log store, when the server has one.
   *
   * @param store the store
   */
  @Autowired(required = false)
  public void setLogStore(final PluginLogStore store) {
    this.logStore = store;
  }

  /** One owned execution and the status of the nodes the scope owns. */
  private Mono<EngineReads.RunDetail> detail(
      final SupervisorScope scope, final String executionId) {
    return owned(scope, executionId)
        .flatMap(
            run ->
                Mono.fromSupplier(() -> controlBus.getProgress(run.sessionId(), executionId))
                    .map(
                        progress ->
                            new EngineReads.RunDetail(
                                new EngineReads.RunView(
                                    run.sessionId(),
                                    run.executionId(),
                                    run.workflowId(),
                                    run.status(),
                                    run.startTime()),
                                nodeStatuses(scope, progress))));
  }

  private static Map<String, String> nodeStatuses(
      final SupervisorScope scope, final WorkflowProgress progress) {
    final Map<String, String> out = new LinkedHashMap<>();
    progress.tasks().stream()
        .filter(task -> scope.matches(progress.sessionId(), progress.workflowId(), task.nodeId()))
        .forEach(task -> out.put(task.nodeId(), task.status().name()));
    return out;
  }

  /** The run, when the scope sees it among its newest {@value #MAX_ROWS}. */
  private Mono<Run> owned(final SupervisorScope scope, final String executionId) {
    return executions(scope, null, MAX_ROWS)
        .filter(run -> executionId.equals(run.executionId()))
        .next();
  }

  /** The shape of a workflow, only the nodes the scope owns and the edges between them. */
  private Mono<EngineReads.WorkflowView> shape(
      final SupervisorScope scope, final String sessionId, final String workflowId) {
    if (!sees(scope, sessionId, workflowId)) {
      return Mono.empty();
    }
    return sessions
        .getSessionWorkflow(sessionId, workflowId)
        .map(
            definition -> {
              final List<EngineReads.NodeView> nodes =
                  definition.nodes().stream()
                      .filter(node -> scope.matches(sessionId, workflowId, node.nodeId()))
                      .map(
                          node ->
                              new EngineReads.NodeView(node.nodeId(), node.type(), node.group()))
                      .toList();
              final java.util.Set<String> kept =
                  nodes.stream()
                      .map(EngineReads.NodeView::nodeId)
                      .collect(java.util.stream.Collectors.toSet());
              final List<EngineReads.EdgeView> edges =
                  definition.edges().stream()
                      .filter(edge -> kept.contains(edge.source()) && kept.contains(edge.target()))
                      .map(edge -> new EngineReads.EdgeView(edge.source(), edge.target()))
                      .toList();
              return new EngineReads.WorkflowView(workflowId, nodes, edges);
            })
        .onErrorResume(_ -> Mono.empty());
  }

  /** The newest lines of an owned execution, each cleaned of secrets and cut short. */
  private Flux<String> logLines(
      final SupervisorScope scope, final String executionId, final int tail) {
    final PluginLogStore store = logStore;
    if (store == null) {
      return Flux.empty();
    }
    final int count = Math.clamp(tail, 1, MAX_ROWS);
    return owned(scope, executionId)
        .flatMapMany(
            run ->
                store
                    .readExecution(executionId)
                    .filter(
                        entry -> scope.matches(run.sessionId(), run.workflowId(), entry.pluginId()))
                    .takeLast(count)
                    .map(this::line));
  }

  private String line(final PluginLogEntry entry) {
    final String text = redactor.redactText(entry.message());
    final String cut =
        text.length() > MAX_LOG_CHARS ? text.substring(0, MAX_LOG_CHARS) + "..." : text;
    return entry.logLevel() + " " + entry.pluginId() + ": " + cut;
  }
}
