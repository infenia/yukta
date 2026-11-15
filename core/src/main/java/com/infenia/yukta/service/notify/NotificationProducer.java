// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.notify;

import com.infenia.yukta.config.YuktaProperties;
import com.infenia.yukta.model.execution.TaskStatus;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.input.Responder;
import com.infenia.yukta.plugin.notify.NotificationKind;
import com.infenia.yukta.plugin.notify.NotificationSeverity;
import com.infenia.yukta.service.input.InputRequestStore;
import com.infenia.yukta.service.leader.LeaderElection;
import com.infenia.yukta.service.leader.LeaderTimer;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import com.infenia.yukta.service.review.AttachedSupervisors;
import com.infenia.yukta.service.review.ExecutionEventFeed;
import com.infenia.yukta.service.sessionstore.InternalSessions;
import io.micrometer.core.instrument.Metrics;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Turns engine events into notifications. It reads the reliable tap of the event feed in order and,
 * on the leader only:
 *
 * <ul>
 *   <li>raises one {@code ACTION} per input request that reaches a person, follows it through
 *       escalation, and resolves it when the request is answered, expires or is cancelled;
 *   <li>raises {@code run.failed}, {@code run.finished} and {@code run.held} alerts for root runs
 *       outside the internal session, as {@link NotificationRules} says.
 * </ul>
 *
 * <p>A tier that also targets supervisors waits {@code action-grace}: a person is told only if the
 * request is still open after it. A repair every five minutes raises the action of an open request
 * that has none (a missed event, or a request opened before this feature).
 */
@Slf4j
@Component
@SuppressWarnings({
  "PMD.CouplingBetweenObjects",
  "PMD.TooManyMethods",
  "PMD.GodClass",
  "PMD.ExcessiveImports",
  "PMD.AvoidUsingVolatile",
  "PMD.UseConcurrentHashMap",
  "PMD.NullAssignment",
  "PMD.AvoidInstantiatingObjectsInLoops"
})
public class NotificationProducer implements SmartLifecycle {

  /** Time between two repairs. */
  private static final Duration RECONCILE_INTERVAL = Duration.ofMinutes(5);

  private static final String PERSON_ROLE = "role:";
  private static final String PERSON_KEY = "key:";
  private static final String SUPERVISOR = "supervisor:";
  private static final String INPUT_KEY = "input:";

  /** The commands of a supervisor that hold a run. */
  private static final Set<String> HOLDING = Set.of("PauseWorkflowCommand", "StopWorkflowCommand");

  /** The statuses of a root run that failed. */
  private static final Set<TaskStatus> FAILED =
      Set.of(TaskStatus.FAILURE, TaskStatus.TIMED_OUT, TaskStatus.ABORTED);

  private final ExecutionEventFeed feed;
  private final NotificationService service;
  private final InputRequestStore inputs;
  private final TaskTrackerService tracker;
  private final InternalSessions internalSessions;
  private final AttachedSupervisors supervisors;
  private final LeaderElection leader;
  private final NotificationRules rules;
  private final boolean enabled;
  private final Duration grace;
  private final Scheduler scheduler;

  /** Runs the repair on the leader. */
  private final LeaderTimer repair;

  /** Grace timers in flight, so a stop cancels them. */
  private volatile Disposable.Composite pending = Disposables.composite();

  /** The reader of the tap, or {@code null} when stopped. */
  private volatile @Nullable Disposable reader;

  /** Active notifications by kind, for the gauge. */
  private final Map<NotificationKind, AtomicLong> active = new EnumMap<>(NotificationKind.class);

  /**
   * Creates the producer.
   *
   * @param feed the event feed
   * @param service writes the notifications
   * @param inputs the input requests, to see whether one is still open
   * @param tracker tells whether a run is a child run
   * @param internalSessions tells whether a session is internal
   * @param supervisors the attached supervisors
   * @param leader tells whether this server is the leader
   * @param rules decides what an engine event raises
   * @param properties the {@code yukta.notifications} settings
   */
  @Autowired
  public NotificationProducer(
      final ExecutionEventFeed feed,
      final NotificationService service,
      final InputRequestStore inputs,
      final TaskTrackerService tracker,
      final InternalSessions internalSessions,
      final AttachedSupervisors supervisors,
      final LeaderElection leader,
      final NotificationRules rules,
      final YuktaProperties properties) {
    this(
        feed,
        service,
        inputs,
        tracker,
        internalSessions,
        supervisors,
        leader,
        rules,
        properties.notifications().enabled(),
        properties.notifications().actionGrace(),
        RECONCILE_INTERVAL,
        Schedulers.parallel());
  }

  /**
   * Creates the producer with explicit timing.
   *
   * @param feed the event feed
   * @param service writes the notifications
   * @param inputs the input requests
   * @param tracker tells whether a run is a child run
   * @param internalSessions tells whether a session is internal
   * @param supervisors the attached supervisors
   * @param leader tells whether this server is the leader
   * @param rules decides what an engine event raises
   * @param enabled whether notifications are raised at all
   * @param grace how long a tier that supervisors may answer waits before a person is told
   * @param interval time between two repairs
   * @param scheduler runs the timers; a virtual-time scheduler in tests
   */
  @SuppressWarnings("PMD.ExcessiveParameterList")
  public NotificationProducer(
      final ExecutionEventFeed feed,
      final NotificationService service,
      final InputRequestStore inputs,
      final TaskTrackerService tracker,
      final InternalSessions internalSessions,
      final AttachedSupervisors supervisors,
      final LeaderElection leader,
      final NotificationRules rules,
      final boolean enabled,
      final Duration grace,
      final Duration interval,
      final Scheduler scheduler) {
    this.feed = feed;
    this.service = service;
    this.inputs = inputs;
    this.tracker = tracker;
    this.internalSessions = internalSessions;
    this.supervisors = supervisors;
    this.leader = leader;
    this.rules = rules;
    this.enabled = enabled;
    this.grace = grace;
    this.scheduler = scheduler;
    this.repair = new LeaderTimer(leader, interval, scheduler, this::reconcile);
    for (final NotificationKind kind : NotificationKind.values()) {
      final AtomicLong count = new AtomicLong();
      active.put(kind, count);
      Metrics.gauge(
          "yukta.notifications.active",
          List.of(io.micrometer.core.instrument.Tag.of("kind", kind.name())),
          count);
    }
  }

  /**
   * Handles one event.
   *
   * @param event the event from the tap
   * @return completes when done; a failure is logged, never an error
   */
  /* default */ Mono<Void> handle(final ExecutionEvent event) {
    if (!leader.isLeader()) {
      return Mono.empty();
    }
    return Mono.defer(() -> route(event))
        .onErrorResume(error -> {
          log.atWarn().setCause(error).log("A notification could not be raised for an event");
          return Mono.empty();
        })
        .then(Mono.defer(this::refreshActive));
  }

  private Mono<Void> route(final ExecutionEvent event) {
    return switch (event) {
      case ExecutionEvent.InputRequested e -> requested(e.request(), null);
      case ExecutionEvent.InputEscalated e -> escalated(e);
      case ExecutionEvent.InputAnswered e ->
          resolve(e.requestId(), "answered by " + who(e.responder()) + ": " + e.decision());
      case ExecutionEvent.InputExpired e -> resolve(e.requestId(), "expired");
      case ExecutionEvent.InputCancelled e -> resolve(e.requestId(), "cancelled: " + e.reason());
      case ExecutionEvent.WorkflowStatusChanged e -> status(e);
      case ExecutionEvent.ControlOutcome e -> held(e);
      default -> Mono.empty();
    };
  }

  // ---- input requests ----

  private Mono<Void> requested(final InputRequest request, final @Nullable String reason) {
    final List<String> people = people(request.targets());
    if (people.isEmpty()) {
      return Mono.empty();
    }
    if (withSupervisors(request.targets()) && !grace.isZero()) {
      pending.add(
          Mono.delay(grace, scheduler)
              .then(Mono.defer(() -> afterGrace(request.id(), reason)))
              .onErrorResume(error -> {
                log.atWarn().setCause(error).log("A notification could not be raised");
                return Mono.empty();
              })
              .then(Mono.defer(this::refreshActive))
              .subscribe());
      return Mono.empty();
    }
    return raiseInput(request, people, reason);
  }

  /** After the grace: raise only if the request is still waiting, from its current tier. */
  private Mono<Void> afterGrace(final String requestId, final @Nullable String reason) {
    if (!leader.isLeader()) {
      return Mono.empty();
    }
    return inputs
        .findById(requestId)
        .filter(request -> !request.state().isTerminal())
        .flatMap(request -> {
          final List<String> people = people(request.targets());
          return people.isEmpty() ? Mono.empty() : raiseInput(request, people, reason);
        })
        .then(Mono.defer(() -> endedMeanwhile(requestId)));
  }

  /** An answer can overtake the raise: if the request ended meanwhile, close the item too. */
  private Mono<Void> endedMeanwhile(final String requestId) {
    return inputs
        .findById(requestId)
        .filter(request -> request.state().isTerminal())
        .flatMap(request ->
            resolve(requestId, "ended: " + request.state().name().toLowerCase(Locale.ROOT)))
        .then();
  }

  private Mono<Void> escalated(final ExecutionEvent.InputEscalated event) {
    final InputRequest request = event.request();
    final List<String> people = people(request.targets());
    return service
        .byKey(INPUT_KEY + request.id())
        .flatMap(existing -> raiseInput(request, people, event.reason()).thenReturn(existing))
        .switchIfEmpty(
            Mono.defer(() ->
                requested(request, event.reason()).thenReturn(Boolean.TRUE).then(Mono.empty())))
        .then();
  }

  private Mono<Void> raiseInput(
      final InputRequest request, final List<String> audience, final @Nullable String reason) {
    final String prompt = request.spec().prompt();
    final int newline = prompt.indexOf('\n');
    final String title = newline < 0 ? prompt : prompt.substring(0, newline);
    final String rest = newline < 0 ? "" : prompt.substring(newline + 1).strip();
    final String body =
        (reason == null ? "" : "Handed up: " + reason + (rest.isEmpty() ? "" : "\n\n")) + rest;
    return service
        .raise(
            NotificationDraft.builder()
                .key(INPUT_KEY + request.id())
                .kind(NotificationKind.ACTION)
                .severity(
                    switch (request.spec().risk()) {
                      case LOW -> NotificationSeverity.INFO;
                      case MEDIUM -> NotificationSeverity.WARN;
                      case HIGH -> NotificationSeverity.ERROR;
                    })
                .category("input")
                .title(title)
                .body(body.isEmpty() ? null : body)
                .sessionId(request.sessionId())
                .workflowId(request.workflowId())
                .executionId(request.executionId())
                .nodeId(request.nodeId())
                .inputRequestId(request.id())
                .audience(audience)
                .cooldown(Duration.ZERO)
                .build())
        .then();
  }

  private Mono<Void> resolve(final String requestId, final String resolution) {
    return service.resolve(INPUT_KEY + requestId, resolution).then();
  }

  /** The person selectors of a tier. */
  private static List<String> people(final List<String> targets) {
    return targets.stream()
        .filter(target -> target.startsWith(PERSON_ROLE) || target.startsWith(PERSON_KEY))
        .toList();
  }

  private static boolean withSupervisors(final List<String> targets) {
    return targets.stream().anyMatch(target -> target.startsWith(SUPERVISOR));
  }

  private static String who(final Responder responder) {
    return switch (responder.type()) {
      case HUMAN -> PERSON_KEY + responder.id();
      case SUPERVISOR -> SUPERVISOR + responder.id();
      case EXTERNAL -> "external:" + responder.id();
      case SYSTEM -> "system";
    };
  }

  // ---- runs ----

  private Mono<Void> status(final ExecutionEvent.WorkflowStatusChanged event) {
    final TaskStatus status = parse(event.status());
    final String category;
    if (status == TaskStatus.SUCCESS) {
      category = NotificationRules.RUN_FINISHED;
    } else if (status != null && FAILED.contains(status)) {
      category = NotificationRules.RUN_FAILED;
    } else {
      return Mono.empty();
    }
    final boolean failed = NotificationRules.RUN_FAILED.equals(category);
    return runAlert(
        category,
        event.executionId(),
        event.sessionId(),
        event.workflowId(),
        failed ? NotificationSeverity.ERROR : NotificationSeverity.INFO,
        (failed ? "Run failed: " : "Run finished: ") + event.workflowId(),
        "Execution " + event.executionId() + " ended " + event.status() + ".");
  }

  private Mono<Void> held(final ExecutionEvent.ControlOutcome event) {
    final String actor = event.actor();
    if (!event.applied()
        || !HOLDING.contains(event.command())
        || actor == null
        || supervisors.find(actor).isEmpty()) {
      return Mono.empty();
    }
    final String what = "PauseWorkflowCommand".equals(event.command()) ? "paused" : "stopped";
    return runAlert(
        NotificationRules.RUN_HELD,
        event.executionId(),
        event.sessionId(),
        event.workflowId(),
        NotificationSeverity.WARN,
        "Run " + what + " by supervisor \"" + actor + "\"",
        event.reason());
  }

  /** Raises the alert of a root run outside the internal session, if a rule says so. */
  private Mono<Void> runAlert(
      final String category,
      final String executionId,
      final @Nullable String sessionId,
      final @Nullable String workflowId,
      final NotificationSeverity defaultSeverity,
      final String title,
      final @Nullable String body) {
    // ponytail: root-ness is read when the event is dequeued; the tracker keeps a finished run for
    // the results retention, which is far longer than the tap's delay.
    if (tracker.lineage(executionId).depth() != 0) {
      return Mono.empty();
    }
    return internalSessions
        .checkInternal(sessionId)
        .filter(internal -> !internal)
        .flatMap(_ ->
            Mono.justOrEmpty(rules.forRun(category, sessionId, workflowId))
                .flatMap(applied ->
                    service.raise(
                        NotificationDraft.builder()
                            .key(category + ":" + executionId)
                            .kind(NotificationKind.INFO)
                            .severity(
                                applied.severity() == null ? defaultSeverity : applied.severity())
                            .category(category)
                            .title(title)
                            .body(body)
                            .sessionId(sessionId)
                            .workflowId(workflowId)
                            .executionId(executionId)
                            .audience(applied.audience())
                            .cooldown(applied.cooldown())
                            .build())))
        .then();
  }

  private static @Nullable TaskStatus parse(final String status) {
    try {
      return TaskStatus.valueOf(status);
    } catch (IllegalArgumentException unknown) {
      return null;
    }
  }

  // ---- repair and lifecycle ----

  /**
   * Raises the action of every open request whose tier reaches a person and that has none.
   *
   * @return completes when done
   */
  /* default */ Mono<Void> reconcile() {
    return inputs
        .listOpen(null, null)
        .filter(request -> !people(request.targets()).isEmpty())
        .concatMap(request ->
            service
                .byKey(INPUT_KEY + request.id())
                .switchIfEmpty(
                    Mono.defer(() ->
                        raiseInput(request, people(request.targets()), null).then(Mono.empty()))))
        .then(Mono.defer(this::refreshActive));
  }

  private Mono<Void> refreshActive() {
    return service
        .activeCounts()
        .doOnNext(
            counts -> active.forEach((kind, gauge) -> gauge.set(counts.getOrDefault(kind, 0L))))
        .then();
  }

  @Override
  public void start() {
    if (!enabled || reader != null) {
      return;
    }
    pending = Disposables.composite();
    reader = feed.reliable().concatMap(this::handle).subscribe();
    repair.start();
    Mono.defer(() -> leader.isLeader() ? reconcile() : Mono.<Void>empty())
        .onErrorResume(error -> {
          log.atWarn().setCause(error).log("The first notification repair failed");
          return Mono.empty();
        })
        .subscribe();
  }

  @Override
  public void stop() {
    final Disposable running = reader;
    reader = null;
    if (running != null) {
      running.dispose();
    }
    pending.dispose();
    repair.stop();
  }

  @Override
  public boolean isRunning() {
    return reader != null;
  }
}
