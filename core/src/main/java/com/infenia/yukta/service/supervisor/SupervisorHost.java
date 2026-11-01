// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import com.infenia.yukta.config.YuktaProperties;
import com.infenia.yukta.plugin.control.ControlActor;
import com.infenia.yukta.plugin.control.ControlPlanePlugin;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.security.AuditRecord;
import com.infenia.yukta.security.AuditSink;
import com.infenia.yukta.service.control.ControlAudit;
import com.infenia.yukta.service.control.directive.DirectiveDispatcher;
import com.infenia.yukta.service.log.ExecutionLogStore;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.BufferOverflowStrategy;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.Context;
import reactor.util.retry.Retry;

/**
 * Runs the {@link ExecutionSupervisor}s: feeds each the execution events and applies the commands
 * it emits, tagged with its id.
 *
 * <p>A supervisor cannot harm the executions: its failures are logged and retried with a backoff,
 * its commands are rate limited, and with {@code yukta.supervisor.dry-run=true} they are only
 * recorded.
 */
@Slf4j
@Component
@SuppressWarnings({"PMD.TooManyMethods", "PMD.ExcessiveImports", "PMD.CouplingBetweenObjects"})
public class SupervisorHost {

  /** Events handed to the supervisor's thread at a time. */
  private static final int SUPERVISOR_PREFETCH = 256;

  /** Most decisions kept for the decision feed. */
  private static final int MAX_DECISIONS = 500;

  /** Counter of supervisor commands by outcome. */
  private static final String COMMANDS_METRIC = "yukta.supervisor.commands";

  /** Outcome recorded for a command a dry-run supervisor would have refused. */
  private static final String DRY_RUN = "dry-run";

  /** Key of the supervisor in log entries. */
  private static final String SUPERVISOR_KEY = "supervisor";

  /** The supervisors to run. */
  private final ObjectProvider<ExecutionSupervisor> supervisors;

  /** The execution events. */
  private final ExecutionEventFeed feed;

  /** Applies the commands. */
  private final DirectiveDispatcher dispatcher;

  /** Records commands that were not applied. */
  private final ExecutionLogStore executionLog;

  /** Receives every audit record besides the log, if a module provides one. */
  @Nullable private AuditSink auditSink;

  /** Registry for the command counter. */
  private final MeterRegistry meters;

  /** Whether commands are only recorded. */
  private final boolean dryRun;

  /** Most commands one supervisor may issue per second. */
  private final int maxCommandsPerSecond;

  /** Events a supervisor may have waiting before events are dropped for it. */
  private final int bufferSize;

  /** Running supervisor subscriptions. */
  private final List<Disposable> subscriptions = new CopyOnWriteArrayList<>();

  /** The newest decisions, newest last; a full queue drops its oldest to take a new one. */
  private final BlockingDeque<SupervisorDecision> recentDecisions =
      new LinkedBlockingDeque<>(MAX_DECISIONS);

  /** Serializes attaching, detaching and stopping, so a binding never has two subscriptions. */
  private final ReentrantLock lock = new ReentrantLock();

  /** Subscriptions of the supervisors attached at run time, by binding id. */
  private final Map<String, Disposable> attachedSubscriptions = new ConcurrentHashMap<>();

  /** The supervisors attached at run time. */
  private AttachedSupervisors attachedSupervisors = new AttachedSupervisors();

  /**
   * Creates the host.
   *
   * @param supervisors the supervisors to run
   * @param feed the execution events
   * @param dispatcher applies the commands
   * @param executionLog records commands that were not applied
   * @param meters registry for the command counter
   * @param properties the {@code yukta.supervisor} settings
   */
  @Autowired
  public SupervisorHost(
      final ObjectProvider<ExecutionSupervisor> supervisors,
      final ExecutionEventFeed feed,
      final DirectiveDispatcher dispatcher,
      final ExecutionLogStore executionLog,
      final MeterRegistry meters,
      final YuktaProperties properties) {
    this(
        supervisors,
        feed,
        dispatcher,
        executionLog,
        meters,
        properties.supervisor().dryRun(),
        properties.supervisor().maxCommandsPerSecond(),
        properties.supervisor().bufferSize());
  }

  /**
   * Creates the host with explicit limits.
   *
   * @param supervisors the supervisors to host
   * @param feed the event feed
   * @param dispatcher applies the commands
   * @param executionLog the execution log
   * @param meters registry for the command counter
   * @param dryRun only record commands instead of applying them
   * @param maxCommandsPerSecond most commands one supervisor may issue per second
   * @param bufferSize events a supervisor may have waiting before events are dropped for it
   */
  @SuppressFBWarnings("EI_EXPOSE_REP2")
  public SupervisorHost(
      final ObjectProvider<ExecutionSupervisor> supervisors,
      final ExecutionEventFeed feed,
      final DirectiveDispatcher dispatcher,
      final ExecutionLogStore executionLog,
      final MeterRegistry meters,
      final boolean dryRun,
      final int maxCommandsPerSecond,
      final int bufferSize) {
    this.bufferSize = bufferSize;
    this.supervisors = supervisors;
    this.feed = feed;
    this.dispatcher = dispatcher;
    this.executionLog = executionLog;
    this.meters = meters;
    this.dryRun = dryRun;
    this.maxCommandsPerSecond = maxCommandsPerSecond;
  }

  /** Starts every supervisor. */
  @PostConstruct
  public void start() {
    supervisors.stream().forEach(supervisor -> subscriptions.add(launch(supervisor, dryRun)));
  }

  /**
   * Shares the set of supervisors attached at run time with the reviewer.
   *
   * @param attached the set
   */
  @Autowired
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Spring injects the shared service, which is meant to be held")
  public void setAttachedSupervisors(final AttachedSupervisors attached) {
    this.attachedSupervisors = attached;
  }

  /**
   * Starts a supervisor made from a binding, replacing one with the same binding id. It sees the
   * events from now on, so a run already going is supervised from where it is.
   *
   * @param supervisor the supervisor
   */
  public void attach(final BoundSupervisor supervisor) {
    lock.lock();
    try {
      detach(supervisor.id());
      attachedSupervisors.put(supervisor);
      attachedSubscriptions.put(supervisor.id(), launch(supervisor, supervisor.dryRun() || dryRun));
    } finally {
      lock.unlock();
    }
  }

  /**
   * Stops an attached supervisor.
   *
   * @param bindingId the binding id
   * @return true if one was attached
   */
  public boolean detach(final String bindingId) {
    lock.lock();
    try {
      final Disposable subscription = attachedSubscriptions.remove(bindingId);
      if (subscription != null) {
        subscription.dispose();
      }
      meters.find(COMMANDS_METRIC).tag("id", bindingId).counters().forEach(meters::remove);
      return attachedSupervisors.remove(bindingId) != null;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Whether a supervisor configured as a bean has this id.
   *
   * @param id the id
   * @return true if one has
   */
  public boolean isBuiltIn(final String id) {
    return supervisors.stream().anyMatch(supervisor -> id.equals(supervisor.id()));
  }

  /**
   * Records that a dry-run supervisor would have refused a command, so the cockpit shows it.
   *
   * @param supervisorId the supervisor or binding
   * @param command the command it would have refused
   * @param reason why
   */
  public void wouldHaveRefused(
      final String supervisorId, final ExecutionControlCommand command, final String reason) {
    count(supervisorId, DRY_RUN);
    decided(supervisorId, DRY_RUN, command, reason);
  }

  /** Stops every supervisor. */
  @PreDestroy
  public void stop() {
    lock.lock();
    try {
      subscriptions.forEach(Disposable::dispose);
      subscriptions.clear();
      attachedSubscriptions.values().forEach(Disposable::dispose);
      attachedSubscriptions.clear();
    } finally {
      lock.unlock();
    }
  }

  /**
   * Describes the supervisors that run and what they have done since startup.
   *
   * @return one entry per supervisor, in review order
   */
  public List<SupervisorInfo> status() {
    return Stream.concat(
            supervisors.orderedStream().sorted(Comparator.comparingInt(ExecutionSupervisor::order)),
            attachedSupervisors.stream().sorted(Comparator.comparing(BoundSupervisor::id)))
        .map(
            supervisor -> {
              final ExecutionSupervisor real =
                  supervisor instanceof BoundSupervisor bound ? bound.delegate() : supervisor;
              return new SupervisorInfo(
                  supervisor.id(),
                  real instanceof ControlPlanePlugin ? "CONTROL_PLANE" : "SUPERVISOR",
                  typeName(real.getClass()),
                  supervisor.order(),
                  dryRun || supervisor instanceof BoundSupervisor bound && bound.dryRun(),
                  maxCommandsPerSecond,
                  commands(supervisor.id(), "applied"),
                  commands(supervisor.id(), "rejected"),
                  commands(supervisor.id(), DRY_RUN),
                  commands(supervisor.id(), "rate-limited"),
                  supervisor.scope().kind().name(),
                  supervisor.scope().sessionId(),
                  supervisor.scope().workflowId());
            })
        .toList();
  }

  /** An anonymous class has no simple name. */
  private static String typeName(final Class<?> type) {
    return type.getSimpleName().isEmpty() ? type.getName() : type.getSimpleName();
  }

  private long commands(final String supervisorId, final String outcome) {
    final Counter counter =
        meters.find(COMMANDS_METRIC).tags("id", supervisorId, "outcome", outcome).counter();
    return counter == null ? 0 : (long) counter.count();
  }

  private Disposable launch(final ExecutionSupervisor supervisor, final boolean recordOnly) {
    final String supervisorId = supervisor.id();
    final RateLimit limit = new RateLimit(maxCommandsPerSecond);
    log.atInfo()
        .addKeyValue(SUPERVISOR_KEY, supervisorId)
        .addKeyValue("dryRun", recordOnly)
        .log("Starting supervisor");
    return Flux.defer(() -> supervisor.supervise(withoutOwnOutcomes(supervisor)))
        .doOnError(
            error ->
                log.atWarn()
                    .setCause(error)
                    .addKeyValue(SUPERVISOR_KEY, supervisorId)
                    .log("Supervisor failed; restarting"))
        .retryWhen(
            Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1)).maxBackoff(Duration.ofMinutes(1)))
        .concatMap(command -> apply(supervisorId, command, limit, recordOnly))
        .subscribeOn(Schedulers.boundedElastic())
        .subscribe();
  }

  /** A supervisor does not see the outcome of its own commands, so it cannot answer itself. */
  private Flux<ExecutionEvent> withoutOwnOutcomes(final ExecutionSupervisor supervisor) {
    final String supervisorId = supervisor.id();
    final SupervisorScope scope = supervisor.scope();
    final AtomicLong missed = new AtomicLong();
    return feed.events()
        .filter(event -> scope.matches(event.sessionId(), event.workflowId()))
        .filter(
            event ->
                !(event instanceof ExecutionEvent.ControlOutcome outcome
                    && supervisorId.equals(outcome.actor())))
        // A large buffer absorbs a burst; only an overflow drops, and the supervisor is then told
        // (below) so it can read the durable log for the gap.
        .onBackpressureBuffer(
            bufferSize,
            _ -> {
              missed.incrementAndGet();
              meters.counter("yukta.tracker.dropped", "kind", "supervisor").increment();
            },
            BufferOverflowStrategy.DROP_LATEST)
        // The feed delivers on the publisher's thread (the tracker's); a supervisor that blocks,
        // for example on a model call, must not hold the runs up.
        .publishOn(Schedulers.boundedElastic(), SUPERVISOR_PREFETCH)
        // ponytail: the gap is reported with the next event, so it is late if the feed then goes
        // quiet; add a timer if that matters.
        .flatMapIterable(
            event -> {
              final long count = missed.getAndSet(0);
              return count == 0
                  ? List.of(event)
                  : List.of(new ExecutionEvent.EventsMissed(count, Instant.now()), event);
            });
  }

  private Mono<Void> apply(
      final String supervisorId,
      final ExecutionControlCommand command,
      final RateLimit limit,
      final boolean recordOnly) {
    final Mono<Void> result;
    if (limit.tryAcquire()) {
      result =
          recordOnly
              ? notApplied(supervisorId, DRY_RUN, command, DRY_RUN)
              : send(supervisorId, command);
    } else {
      result = notApplied(supervisorId, "rate-limited", command, "rate limit exceeded");
    }
    return result;
  }

  private Mono<Void> send(final String supervisorId, final ExecutionControlCommand command) {
    return dispatcher
        .dispatchOrdered(command)
        .contextWrite(Context.of(ControlActor.CONTEXT_KEY, supervisorId))
        .doOnSuccess(
            _ -> {
              count(supervisorId, "applied");
              decided(supervisorId, "applied", command, null);
            })
        .onErrorResume(
            error -> {
              count(supervisorId, "rejected");
              decided(supervisorId, "rejected", command, error.getMessage());
              log.atWarn()
                  .addKeyValue(SUPERVISOR_KEY, supervisorId)
                  .addKeyValue("command", command.getClass().getSimpleName())
                  .addKeyValue("reason", error.getMessage())
                  .log("Supervisor command rejected");
              return Mono.empty();
            });
  }

  /** A command that is not applied is still recorded, so the audit shows what was intended. */
  private Mono<Void> notApplied(
      final String supervisorId,
      final String outcome,
      final ExecutionControlCommand command,
      final String reason) {
    count(supervisorId, outcome);
    decided(supervisorId, outcome, command, reason);
    ControlAudit.record(
        executionLog,
        auditSink,
        new AuditRecord(
            false,
            command.executionId(),
            command.getClass().getSimpleName(),
            supervisorId,
            reason,
            Instant.now()));
    return Mono.empty();
  }

  /**
   * Passes every audit record on to a sink, besides the execution log.
   *
   * @param sink the sink, if the application has one
   */
  @Autowired(required = false)
  public void setAuditSink(final AuditSink sink) {
    this.auditSink = sink;
  }

  private void decided(
      final String supervisorId,
      final String outcome,
      final ExecutionControlCommand command,
      @Nullable final String reason) {
    final SupervisorDecision decision =
        new SupervisorDecision(
            Instant.now(),
            supervisorId,
            outcome,
            command.getClass().getSimpleName(),
            command.executionId(),
            reason);
    while (!recentDecisions.offerLast(decision)) {
      recentDecisions.pollFirst();
    }
  }

  /**
   * The newest decisions about the commands supervisors emitted, since startup.
   *
   * @param supervisorId only this supervisor or binding, or null for all
   * @param limit most entries to return
   * @return the decisions, newest first
   */
  public List<SupervisorDecision> decisions(@Nullable final String supervisorId, final int limit) {
    return List.copyOf(recentDecisions).reversed().stream()
        .filter(d -> supervisorId == null || supervisorId.equals(d.supervisorId()))
        .limit(Math.max(0, limit))
        .toList();
  }

  private void count(final String supervisorId, final String outcome) {
    meters.counter(COMMANDS_METRIC, "id", supervisorId, "outcome", outcome).increment();
  }

  /** Allows a fixed number of calls per second. */
  private static final class RateLimit {

    /** Most calls per second. */
    private final int max;

    /** Second of the current window and calls made in it, packed as {@code second << 32 | used}. */
    private final AtomicLong state = new AtomicLong();

    /* default */ RateLimit(final int max) {
      this.max = max;
    }

    /* default */ boolean tryAcquire() {
      final long second = System.currentTimeMillis() / 1000;
      final long next =
          state.updateAndGet(
              current -> (current >>> 32) == second ? current + 1 : (second << 32) + 1);
      return (next & 0xFFFFFFFFL) <= max;
    }
  }
}
