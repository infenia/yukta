// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.notify;

import com.infenia.yukta.config.YuktaProperties;
import com.infenia.yukta.plugin.input.InputIdentity;
import com.infenia.yukta.plugin.notify.NotificationGateway;
import com.infenia.yukta.plugin.notify.NotificationKind;
import com.infenia.yukta.plugin.notify.NotificationLimitException;
import com.infenia.yukta.plugin.notify.NotificationSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * The gateway a plugin takes through an {@code ObjectProvider<NotificationGateway>}. One singleton
 * serves every node: the session, workflow, run and node are read from the Reactor context at
 * subscription, so a plugin cannot raise a notification in the name of another run. The category is
 * {@code workflow}, the audience defaults to operators, and a key an author gives is scoped to the
 * run, so it can never reach another notification (such as {@code input:<id>}).
 *
 * <p>A run may raise {@link #MAX_PER_MINUTE} a minute and {@link #MAX_PER_EXECUTION} in all.
 */
@Component
public class DefaultNotificationGateway implements NotificationGateway {

  /** The category of every notification a workflow raises. */
  public static final String CATEGORY = "workflow";

  /** How long a run's counts are remembered after its last notification. */
  private static final Duration FORGET_AFTER = Duration.ofHours(1);

  /** Runs tracked before stale ones are dropped. */
  private static final int MAX_TRACKED = 10_000;

  private static final Duration WINDOW = Duration.ofMinutes(1);

  private static final List<String> OPERATORS = List.of("role:OPERATOR");

  private final NotificationService service;
  private final NotificationOutbox outbox;
  private final Map<String, Map<String, Map<String, Object>>> configured;
  private final Clock clock;

  /** What each run raised: the times of the last minute, and the total. */
  private final Map<String, Counts> runs = new ConcurrentHashMap<>();

  /**
   * Creates the gateway.
   *
   * @param service writes the notification
   * @param outbox queues the deliveries to channels
   * @param properties the {@code yukta.notifications} settings, for the configured channels
   */
  @Autowired
  public DefaultNotificationGateway(
      final NotificationService service,
      final NotificationOutbox outbox,
      final YuktaProperties properties) {
    this(service, outbox, properties, Clock.systemUTC());
  }

  /**
   * Creates the gateway with an explicit clock.
   *
   * @param service writes the notification
   * @param outbox queues the deliveries to channels
   * @param properties the {@code yukta.notifications} settings
   * @param clock the time of a notification
   */
  public DefaultNotificationGateway(
      final NotificationService service,
      final NotificationOutbox outbox,
      final YuktaProperties properties,
      final Clock clock) {
    this.service = service;
    this.outbox = outbox;
    this.configured = Map.copyOf(properties.notifications().channels());
    this.clock = clock;
  }

  @Override
  public Mono<Void> notify(final NotificationSpec spec) {
    return Mono.deferContextual(context -> {
      final InputIdentity who = InputIdentity.from(context);
      checkChannels(spec.channels());
      admit(who.executionId());
      final String key =
          "workflow:"
              + who.executionId()
              + ":"
              + (spec.key() == null ? who.nodeId() + ":" + UUID.randomUUID() : spec.key());
      return service
          .raise(
              NotificationDraft.builder()
                  .key(key)
                  .kind(NotificationKind.INFO)
                  .severity(spec.severity())
                  .category(CATEGORY)
                  .title(spec.title())
                  .body(spec.body())
                  .sessionId(who.sessionId())
                  .workflowId(who.workflowId())
                  .executionId(who.executionId())
                  .nodeId(who.nodeId())
                  .audience(spec.audience().isEmpty() ? OPERATORS : spec.audience())
                  .cooldown(Duration.ZERO)
                  .build())
          .flatMap(raised -> outbox.enqueueAll(raised, spec.channels()));
    });
  }

  /** A channel must be one the operator configured: {@code <type>:<name>}. */
  private void checkChannels(final List<String> channels) {
    for (final String channel : channels) {
      final int colon = channel.indexOf(':');
      final Map<String, Map<String, Object>> ofType =
          colon < 0 ? null : configured.getOrDefault(channel.substring(0, colon), Map.of());
      if (ofType == null || !ofType.containsKey(channel.substring(colon + 1))) {
        throw new IllegalArgumentException(
            "The channel '"
                + channel
                + "' is not configured; use one of the targets under"
                + " yukta.notifications.channels.<type>.<name>");
      }
    }
  }

  /** Counts a notification against the limits of its run, or refuses it. */
  private void admit(final String executionId) {
    final Instant now = clock.instant();
    if (runs.size() > MAX_TRACKED) {
      runs.values().removeIf(counts -> counts.idle(now));
    }
    runs.computeIfAbsent(executionId, _ -> new Counts()).admit(executionId, now);
  }

  /** The notifications of one run. */
  private static final class Counts {
    private final ReentrantLock lock = new ReentrantLock();
    private final Deque<Instant> recent = new ArrayDeque<>();
    private int total;
    private Instant last = Instant.EPOCH;

    /* default */ boolean idle(final Instant now) {
      lock.lock();
      try {
        return last.plus(FORGET_AFTER).isBefore(now);
      } finally {
        lock.unlock();
      }
    }

    /* default */ void admit(final String executionId, final Instant now) {
      lock.lock();
      try {
        while (!recent.isEmpty() && !recent.peekFirst().plus(WINDOW).isAfter(now)) {
          recent.removeFirst();
        }
        if (total >= MAX_PER_EXECUTION) {
          throw new NotificationLimitException(
              "Run "
                  + executionId
                  + " raised "
                  + MAX_PER_EXECUTION
                  + " notifications, the most a run may raise");
        }
        if (recent.size() >= MAX_PER_MINUTE) {
          throw new NotificationLimitException(
              "Run "
                  + executionId
                  + " raised "
                  + MAX_PER_MINUTE
                  + " notifications in a minute, the most a run may raise; wait or send fewer");
        }
        recent.addLast(now);
        total++;
        last = now;
      } finally {
        lock.unlock();
      }
    }
  }
}
