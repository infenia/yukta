// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.input.delivery;

import com.infenia.yukta.config.YuktaProperties;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.input.InputResponder;
import com.infenia.yukta.service.input.InputBroker;
import com.infenia.yukta.service.input.InputRequestStore;
import com.infenia.yukta.service.input.route.RouteResolver;
import com.infenia.yukta.service.input.route.Tier;
import com.infenia.yukta.service.input.token.AnswerTokens;
import com.infenia.yukta.service.leader.LeaderElection;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Delivers requests to the external responders of their tier, at least once: a failed delivery is
 * tried again after 1 s, then twice as long each time up to 5 minutes, and given up after 10 tries.
 * It runs only on the leader. Each delivery carries a one-time answer token for the tier.
 *
 * @since 0.1.0-alpha.4
 */
@Slf4j
@Component
@SuppressWarnings("PMD.CouplingBetweenObjects")
public class InputDeliveryWorker implements SmartLifecycle {

  /** Most tries of one delivery. */
  public static final int MAX_ATTEMPTS = 10;

  /** First wait after a failure. */
  private static final Duration FIRST_BACKOFF = Duration.ofSeconds(1);

  /** Longest wait after a failure. */
  private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

  /** How long a token lives when the request has no deadline. */
  private static final Duration FALLBACK_TOKEN_LIFE = Duration.ofHours(1);

  /** Counter of deliveries by responder and outcome. */
  private static final String METRIC = "yukta.input.delivery";

  /** Tag and settings key of the responder selector. */
  private static final String KEY_RESPONDER = "responder";

  /** Where requests and deliveries are kept. */
  private final InputRequestStore store;

  /** Finds the route and so the settings of a tier. */
  private final RouteResolver routes;

  /** Makes the answer tokens. */
  private final AnswerTokens tokens;

  /** Writes to the execution log. */
  private final InputBroker broker;

  /** Delivers, by type. */
  private final ObjectProvider<InputResponder> responders;

  /** Only the leader delivers. */
  private final LeaderElection leader;

  /** Registry for the metrics. */
  private final MeterRegistry meters;

  /** The time. */
  private final Clock clock;

  /** How often due deliveries are looked for. */
  private final Duration interval;

  /** Runs the timer. */
  private final Scheduler scheduler;

  /** The running timer. */
  private final AtomicReference<Disposable> timer = new AtomicReference<>();

  /** Guards start and stop. */
  private final ReentrantLock lock = new ReentrantLock();

  /**
   * Creates the worker.
   *
   * @param store where requests and deliveries are kept
   * @param routes finds the settings of a tier
   * @param tokens makes the answer tokens
   * @param broker writes to the execution log
   * @param responders the responders
   * @param leader says whether this server leads
   * @param meters registry for the metrics
   * @param properties the {@code yukta.input} settings
   */
  @Autowired
  public InputDeliveryWorker(
      final InputRequestStore store,
      final RouteResolver routes,
      final AnswerTokens tokens,
      final InputBroker broker,
      final ObjectProvider<InputResponder> responders,
      final LeaderElection leader,
      final MeterRegistry meters,
      final YuktaProperties properties) {
    this(
        store,
        routes,
        tokens,
        broker,
        responders,
        leader,
        meters,
        Clock.systemUTC(),
        properties.input().sweepInterval(),
        Schedulers.parallel());
  }

  /**
   * Creates the worker with a clock and a scheduler.
   *
   * @param store where requests and deliveries are kept
   * @param routes finds the settings of a tier
   * @param tokens makes the answer tokens
   * @param broker writes to the execution log
   * @param responders the responders
   * @param leader says whether this server leads
   * @param meters registry for the metrics
   * @param clock the time
   * @param interval how often due deliveries are looked for
   * @param scheduler runs the timer
   */
  @SuppressFBWarnings("EI_EXPOSE_REP2") // shared Spring beans, held on purpose
  @SuppressWarnings("PMD.ExcessiveParameterList")
  public InputDeliveryWorker(
      final InputRequestStore store,
      final RouteResolver routes,
      final AnswerTokens tokens,
      final InputBroker broker,
      final ObjectProvider<InputResponder> responders,
      final LeaderElection leader,
      final MeterRegistry meters,
      final Clock clock,
      final Duration interval,
      final Scheduler scheduler) {
    this.store = store;
    this.routes = routes;
    this.tokens = tokens;
    this.broker = broker;
    this.responders = responders;
    this.leader = leader;
    this.meters = meters;
    this.clock = clock;
    this.interval = interval;
    this.scheduler = scheduler;
  }

  /**
   * Tries every delivery that is due.
   *
   * @return completes when they have been tried
   */
  public Mono<Void> deliverDue() {
    return Flux.defer(() -> store.dueDeliveries(clock.instant()))
        .concatMap(
            delivery ->
                attempt(delivery)
                    .onErrorResume(
                        error -> {
                          log.atWarn()
                              .setCause(error)
                              .addKeyValue("deliveryId", delivery.id())
                              .log("Could not deliver an input request");
                          return Mono.empty();
                        }))
        .then();
  }

  private Mono<Void> attempt(final InputDelivery delivery) {
    return store
        .findById(delivery.requestId())
        // a request that ended or moved on needs no delivery
        .filter(request -> !request.state().isTerminal() && request.tier().equals(delivery.tier()))
        // send completes empty, so only a request that needs no delivery is marked done here
        .flatMap(request -> send(delivery, request).thenReturn(Boolean.TRUE))
        .switchIfEmpty(
            Mono.defer(() -> store.saveDelivery(delivery.sent()).thenReturn(Boolean.FALSE)))
        .then();
  }

  private Mono<Void> send(final InputDelivery delivery, final InputRequest request) {
    final String[] parts = delivery.responder().split(":", 3);
    return Mono.defer(() -> settings(delivery, request, parts.length > 2 ? parts[2] : null))
        .flatMap(settings -> responder(parts[1]).deliver(request, settings))
        .then(Mono.defer(() -> delivered(delivery, request)))
        .onErrorResume(error -> failed(delivery, request, error));
  }

  private InputResponder responder(final String type) {
    return responders.stream()
        .filter(r -> r.type().equals(type))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("No input responder of type '" + type + "'"));
  }

  /** The settings of the tier and the token that lets the receiver answer. */
  @SuppressWarnings("PMD.UseConcurrentHashMap")
  private Mono<Map<String, Object>> settings(
      final InputDelivery delivery, final InputRequest request, final String name) {
    return routes
        .resolve(request.spec(), request.sessionId())
        .map(
            route -> {
              final Map<String, Object> out = new LinkedHashMap<>();
              route.tiers().stream()
                  .filter(tier -> tier.name().equals(request.tier()))
                  .map(Tier::settings)
                  .findFirst()
                  .ifPresent(out::putAll);
              final Instant expires = expiry(request);
              out.put(KEY_RESPONDER, delivery.responder());
              out.put("name", name);
              out.put("answerExpires", expires.getEpochSecond());
              out.put("answerToken", tokens.mint(request.id(), request.tier(), expires));
              return out;
            });
  }

  private Instant expiry(final InputRequest request) {
    final Instant tier = request.tierDeadline();
    final Instant overall = request.deadline();
    if (tier != null) {
      return tier;
    }
    return overall == null ? clock.instant().plus(FALLBACK_TOKEN_LIFE) : overall;
  }

  private Mono<Void> delivered(final InputDelivery delivery, final InputRequest request) {
    meters.counter(METRIC, KEY_RESPONDER, delivery.responder(), "outcome", "sent").increment();
    broker.logDelivery(
        request,
        "INPUT_DELIVERED",
        Map.of(KEY_RESPONDER, delivery.responder(), "tier", request.tier()));
    return store.saveDelivery(delivery.sent());
  }

  private Mono<Void> failed(
      final InputDelivery delivery, final InputRequest request, final Throwable error) {
    final boolean giveUp = delivery.attempts() + 1 >= MAX_ATTEMPTS;
    final Duration wait = backoff(delivery.attempts());
    final String why = error.getClass().getSimpleName();
    meters
        .counter(
            METRIC, KEY_RESPONDER, delivery.responder(), "outcome", giveUp ? "failed" : "retry")
        .increment();
    if (giveUp) {
      broker.logDelivery(
          request,
          "INPUT_DELIVERY_FAILED",
          Map.of(KEY_RESPONDER, delivery.responder(), "tier", request.tier(), "error", why));
    }
    return store.saveDelivery(delivery.failed(why, clock.instant().plus(wait), giveUp));
  }

  /** One second, then twice as long each time, at most five minutes. */
  /* default */ static Duration backoff(final int attempts) {
    final Duration wait = FIRST_BACKOFF.multipliedBy(1L << Math.min(attempts, MAX_ATTEMPTS));
    return wait.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : wait;
  }

  @Override
  public void start() {
    lock.lock();
    try {
      if (timer.get() == null) {
        timer.set(
            Flux.interval(interval, scheduler)
                .filter(_ -> leader.isLeader())
                .concatMap(_ -> deliverDue())
                .subscribe());
      }
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void stop() {
    final Disposable running = timer.getAndSet(null);
    if (running != null) {
      running.dispose();
    }
  }

  @Override
  public boolean isRunning() {
    return timer.get() != null;
  }
}
