// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import com.infenia.yukta.plugin.control.ControlActor;
import com.infenia.yukta.plugin.control.ControlPlanePlugin;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.service.control.directive.DirectiveDispatcher;
import com.infenia.yukta.service.log.ExecutionLogEvent;
import com.infenia.yukta.service.log.ExecutionLogStore;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
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
@SuppressWarnings("PMD.TooManyMethods")
public class SupervisorHost {

  /** Events a supervisor may have waiting before the feed starts dropping them for it. */
  private static final int SUPERVISOR_PREFETCH = 256;

  /** Counter of supervisor commands by outcome. */
  private static final String COMMANDS_METRIC = "yukta.supervisor.commands";

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

  /** Registry for the command counter. */
  private final MeterRegistry meters;

  /** Whether commands are only recorded. */
  private final boolean dryRun;

  /** Most commands one supervisor may issue per second. */
  private final int maxCommandsPerSecond;

  /** Running supervisor subscriptions. */
  private final List<Disposable> subscriptions = new CopyOnWriteArrayList<>();

  /**
   * Creates the host.
   *
   * @param supervisors the supervisors to run
   * @param feed the execution events
   * @param dispatcher applies the commands
   * @param executionLog records commands that were not applied
   * @param meters registry for the command counter
   * @param dryRun only record commands instead of applying them
   * @param maxCommandsPerSecond most commands one supervisor may issue per second
   */
  @SuppressFBWarnings("EI_EXPOSE_REP2")
  public SupervisorHost(
      final ObjectProvider<ExecutionSupervisor> supervisors,
      final ExecutionEventFeed feed,
      final DirectiveDispatcher dispatcher,
      final ExecutionLogStore executionLog,
      final MeterRegistry meters,
      @Value("${yukta.supervisor.dry-run:false}") final boolean dryRun,
      @Value("${yukta.supervisor.max-commands-per-second:20}") final int maxCommandsPerSecond) {
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
    supervisors.stream().forEach(this::launch);
  }

  /** Stops every supervisor. */
  @PreDestroy
  public void stop() {
    subscriptions.forEach(Disposable::dispose);
    subscriptions.clear();
  }

  /**
   * Describes the supervisors that run and what they have done since startup.
   *
   * @return one entry per supervisor, in review order
   */
  public List<SupervisorInfo> status() {
    return supervisors
        .orderedStream()
        .sorted(Comparator.comparingInt(ExecutionSupervisor::order))
        .map(
            supervisor ->
                new SupervisorInfo(
                    supervisor.id(),
                    supervisor instanceof ControlPlanePlugin ? "CONTROL_PLANE" : "SUPERVISOR",
                    typeName(supervisor.getClass()),
                    supervisor.order(),
                    dryRun,
                    maxCommandsPerSecond,
                    commands(supervisor.id(), "applied"),
                    commands(supervisor.id(), "rejected"),
                    commands(supervisor.id(), "dry-run"),
                    commands(supervisor.id(), "rate-limited")))
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

  private void launch(final ExecutionSupervisor supervisor) {
    final String supervisorId = supervisor.id();
    final RateLimit limit = new RateLimit(maxCommandsPerSecond);
    log.atInfo()
        .addKeyValue(SUPERVISOR_KEY, supervisorId)
        .addKeyValue("dryRun", dryRun)
        .log("Starting supervisor");
    subscriptions.add(
        Flux.defer(() -> supervisor.supervise(withoutOwnOutcomes(supervisorId)))
            .doOnError(
                error ->
                    log.atWarn()
                        .setCause(error)
                        .addKeyValue(SUPERVISOR_KEY, supervisorId)
                        .log("Supervisor failed; restarting"))
            .retryWhen(
                Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1))
                    .maxBackoff(Duration.ofMinutes(1)))
            .concatMap(command -> apply(supervisorId, command, limit))
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe());
  }

  /** A supervisor does not see the outcome of its own commands, so it cannot answer itself. */
  private Flux<ExecutionEvent> withoutOwnOutcomes(final String supervisorId) {
    return feed.events()
        .filter(
            event ->
                !(event instanceof ExecutionEvent.ControlOutcome outcome
                    && supervisorId.equals(outcome.actor())))
        .onBackpressureDrop(
            _ -> meters.counter("yukta.tracker.dropped", "kind", "supervisor").increment())
        // The feed delivers on the publisher's thread (the tracker's); a supervisor that blocks,
        // for example on a model call, must not hold the runs up.
        .publishOn(Schedulers.boundedElastic(), SUPERVISOR_PREFETCH);
  }

  private Mono<Void> apply(
      final String supervisorId, final ExecutionControlCommand command, final RateLimit limit) {
    final Mono<Void> result;
    if (limit.tryAcquire()) {
      result =
          dryRun
              ? notApplied(supervisorId, "dry-run", command, "dry-run")
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
        .doOnSuccess(_ -> count(supervisorId, "applied"))
        .onErrorResume(
            error -> {
              count(supervisorId, "rejected");
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
    executionLog.append(
        ExecutionLogEvent.control(
            false,
            command.executionId(),
            command.getClass().getSimpleName() + " [by " + supervisorId + "]",
            reason));
    return Mono.empty();
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
