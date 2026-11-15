// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.notify;

import com.infenia.yukta.config.YuktaProperties;
import com.infenia.yukta.model.input.Tier;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.notify.ChannelContext;
import com.infenia.yukta.plugin.notify.Notification;
import com.infenia.yukta.plugin.notify.NotificationChannel;
import com.infenia.yukta.plugin.notify.NotificationKind;
import com.infenia.yukta.plugin.notify.NotificationSeverity;
import com.infenia.yukta.service.input.InputBroker;
import com.infenia.yukta.service.input.InputRequestStore;
import com.infenia.yukta.service.input.route.RouteResolver;
import com.infenia.yukta.service.input.token.AnswerTokens;
import com.infenia.yukta.service.leader.LeaderElection;
import com.infenia.yukta.service.leader.LeaderTimer;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Delivers notifications to their outbound channels, at least once: a failed delivery is tried
 * again after 1 s, then twice as long each time up to 5 minutes, and given up after 10 tries, which
 * raises a {@code delivery.failed} notification (never for a failed {@code delivery.failed}). It
 * runs only on the leader. A delivery that comes from a route tier carries a one-time answer token
 * for that tier; a delivery of a rule's channel never does. A question that ended or left the tier
 * needs no delivery.
 */
@Slf4j
@Component
@SuppressWarnings({"PMD.CouplingBetweenObjects", "PMD.ExcessiveImports", "PMD.TooManyMethods"})
public class NotificationDeliveryWorker implements SmartLifecycle {

  /** Most tries of one delivery. */
  public static final int MAX_ATTEMPTS = 10;

  /** First wait after a failure. */
  private static final Duration FIRST_BACKOFF = Duration.ofSeconds(1);

  /** Longest wait after a failure. */
  private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

  /** How long a token lives when the request has no deadline. */
  private static final Duration FALLBACK_TOKEN_LIFE = Duration.ofHours(1);

  /** Counter of deliveries by channel and outcome. */
  private static final String METRIC = "yukta.notifications.delivery";

  private static final String CHANNEL_TAG = "channel";
  private static final String TIER = "tier";
  private static final String DEFAULT_NAME = "default";

  private final NotificationStore store;
  private final InputRequestStore inputs;
  private final RouteResolver routes;
  private final AnswerTokens tokens;
  private final InputBroker broker;
  private final ObjectProvider<NotificationChannel> channels;
  private final NotificationService service;
  private final NotificationOutbox outbox;
  private final NotificationRules rules;
  private final YuktaProperties properties;
  private final MeterRegistry meters;
  private final Clock clock;

  /** Runs {@code deliverDue} on the leader at a fixed interval. */
  private final LeaderTimer timer;

  /**
   * Creates the worker.
   *
   * @param store where notifications and deliveries are kept
   * @param inputs the input requests
   * @param routes finds the settings of a tier
   * @param tokens makes the answer tokens
   * @param broker writes to the execution log
   * @param channels the channels
   * @param service raises {@code delivery.failed}
   * @param outbox queues the channels of a {@code delivery.failed} rule
   * @param rules decides who hears of a failed delivery
   * @param leader says whether this server leads
   * @param meters registry for the metrics
   * @param properties the settings
   */
  @Autowired
  @SuppressWarnings("PMD.ExcessiveParameterList")
  public NotificationDeliveryWorker(
      final NotificationStore store,
      final InputRequestStore inputs,
      final RouteResolver routes,
      final AnswerTokens tokens,
      final InputBroker broker,
      final ObjectProvider<NotificationChannel> channels,
      final NotificationService service,
      final NotificationOutbox outbox,
      final NotificationRules rules,
      final LeaderElection leader,
      final MeterRegistry meters,
      final YuktaProperties properties) {
    this(
        store,
        inputs,
        routes,
        tokens,
        broker,
        channels,
        service,
        outbox,
        rules,
        leader,
        meters,
        properties,
        Clock.systemUTC(),
        properties.input().sweepInterval(),
        Schedulers.parallel());
  }

  /**
   * Creates the worker with a clock and a scheduler.
   *
   * @param store where notifications and deliveries are kept
   * @param inputs the input requests
   * @param routes finds the settings of a tier
   * @param tokens makes the answer tokens
   * @param broker writes to the execution log
   * @param channels the channels
   * @param service raises {@code delivery.failed}
   * @param outbox queues the channels of a {@code delivery.failed} rule
   * @param rules decides who hears of a failed delivery
   * @param leader says whether this server leads
   * @param meters registry for the metrics
   * @param properties the settings
   * @param clock the time
   * @param interval how often due deliveries are looked for
   * @param scheduler runs the timer
   */
  @SuppressWarnings("PMD.ExcessiveParameterList")
  public NotificationDeliveryWorker(
      final NotificationStore store,
      final InputRequestStore inputs,
      final RouteResolver routes,
      final AnswerTokens tokens,
      final InputBroker broker,
      final ObjectProvider<NotificationChannel> channels,
      final NotificationService service,
      final NotificationOutbox outbox,
      final NotificationRules rules,
      final LeaderElection leader,
      final MeterRegistry meters,
      final YuktaProperties properties,
      final Clock clock,
      final Duration interval,
      final Scheduler scheduler) {
    this.store = store;
    this.inputs = inputs;
    this.routes = routes;
    this.tokens = tokens;
    this.broker = broker;
    this.channels = channels;
    this.service = service;
    this.outbox = outbox;
    this.rules = rules;
    this.properties = properties;
    this.meters = meters;
    this.clock = clock;
    this.timer = new LeaderTimer(leader, interval, scheduler, this::deliverDue);
  }

  /**
   * Tries every delivery that is due.
   *
   * @return completes when they have been tried
   */
  public Mono<Void> deliverDue() {
    return Flux.defer(() -> store.dueDeliveries(clock.instant()))
        .concatMap(delivery ->
            attempt(delivery)
                .onErrorResume(error -> {
                  log.atWarn()
                      .setCause(error)
                      .addKeyValue("deliveryId", delivery.id())
                      .log("Could not deliver a notification");
                  return Mono.empty();
                }))
        .then();
  }

  private Mono<Void> attempt(final NotificationDelivery delivery) {
    return store
        .findById(delivery.notificationId())
        .flatMap(notification -> withRequest(delivery, notification))
        // send completes empty, so only a delivery that needs no sending is marked done here: a
        // notification that is gone, or a question that ended or moved on
        .switchIfEmpty(
            Mono.defer(() -> store.saveDelivery(delivery.sent()).thenReturn(Boolean.FALSE)))
        .then();
  }

  /** Sends it, answering true; empty when the delivery is no longer needed. */
  private Mono<Boolean> withRequest(
      final NotificationDelivery delivery, final Notification notification) {
    if (notification.kind() == NotificationKind.INFO) {
      return send(delivery, notification, null).thenReturn(Boolean.TRUE);
    }
    final String requestId = notification.inputRequestId();
    final Mono<InputRequest> request =
        requestId == null ? Mono.empty() : inputs.findById(requestId);
    return request
        .filter(r -> !r.state().isTerminal())
        .filter(r -> delivery.tier() == null || r.tier().equals(delivery.tier()))
        .flatMap(r -> send(delivery, notification, r).thenReturn(Boolean.TRUE));
  }

  private Mono<Void> send(
      final NotificationDelivery delivery,
      final Notification notification,
      final @Nullable InputRequest request) {
    final String[] parts = delivery.channel().split(":", 2);
    final String name = parts.length > 1 ? parts[1] : DEFAULT_NAME;
    return Mono.defer(() -> context(delivery, request, name))
        .flatMap(context -> channel(parts[0]).deliver(notification, context))
        .then(Mono.defer(() -> delivered(delivery, request)))
        .onErrorResume(error -> failed(delivery, notification, request, error));
  }

  private NotificationChannel channel(final String type) {
    return channels.stream()
        .filter(c -> c.type().equals(type))
        .findFirst()
        .orElseThrow(
            () -> new IllegalStateException("No notification channel of type '" + type + "'"));
  }

  /** The settings of the target and, for a tier delivery, those of the tier and the token. */
  @SuppressWarnings("PMD.UseConcurrentHashMap")
  private Mono<ChannelContext> context(
      final NotificationDelivery delivery,
      final @Nullable InputRequest request,
      final String name) {
    final Map<String, Object> settings = new LinkedHashMap<>(configured(delivery.channel()));
    final String tierName = delivery.tier();
    if (tierName == null || request == null) {
      return Mono.just(new ChannelContext(name, settings, request, null, null));
    }
    return routes
        .resolve(request.spec(), request.sessionId())
        .map(route -> {
          route.tiers().stream()
              .filter(tier -> tier.name().equals(tierName))
              .map(Tier::settings)
              .findFirst()
              .ifPresent(settings::putAll);
          final Instant expires = expiry(request);
          return new ChannelContext(
              name, settings, request, tokens.mint(request.id(), tierName, expires), expires);
        });
  }

  /** The settings under {@code yukta.notifications.channels.<type>.<name>}. */
  private Map<String, Object> configured(final String channel) {
    final String[] parts = channel.split(":", 2);
    final Map<String, Map<String, Object>> ofType =
        properties.notifications().channels().getOrDefault(parts[0], Map.of());
    return ofType.getOrDefault(parts.length > 1 ? parts[1] : DEFAULT_NAME, Map.of());
  }

  private Instant expiry(final InputRequest request) {
    final Instant tier = request.tierDeadline();
    final Instant overall = request.deadline();
    if (tier != null) {
      return tier;
    }
    return overall == null ? clock.instant().plus(FALLBACK_TOKEN_LIFE) : overall;
  }

  private Mono<Void> delivered(
      final NotificationDelivery delivery, final @Nullable InputRequest request) {
    meters.counter(METRIC, CHANNEL_TAG, delivery.channel(), "outcome", "sent").increment();
    if (request != null) {
      broker.logDelivery(
          request,
          "INPUT_DELIVERED",
          Map.of(CHANNEL_TAG, delivery.channel(), TIER, request.tier()));
    }
    return store.saveDelivery(delivery.sent());
  }

  private Mono<Void> failed(
      final NotificationDelivery delivery,
      final Notification notification,
      final @Nullable InputRequest request,
      final Throwable error) {
    final boolean giveUp = delivery.attempts() + 1 >= MAX_ATTEMPTS;
    final String why = error.getClass().getSimpleName();
    meters
        .counter(METRIC, CHANNEL_TAG, delivery.channel(), "outcome", giveUp ? "failed" : "retry")
        .increment();
    final Mono<Void> save =
        store.saveDelivery(
            delivery.failed(why, clock.instant().plus(backoff(delivery.attempts())), giveUp));
    if (!giveUp) {
      return save;
    }
    if (request != null) {
      broker.logDelivery(
          request,
          "INPUT_DELIVERY_FAILED",
          Map.of(CHANNEL_TAG, delivery.channel(), TIER, request.tier(), "error", why));
    }
    return save.then(Mono.defer(() -> deliveryFailed(delivery, notification, why)));
  }

  /** Tells the people the rules name, once per delivery given up; never for its own kind. */
  private Mono<Void> deliveryFailed(
      final NotificationDelivery delivery, final Notification notification, final String why) {
    if (NotificationRules.DELIVERY_FAILED.equals(notification.category())) {
      return Mono.empty();
    }
    return Mono.justOrEmpty(
            rules.forRun(
                NotificationRules.DELIVERY_FAILED,
                notification.sessionId(),
                notification.workflowId()))
        .flatMap(applied ->
            service
                .raise(
                    NotificationDraft.builder()
                        .key(NotificationRules.DELIVERY_FAILED + ":" + delivery.id())
                        .kind(NotificationKind.INFO)
                        .severity(
                            applied.severity() == null
                                ? NotificationSeverity.WARN
                                : applied.severity())
                        .category(NotificationRules.DELIVERY_FAILED)
                        .title("Delivery failed: " + delivery.channel())
                        .body(
                            "Could not deliver \""
                                + notification.title()
                                + "\" after "
                                + MAX_ATTEMPTS
                                + " tries ("
                                + why
                                + ").")
                        .sessionId(notification.sessionId())
                        .workflowId(notification.workflowId())
                        .executionId(notification.executionId())
                        .audience(applied.audience())
                        .cooldown(applied.cooldown())
                        .build())
                .flatMap(raised -> outbox.enqueueAll(raised, applied.channels())));
  }

  /** One second, then twice as long each time, at most five minutes. */
  /* default */ static Duration backoff(final int attempts) {
    final Duration wait = FIRST_BACKOFF.multipliedBy(1L << Math.min(attempts, MAX_ATTEMPTS));
    return wait.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : wait;
  }

  @Override
  public void start() {
    timer.start();
  }

  @Override
  public void stop() {
    timer.stop();
  }

  @Override
  public boolean isRunning() {
    return timer.isRunning();
  }
}
