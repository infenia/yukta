// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import com.infenia.yukta.config.YuktaProperties;
import com.infenia.yukta.plugin.control.ControlActor;
import com.infenia.yukta.plugin.control.ControlPlanePlugin;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.Supervisor;
import com.infenia.yukta.plugin.control.SupervisorCapabilities;
import com.infenia.yukta.plugin.control.SupervisorIds;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.plugin.control.engine.Directive;
import com.infenia.yukta.plugin.control.engine.DirectiveSupervisor;
import com.infenia.yukta.security.AuditRecord;
import com.infenia.yukta.security.AuditSink;
import com.infenia.yukta.service.control.ControlAudit;
import com.infenia.yukta.service.control.SupervisedControl;
import com.infenia.yukta.service.control.directive.DirectiveDispatcher;
import com.infenia.yukta.service.log.ExecutionLogStore;
import com.infenia.yukta.service.session.InternalSessions;
import com.infenia.yukta.service.supervisor.engine.DirectiveGate;
import com.infenia.yukta.service.supervisor.engine.HoldLedger;
import com.infenia.yukta.service.supervisor.engine.SupervisorQueries;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
 * recorded. It acts only within its scope: a command for another run, for a node outside its
 * section, for a whole run from a section supervisor, or for no run from a supervisor that is not
 * global is not sent ({@code outcome=out-of-scope}).
 */
@Slf4j
@Component
@SuppressWarnings({
  "PMD.TooManyMethods",
  "PMD.ExcessiveImports",
  "PMD.CouplingBetweenObjects",
  "PMD.GodClass"
})
public class SupervisorHost {

  /** Events handed to the supervisor's thread at a time. */
  private static final int SUPERVISOR_PREFETCH = 256;

  /** Most decisions kept for the decision feed. */
  private static final int MAX_DECISIONS = 500;

  /** Counter of supervisor commands by outcome. */
  private static final String COMMANDS_METRIC = "yukta.supervisor.commands";

  /** Outcome recorded for a command a dry-run supervisor would have refused. */
  private static final String DRY_RUN = "dry-run";

  /** Outcome recorded for a command that a review parked until it is approved. */
  private static final String AWAITING_APPROVAL = "awaiting-approval";

  /** Outcome recorded for a command whose target is outside the supervisor's scope. */
  private static final String OUT_OF_SCOPE = "out-of-scope";

  /** Key of the supervisor in log entries. */
  private static final String SUPERVISOR_KEY = "supervisor";

  /** The supervisors to run. */
  private final ObjectProvider<ExecutionSupervisor> supervisors;

  /** The execution events. */
  private final ExecutionEventFeed feed;

  /** Applies the commands. */
  private final DirectiveDispatcher dispatcher;

  /** Applies the commands of the supervisors. */
  private final SupervisedControl control;

  /** Records commands that were not applied. */
  private final ExecutionLogStore executionLog;

  /** Receives every audit record besides the log, if a module provides one. */
  @Nullable private AuditSink auditSink;

  /** Tells which sessions are internal; absent where nothing is hidden. */
  @Nullable private InternalSessions internalSessions;

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

  /** Serializes attaching, detaching and stopping, so a attachment never has two subscriptions. */
  private final ReentrantLock lock = new ReentrantLock();

  /** Subscriptions of the supervisors attached at run time, by attachment id. */
  private final Map<String, Disposable> attachedSubscriptions = new ConcurrentHashMap<>();

  /** The supervisors attached at run time. */
  private AttachedSupervisors attachedSupervisors = new AttachedSupervisors();

  /** The runs each supervisor paused. */
  private final HoldLedger holds = new HoldLedger();

  /** Reads the status of a run for {@code ifStatus}, looked up late; null where none is given. */
  @Nullable private ObjectProvider<SupervisorQueries> queries;

  /** Checks the commands of engines. */
  private final DirectiveGate directiveGate = new DirectiveGate();

  /** Orders the conflicting commands of supervisors. */
  private final TurnAroundGuard guard;

  /**
   * Creates the host.
   *
   * @param supervisors the supervisors to run
   * @param feed the execution events
   * @param dispatcher finds the target of a command
   * @param control applies the commands
   * @param executionLog records commands that were not applied
   * @param meters registry for the command counter
   * @param properties the {@code yukta.supervisor} settings
   */
  @Autowired
  public SupervisorHost(
      final ObjectProvider<ExecutionSupervisor> supervisors,
      final ExecutionEventFeed feed,
      final DirectiveDispatcher dispatcher,
      final SupervisedControl control,
      final ExecutionLogStore executionLog,
      final MeterRegistry meters,
      final YuktaProperties properties) {
    this(
        supervisors,
        feed,
        dispatcher,
        control,
        executionLog,
        meters,
        properties.supervisor().dryRun(),
        properties.supervisor().maxCommandsPerSecond(),
        properties.supervisor().bufferSize(),
        new TurnAroundGuard(properties.supervisor().turnAround()));
  }

  /**
   * Creates the host with a guard against a supervisor flapping against itself.
   *
   * @param supervisors the supervisors to host
   * @param feed the event feed
   * @param dispatcher finds the target of a command
   * @param control sends their commands
   * @param executionLog records what happened to their commands
   * @param meters registry for the metrics
   * @param dryRun whether commands are only recorded
   * @param maxCommandsPerSecond the command rate limit of one supervisor
   * @param bufferSize events a supervisor may have waiting
   * @param guard stops a supervisor from turning a target around too often
   */
  @SuppressWarnings("PMD.ExcessiveParameterList")
  public SupervisorHost(
      final ObjectProvider<ExecutionSupervisor> supervisors,
      final ExecutionEventFeed feed,
      final DirectiveDispatcher dispatcher,
      final SupervisedControl control,
      final ExecutionLogStore executionLog,
      final MeterRegistry meters,
      final boolean dryRun,
      final int maxCommandsPerSecond,
      final int bufferSize,
      final TurnAroundGuard guard) {
    this.guard = guard;
    this.bufferSize = bufferSize;
    this.supervisors = supervisors;
    this.feed = feed;
    this.dispatcher = dispatcher;
    this.control = control;
    this.executionLog = executionLog;
    this.meters = meters;
    this.dryRun = dryRun;
    this.maxCommandsPerSecond = maxCommandsPerSecond;
  }

  /** Starts every supervisor. */
  @PostConstruct
  public void start() {
    supervisors.stream()
        .forEach(
            supervisor -> {
              checkBeanId(supervisor);
              subscriptions.add(launch(supervisor, dryRun));
            });
  }

  /** Fails start-up for a bean supervisor whose id breaks the naming rule. */
  private static void checkBeanId(final ExecutionSupervisor supervisor) {
    try {
      SupervisorIds.validate(supervisor.id(), "id");
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException(
          "The supervisor bean "
              + supervisor.getClass().getName()
              + " has an invalid id: "
              + e.getMessage(),
          e);
    }
  }

  /**
   * Gives the host the reads for {@code ifStatus}.
   *
   * @param supervisorQueries looked up late: the queries need services that need the host
   */
  @Autowired
  public void setQueries(final ObjectProvider<SupervisorQueries> supervisorQueries) {
    this.queries = supervisorQueries;
  }

  /**
   * The runs a supervisor paused and has not resumed.
   *
   * @param supervisorId the supervisor id, or the id of an attachment
   * @return the run ids, oldest first
   */
  public List<String> held(final String supervisorId) {
    final List<String> out = new ArrayList<>(holds.held(supervisorId));
    attachedSupervisors.stream()
        .filter(bound -> bound.attachment().supervisorId().equals(supervisorId))
        .forEach(bound -> out.addAll(holds.held(bound.id())));
    return out.stream().distinct().toList();
  }

  /**
   * Shares the set of supervisors attached at run time with the reviewer.
   *
   * @param attached the set
   */
  @Autowired
  public void setAttachedSupervisors(final AttachedSupervisors attached) {
    this.attachedSupervisors = attached;
  }

  /**
   * Starts a supervisor made from a attachment, replacing one with the same attachment id. It sees
   * the events from now on, so a run already going is supervised from where it is.
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
   * @param attachmentId the attachment id
   * @return true if one was attached
   */
  public boolean detach(final String attachmentId) {
    lock.lock();
    try {
      final Disposable subscription = attachedSubscriptions.remove(attachmentId);
      if (subscription != null) {
        subscription.dispose();
      }
      meters.find(COMMANDS_METRIC).tag("id", attachmentId).counters().forEach(meters::remove);
      final BoundSupervisor removed = attachedSupervisors.remove(attachmentId);
      if (removed != null) {
        close(removed);
      }
      return removed != null;
    } finally {
      lock.unlock();
    }
  }

  /** Releases what an engine holds when its supervisor is detached. */
  @SuppressWarnings("PMD.CloseResource") // closing it is the point; the host does not own it
  private static void close(final BoundSupervisor removed) {
    if (removed.delegate() instanceof AutoCloseable closeable) {
      try {
        closeable.close();
      } catch (Exception e) { // NOPMD - AutoCloseable.close throws Exception; a detach goes on
        log.atWarn()
            .setCause(e)
            .addKeyValue(SUPERVISOR_KEY, removed.id())
            .log("A supervisor did not close cleanly");
      }
    }
  }

  /** Stops every attached supervisor, for a server that is no longer the leader. */
  public void detachAll() {
    lock.lock();
    try {
      new ArrayList<>(attachedSubscriptions.keySet()).forEach(this::detach);
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
   * @param supervisorId the supervisor or attachment
   * @param command the command it would have refused
   * @param reason why
   */
  public void wouldHaveRefused(
      final String supervisorId, final ExecutionControlCommand command, final String reason) {
    count(supervisorId, DRY_RUN);
    decided(supervisorId, DRY_RUN, command, reason);
  }

  /**
   * Hides the events of internal sessions from supervisors.
   *
   * @param internal the lookup
   */
  @Autowired(required = false)
  public void setInternalSessions(final InternalSessions internal) {
    this.internalSessions = internal;
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
   * @return one entry per supervisor, by id
   */
  public List<SupervisorInfo> status() {
    return Stream.concat(
            supervisors.orderedStream().sorted(Comparator.comparing(ExecutionSupervisor::id)),
            attachedSupervisors.stream().sorted(Comparator.comparing(BoundSupervisor::id)))
        .map(
            supervisor -> {
              final ExecutionSupervisor real =
                  supervisor instanceof BoundSupervisor bound ? bound.delegate() : supervisor;
              return new SupervisorInfo(
                  supervisor.id(),
                  kind(real),
                  typeName(real.getClass()),
                  dryRun || supervisor instanceof BoundSupervisor bound && bound.dryRun(),
                  maxCommandsPerSecond,
                  commands(supervisor.id(), "applied"),
                  commands(supervisor.id(), "rejected"),
                  commands(supervisor.id(), DRY_RUN),
                  commands(supervisor.id(), "rate-limited"),
                  supervisor.scope().kind().name(),
                  supervisor.scope().sessionId(),
                  supervisor.scope().workflowId(),
                  supervisor.scope() instanceof SupervisorScope.Section section
                      ? List.copyOf(section.nodes())
                      : null,
                  commands(supervisor.id(), "flapping-blocked"));
            })
        .toList();
  }

  private static String kind(final ExecutionSupervisor real) {
    return real instanceof ControlPlanePlugin ? "CONTROL_PLANE" : "SUPERVISOR";
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
    final SupervisorCapabilities allowed =
        supervisor instanceof BoundSupervisor bound ? bound.capabilities() : null;
    log.atInfo()
        .addKeyValue(SUPERVISOR_KEY, supervisorId)
        .addKeyValue("dryRun", recordOnly)
        .log("Starting supervisor");
    return Flux.defer(() -> commandsOf(supervisor, recordOnly))
        .doOnError(
            error ->
                log.atWarn()
                    .setCause(error)
                    .addKeyValue(SUPERVISOR_KEY, supervisorId)
                    .log("Supervisor failed; restarting"))
        .retryWhen(
            Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1)).maxBackoff(Duration.ofMinutes(1)))
        .concatMap(
            command ->
                Mono.defer(
                        () ->
                            apply(
                                supervisorId,
                                supervisor.scope(),
                                command,
                                limit,
                                recordOnly,
                                allowed))
                    // One command that cannot be handled (a log or audit sink that throws) must
                    // not end the supervisor: the retry above covers only its own errors.
                    .onErrorResume(
                        error -> {
                          log.atError()
                              .setCause(error)
                              .addKeyValue(SUPERVISOR_KEY, supervisorId)
                              .log("Could not handle a supervisor command; carrying on");
                          return Mono.empty();
                        }))
        .subscribeOn(Schedulers.boundedElastic())
        .subscribe();
  }

  /**
   * The commands of a supervisor. Those of a {@link DirectiveSupervisor} first pass the {@link
   * DirectiveGate}: a reason and an {@code initiative} entry for a command nobody prompted, the
   * initiative rate, and at most one application per idempotency key.
   */
  private Flux<ExecutionControlCommand> commandsOf(
      final ExecutionSupervisor supervisor, final boolean recordOnly) {
    final Flux<ExecutionEvent> events = withoutOwnOutcomes(supervisor);
    final ExecutionSupervisor real =
        supervisor instanceof BoundSupervisor bound ? bound.delegate() : supervisor;
    if (!(real instanceof DirectiveSupervisor directives)) {
      return supervisor.supervise(events);
    }
    final SupervisorCapabilities capabilities =
        supervisor instanceof BoundSupervisor bound
            ? bound.capabilities()
            : SupervisorCapabilities.from(new Supervisor(supervisor.id(), "bean", Map.of(), false));
    final Set<String> seen = Collections.newSetFromMap(new BoundedKeys());
    return directives
        .directives(events)
        .concatMap(
            directive ->
                seen.add(directive.key())
                    ? gated(supervisor, directive, capabilities, recordOnly)
                    : Mono.empty());
  }

  private Mono<ExecutionControlCommand> gated(
      final ExecutionSupervisor supervisor,
      final Directive.Command directive,
      final SupervisorCapabilities capabilities,
      final boolean recordOnly) {
    if (supervisor instanceof BoundSupervisor
        && supervisor.scope() instanceof SupervisorScope.Global) {
      // an attached Global scope owns nothing (it only observes), so it commands nothing
      return notApplied(
              supervisor.id(),
              "not-owned",
              directive.command(),
              "refused: NOT_OWNED (" + directive.cause().text() + ")")
          .then(Mono.empty());
    }
    return dispatcher
        .target(directive.command())
        .map(t -> new DirectiveGate.Target(t.sessionId(), t.workflowId(), t.nodeId()))
        .onErrorReturn(new DirectiveGate.Target(null, null, null))
        .flatMap(
            target -> {
              final DirectiveGate.Decision decision =
                  directiveGate.check(
                      supervisor.id(),
                      directive,
                      target,
                      capabilities,
                      supervisor.scope(),
                      true,
                      recordOnly);
              final DirectiveGate.Rejection rejection = decision.rejection();
              if (rejection == null) {
                return preconditionHolds(directive, target)
                    .flatMap(
                        holds ->
                            holds
                                ? Mono.just(directive.command())
                                : notApplied(
                                        supervisor.id(),
                                        "precondition-failed",
                                        directive.command(),
                                        "the run is not in status " + directive.ifStatus())
                                    .then(Mono.<ExecutionControlCommand>empty()));
              }
              return notApplied(
                      supervisor.id(),
                      rejection.name().toLowerCase(Locale.ROOT).replace('_', '-'),
                      directive.command(),
                      "refused: " + rejection + " (" + directive.cause().text() + ")")
                  .then(Mono.<ExecutionControlCommand>empty());
            });
  }

  /** Whether a command's {@code ifStatus} matches the run's status now; true when it names none. */
  private Mono<Boolean> preconditionHolds(
      final Directive.Command directive, final DirectiveGate.Target target) {
    final SupervisorQueries reads = queries == null ? null : queries.getIfAvailable();
    if (directive.ifStatus() == null || reads == null || target.sessionId() == null) {
      return Mono.just(true);
    }
    return reads
        .statusOf(target.sessionId(), directive.command().executionId())
        .map(directive.ifStatus()::equalsIgnoreCase)
        .defaultIfEmpty(false)
        .onErrorReturn(false);
  }

  /** The keys remembered per supervisor: the newest 1000. */
  private static final class BoundedKeys extends LinkedHashMap<String, Boolean> {
    private static final long serialVersionUID = 1L;

    @Override
    protected boolean removeEldestEntry(final Map.Entry<String, Boolean> eldest) {
      return size() > 1000;
    }
  }

  /**
   * Events of an internal session reach a supervisor only when its supervisor says {@code
   * observeInternal: true} and its scope names that session.
   */
  private Mono<Boolean> visible(
      final ExecutionEvent event, final SupervisorScope scope, final boolean observeInternal) {
    final InternalSessions internal = internalSessions;
    final String sessionId = event.sessionId();
    if (internal == null || sessionId == null) {
      return Mono.just(true);
    }
    return internal
        .checkInternal(sessionId)
        .map(hidden -> !hidden || observeInternal && sessionId.equals(scope.sessionId()));
  }

  /** A supervisor does not see the outcome of its own commands, so it cannot answer itself. */
  private Flux<ExecutionEvent> withoutOwnOutcomes(final ExecutionSupervisor supervisor) {
    final String supervisorId = supervisor.id();
    final SupervisorScope scope = supervisor.scope();
    final AtomicLong missed = new AtomicLong();
    final boolean observeInternal =
        supervisor instanceof BoundSupervisor bound && bound.observeInternal();
    return feed.events()
        .filter(event -> scope.matches(event.sessionId(), event.workflowId(), event.nodeId()))
        .filterWhen(event -> visible(event, scope, observeInternal))
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
      final SupervisorScope scope,
      final ExecutionControlCommand command,
      final RateLimit limit,
      final boolean recordOnly,
      final @Nullable SupervisorCapabilities allowed) {
    final Mono<Void> result;
    if (allowed != null && !allowed.allows(command)) {
      // dropped before the rate limit: a command the supervisor may not send costs it nothing
      result =
          notApplied(
              supervisorId,
              "forbidden",
              command,
              command.getClass().getSimpleName() + " is not in allowedCommands of this supervisor");
    } else {
      // out of scope is dropped before the rate limit too, and in dry-run, so the record is the
      // same either way
      result =
          inScope(scope, command, allowed != null)
              .flatMap(
                  inside ->
                      inside
                          ? guarded(supervisorId, command, limit, recordOnly)
                          : notApplied(
                              supervisorId,
                              OUT_OF_SCOPE,
                              command,
                              "target outside scope " + scope.key()));
    }
    return result;
  }

  /**
   * Whether a command acts within a scope: on a run, and for a section on a node, that the scope
   * matches. A command that belongs to no run is within a global scope only, and one on a whole run
   * is never within a section. A target that cannot be read counts as outside.
   */
  private Mono<Boolean> inScope(
      final SupervisorScope scope, final ExecutionControlCommand command, final boolean attached) {
    // an attached Global scope owns nothing (it only observes); a bean supervisor keeps it all
    return scope instanceof SupervisorScope.Global
        ? Mono.just(!attached)
        : dispatcher
            .target(command)
            .map(target -> scope.matches(target.sessionId(), target.workflowId(), target.nodeId()))
            .onErrorReturn(false);
  }

  /** Blocks a supervisor that keeps turning a target around, else applies the command. */
  private Mono<Void> guarded(
      final String supervisorId,
      final ExecutionControlCommand command,
      final RateLimit limit,
      final boolean recordOnly) {
    return guard
        .check(supervisorId, command)
        .map(reason -> notApplied(supervisorId, "flapping-blocked", command, reason))
        .orElseGet(() -> limited(supervisorId, command, limit, recordOnly));
  }

  /** Applies a command in scope, or records it in dry-run or over the rate limit. */
  private Mono<Void> limited(
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
    return control
        .engine(command)
        .contextWrite(
            Context.of(
                ControlActor.CONTEXT_KEY,
                supervisorId,
                ControlActor.KIND_KEY,
                ControlActor.Kind.SUPERVISOR))
        .doOnSuccess(
            _ -> {
              count(supervisorId, "applied");
              decided(supervisorId, "applied", command, null);
              holds.applied(supervisorId, command);
            })
        .onErrorResume(
            ControlAwaitingApprovalException.class,
            parked -> {
              count(supervisorId, AWAITING_APPROVAL);
              decided(
                  supervisorId,
                  AWAITING_APPROVAL,
                  command,
                  "input request " + parked.getRequestId());
              return Mono.empty();
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
   * @param supervisorId only this supervisor or attachment, or null for all
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
