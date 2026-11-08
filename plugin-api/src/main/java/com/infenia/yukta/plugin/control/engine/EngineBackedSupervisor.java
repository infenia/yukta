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
public final class EngineBackedSupervisor
    implements DirectiveSupervisor, ReadsAware, AutoCloseable {

  private static final Duration REVIEW_TIMEOUT = Duration.ofSeconds(10);

  private final String id;
  private final SupervisorScope scope;
  private final SupervisorEngine engine;
  private final boolean reviews;
  private final Duration reviewTimeout;
  private final Map<String, Sinks.One<Decision>> pending = new ConcurrentHashMap<>();
  private final Sinks.Many<Signal> asked = Sinks.many().multicast().onBackpressureBuffer();
  private final AtomicLong seq = new AtomicLong();
  private final Consumer<EngineReads> readsSink;

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

  EngineBackedSupervisor(
      final String id,
      final SupervisorScope scope,
      final SupervisorEngine engine,
      final boolean reviews,
      final Duration reviewTimeout,
      final Consumer<EngineReads> readsSink) {
    this.id = id;
    this.scope = scope;
    this.engine = engine;
    this.reviews = reviews;
    this.reviewTimeout = reviewTimeout;
    this.readsSink = readsSink;
  }

  @Override
  @SuppressWarnings("PMD.ShortMethodName")
  public String id() {
    return id;
  }

  @Override
  public SupervisorScope scope() {
    return scope;
  }

  @Override
  public Flux<Directive.Command> directives(final Flux<ExecutionEvent> events) {
    final Flux<Signal> signals =
        Flux.merge(
            Flux.<Signal>just(new Signal.Started("first", "", List.of(), List.of(), List.of())),
            events.map(this::event),
            asked.asFlux());
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
    final String reviewId = "r" + seq.incrementAndGet();
    final Sinks.One<Decision> sink = Sinks.one();
    pending.put(reviewId, sink);
    asked.tryEmitNext(new Signal.Review(reviewId, request, reviewTimeout));
    return sink.asMono()
        .timeout(reviewTimeout, Mono.just(Decision.ALLOW))
        .doFinally(done -> pending.remove(reviewId));
  }

  @Override
  public void readsFrom(final EngineReads reads) {
    readsSink.accept(reads);
  }

  @Override
  public void close() {
    asked.tryEmitComplete();
    engine.close();
  }
}
