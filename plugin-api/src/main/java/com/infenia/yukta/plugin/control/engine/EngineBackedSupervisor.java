// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control.engine;

import com.infenia.yukta.plugin.control.ApprovalNeed;
import com.infenia.yukta.plugin.control.ControlRequest;
import com.infenia.yukta.plugin.control.Decision;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.SupervisorScope;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Runs a {@link SupervisorEngine} as an {@link ExecutionSupervisor}: execution events become {@link
 * Signal.Event}s, a review becomes a {@link Signal.Review} and waits for the engine's verdict, and
 * a {@link Directive.Command} is emitted as the supervisor's command. The host then applies the
 * usual scope, capability, rate and dry-run checks to it.
 */
@SuppressWarnings({"PMD.TooManyMethods", "PMD.CouplingBetweenObjects"})
public final class EngineBackedSupervisor
    implements DirectiveSupervisor, ReadsAware, AutoCloseable {

  private static final Duration REVIEW_TIMEOUT = Duration.ofSeconds(10);

  /** Most reviews waiting for the engine at once; more are treated as unanswered. */
  public static final int MAX_OPEN_REVIEWS = 100;

  /** The most executions and input requests a {@code Started} snapshot lists. */
  public static final int SNAPSHOT_ROWS = 200;

  private final String supervisorId;
  private final SupervisorScope owned;
  private final SupervisorEngine engine;
  private final boolean reviews;
  private final Duration reviewTimeout;
  private final Map<String, Sinks.One<Decision>> pending = new ConcurrentHashMap<>();
  private final Sinks.Many<Signal> asked =
      Sinks.many().multicast().onBackpressureBuffer(MAX_OPEN_REVIEWS, false);
  private final AtomicLong seq = new AtomicLong();
  private final Consumer<EngineReads> readsSink;
  private final java.util.concurrent.atomic.AtomicReference<EngineReads> reads =
      new java.util.concurrent.atomic.AtomicReference<>(EngineReads.NONE);
  private final java.util.concurrent.atomic.AtomicBoolean startedOnce =
      new java.util.concurrent.atomic.AtomicBoolean();

  /**
   * Creates the supervisor.
   *
   * @param id the supervisor id
   * @param scope what it supervises
   * @param engine its decision maker
   * @param reviews whether the engine answers {@link Signal.Review}s; if not, every command is
   *     allowed without asking
   */
  public EngineBackedSupervisor(
      final String id,
      final SupervisorScope scope,
      final SupervisorEngine engine,
      final boolean reviews) {
    this(id, scope, engine, reviews, REVIEW_TIMEOUT, reads -> {});
  }

  /* default */ EngineBackedSupervisor(
      final String id,
      final SupervisorScope scope,
      final SupervisorEngine engine,
      final boolean reviews,
      final Duration reviewTimeout,
      final Consumer<EngineReads> readsSink) {
    this.supervisorId = id;
    this.owned = scope;
    this.engine = engine;
    this.reviews = reviews;
    this.reviewTimeout = reviewTimeout;
    this.readsSink = readsSink;
  }

  @Override
  @SuppressWarnings("PMD.ShortMethodName")
  public String id() {
    return supervisorId;
  }

  @Override
  public SupervisorScope scope() {
    return owned;
  }

  @Override
  public Flux<Directive.Command> directives(final Flux<ExecutionEvent> events) {
    final Flux<Signal> signals =
        // the snapshot comes first; what happens while it is read is in it or comes after it
        Flux.concat(started(), Flux.merge(events.map(this::event), asked.asFlux()));
    return engine
        .run(signals)
        .flatMap(
            directive ->
                switch (directive) {
                  case Directive.Command command -> Mono.just(command);
                  case Directive.ReviewVerdict verdict -> answer(verdict);
                  case Directive.NoOpinion none -> answer(none);
                  default -> Mono.empty();
                });
  }

  /**
   * The snapshot an engine starts from: what it owns now, the open inputs, what it holds and the
   * cursor of the newest event, so a level-triggered engine never depends on one missed event.
   */
  private Mono<Signal> started() {
    return Mono.defer(
        () -> {
          final String reason = startedOnce.getAndSet(true) ? "resubscribe" : "first";
          final String cursor = "e" + seq.get();
          final EngineReads now = reads.get();
          return Mono.zip(
                  now.executions(null, SNAPSHOT_ROWS)
                      .map(EngineReads.RunView::executionId)
                      .collectList()
                      .onErrorReturn(List.of()),
                  now.openInputs(SNAPSHOT_ROWS)
                      .map(com.infenia.yukta.plugin.input.InputRequest::id)
                      .collectList()
                      .onErrorReturn(List.of()),
                  now.held().collectList().onErrorReturn(List.of()))
              .map(
                  all ->
                      (Signal)
                          new Signal.Started(
                              reason, cursor, all.getT1(), all.getT2(), all.getT3()));
        });
  }

  private Signal event(final ExecutionEvent event) {
    final String n = "e" + seq.incrementAndGet();
    return new Signal.Event(n, n, event);
  }

  private Mono<Directive.Command> answer(final Directive.ReviewVerdict verdict) {
    final Sinks.One<Decision> sink = pending.get(verdict.answers());
    if (sink != null) {
      sink.tryEmitValue(
          switch (verdict.verdict()) {
            case ALLOW -> Decision.ALLOW;
            case DENY -> Decision.deny(reason(verdict.reason()));
            case REQUIRE_APPROVAL ->
                Decision.requireApproval(reason(verdict.reason()), ApprovalNeed.DEFAULT);
          });
    }
    return Mono.empty();
  }

  private Mono<Directive.Command> answer(final Directive.NoOpinion none) {
    final Sinks.One<Decision> sink = pending.get(none.answers());
    if (sink != null) {
      sink.tryEmitValue(Decision.ALLOW);
    }
    return Mono.empty();
  }

  private static String reason(final String reason) {
    return reason == null ? "no reason given" : reason;
  }

  @Override
  public Mono<Decision> review(final ControlRequest request) {
    if (!reviews) {
      return Mono.just(Decision.ALLOW);
    }
    if (pending.size() >= MAX_OPEN_REVIEWS) {
      // an error, not an answer: the reviewer refuses it (a stop is still let through)
      return Mono.error(new IllegalStateException("too many open reviews"));
    }
    final String reviewId = "r" + seq.incrementAndGet();
    final Sinks.One<Decision> sink = Sinks.one();
    pending.put(reviewId, sink);
    asked.tryEmitNext(new Signal.Review(reviewId, request, reviewTimeout));
    return sink.asMono().timeout(reviewTimeout).doFinally(done -> pending.remove(reviewId));
  }

  @Override
  public void readsFrom(final EngineReads given) {
    reads.set(given);
    readsSink.accept(given);
  }

  @Override
  public void close() {
    asked.tryEmitComplete();
    engine.close();
  }
}
