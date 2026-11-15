// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.config.YuktaProperties;
import com.infenia.yukta.model.input.FinalAction;
import com.infenia.yukta.model.input.InputRoute;
import com.infenia.yukta.model.input.Mode;
import com.infenia.yukta.model.input.ResponderType;
import com.infenia.yukta.model.input.Tier;
import com.infenia.yukta.model.notify.NotificationRuleSettings;
import com.infenia.yukta.plugin.input.Audience;
import com.infenia.yukta.plugin.input.InputKind;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.input.InputRequestSpec;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.plugin.input.InputState;
import com.infenia.yukta.plugin.input.TimeoutAction;
import com.infenia.yukta.plugin.notify.ChannelContext;
import com.infenia.yukta.plugin.notify.Notification;
import com.infenia.yukta.plugin.notify.NotificationChannel;
import com.infenia.yukta.plugin.notify.NotificationKind;
import com.infenia.yukta.service.input.InputBroker;
import com.infenia.yukta.service.input.InputRequestStore;
import com.infenia.yukta.service.input.InputTestSupport.MutableClock;
import com.infenia.yukta.service.input.route.RouteResolver;
import com.infenia.yukta.service.input.token.AnswerTokens;
import com.infenia.yukta.service.redact.InputRedactor;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;
import reactor.test.scheduler.VirtualTimeScheduler;

/** The outbound delivery: tokens only for tiers, retries, give-up and {@code delivery.failed}. */
@NoArgsConstructor
@SuppressFBWarnings(
    value = "NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE",
    justification = "block() of a store call that always emits")
@SuppressWarnings({
  "PMD.TooManyMethods",
  "PMD.CouplingBetweenObjects",
  "PMD.ExcessiveImports",
  "PMD.TooManyStaticImports",
  "PMD.AvoidDuplicateLiterals"
})
class NotificationDeliveryWorkerTest {

  private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
  private static final Duration INTERVAL = Duration.ofSeconds(1);
  private static final String HOOK = "webhook:ops";

  private final MutableClock clock = new MutableClock();
  private final InMemoryNotificationStore store = new InMemoryNotificationStore(100);
  private final NotificationService service =
      new NotificationService(store, new InputRedactor(List.of()), clock);
  private final NotificationOutbox outbox = new NotificationOutbox(store, clock);
  private final InputRequestStore inputs = mock(InputRequestStore.class);
  private final RouteResolver routes = mock(RouteResolver.class);
  private final InputBroker broker = mock(InputBroker.class);
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final AnswerTokens tokens =
      new AnswerTokens("worker-secret-worker-secret-worker-secret", clock);
  private final List<ChannelContext> contexts = new CopyOnWriteArrayList<>();
  private final List<Notification> delivered = new CopyOnWriteArrayList<>();
  private final AtomicBoolean fail = new AtomicBoolean();
  private final AtomicBoolean leading = new AtomicBoolean(true);
  private final VirtualTimeScheduler scheduler = VirtualTimeScheduler.create();
  private final List<NotificationChannel> channels = new ArrayList<>();
  private NotificationDeliveryWorker worker = newWorker(Map.of());

  @AfterEach
  void tearDown() {
    worker.stop();
  }

  @SuppressWarnings("unchecked")
  private NotificationDeliveryWorker newWorker(final Map<String, NotificationRuleSettings> rules) {
    channels.clear();
    channels.add(
        new NotificationChannel() {
          @Override
          public String type() {
            return "webhook";
          }

          @Override
          public Mono<Void> deliver(final Notification n, final ChannelContext context) {
            contexts.add(context);
            delivered.add(n);
            return fail.get() ? Mono.error(new IllegalStateException("down")) : Mono.empty();
          }
        });
    final ObjectProvider<NotificationChannel> provider = mock(ObjectProvider.class);
    when(provider.stream()).thenAnswer(_ -> channels.stream());
    final Tier tier =
        new Tier(
            "hook",
            List.of("external:webhook:ops"),
            Duration.ofMinutes(10),
            Mode.FIRST,
            0,
            0,
            ResponderType.HUMAN,
            Map.of("x", 1));
    when(routes.resolve(any(), anyString()))
        .thenReturn(Mono.just(new InputRoute("ext", List.of(tier), FinalAction.FAIL)));
    final YuktaProperties properties =
        new YuktaProperties(
            null,
            null,
            null,
            null,
            null,
            null,
            new YuktaProperties.Notifications(
                true,
                Duration.ofDays(7),
                100,
                Duration.ofSeconds(2),
                rules,
                Map.of(
                    "webhook",
                    Map.of("ops", Map.<String, Object>of("url", "https://h.example/x")))),
            null,
            null,
            null,
            null,
            null,
            null);
    return new NotificationDeliveryWorker(
        store,
        inputs,
        routes,
        tokens,
        broker,
        provider,
        service,
        outbox,
        new NotificationRules(properties),
        leading::get,
        meters,
        properties,
        clock,
        INTERVAL,
        scheduler);
  }

  private static InputRequest request(final InputState state, final String tier) {
    final InputRequestSpec spec =
        new InputRequestSpec(
            "k",
            InputKind.APPROVAL,
            "Deploy?",
            Map.of(),
            List.of(),
            null,
            InputRisk.LOW,
            Audience.CURRENT_TIER,
            Duration.ofHours(2),
            TimeoutAction.FAIL,
            null,
            "ext",
            "TEST");
    return new InputRequest(
        "r1",
        "k",
        "s",
        "w",
        "e1",
        "n",
        spec,
        state,
        0,
        tier,
        List.of("external:webhook:ops"),
        null,
        NOW.plusSeconds(7200),
        NOW.plusSeconds(600),
        List.of(),
        NOW,
        NOW,
        List.of());
  }

  private Notification action() {
    return service
        .raise(
            NotificationDraft.builder()
                .key("input:r1")
                .kind(NotificationKind.ACTION)
                .severity(com.infenia.yukta.plugin.notify.NotificationSeverity.WARN)
                .category("input")
                .title("Deploy?")
                .sessionId("s")
                .workflowId("w")
                .executionId("e1")
                .inputRequestId("r1")
                .audience(List.of())
                .cooldown(Duration.ZERO)
                .build())
        .block();
  }

  private Notification info(final String category) {
    return service
        .raise(
            NotificationDraft.builder()
                .key(category + ":x")
                .kind(NotificationKind.INFO)
                .severity(com.infenia.yukta.plugin.notify.NotificationSeverity.ERROR)
                .category(category)
                .title("Run failed")
                .sessionId("s")
                .audience(List.of("role:OPERATOR"))
                .cooldown(Duration.ZERO)
                .build())
        .block();
  }

  private List<NotificationDelivery> due() {
    return store.dueDeliveries(clock.instant().plus(Duration.ofDays(1))).collectList().block();
  }

  // ---- tokens ----

  @Test
  void tierDeliveryCarriesTheTierSettingsAndOneTimeToken() {
    final Notification n = action();
    final InputRequest request = request(InputState.OPEN, "hook");
    when(inputs.findById("r1")).thenReturn(Mono.just(request));
    outbox.enqueueTier(n, request).block();

    worker.deliverDue().block();

    assertThat(contexts)
        .singleElement()
        .satisfies(c -> {
          assertThat(c.name()).isEqualTo("ops");
          assertThat(c.settings())
              .containsEntry("x", 1)
              .containsEntry("url", "https://h.example/x");
          assertThat(c.request()).isEqualTo(request);
          assertThat(c.answerExpires()).isEqualTo(NOW.plusSeconds(600));
          assertThat(
                  tokens.verify("r1", "hook", c.answerExpires().getEpochSecond(), c.answerToken()))
              .isTrue();
        });
    assertThat(due()).isEmpty();
    verify(broker).logDelivery(eq(request), eq("INPUT_DELIVERED"), any());
    assertThat(
            meters
                .counter("yukta.notifications.delivery", "channel", HOOK, "outcome", "sent")
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void tokenLivesUntilTheRequestDeadlineOrAnHourWhenThereIsNone() {
    final Notification n = action();
    final InputRequest base = request(InputState.OPEN, "hook");
    final InputRequest noTier =
        new InputRequest(
            "r1",
            "k",
            "s",
            "w",
            "e1",
            "n",
            base.spec(),
            InputState.OPEN,
            0,
            "hook",
            List.of("external:webhook:ops"),
            null,
            NOW.plusSeconds(7200),
            null,
            List.of(),
            NOW,
            NOW,
            List.of());
    final InputRequest bare =
        new InputRequest(
            "r1",
            "k",
            "s",
            "w",
            "e1",
            "n",
            base.spec(),
            InputState.OPEN,
            0,
            "hook",
            List.of("external:webhook:ops"),
            null,
            null,
            null,
            List.of(),
            NOW,
            NOW,
            List.of());
    when(inputs.findById("r1")).thenReturn(Mono.just(noTier), Mono.just(bare));

    outbox.enqueueTier(n, noTier).block();
    worker.deliverDue().block();
    store
        .enqueueDelivery(NotificationDelivery.pending(n.id(), "webhook:other", "hook", NOW))
        .block();
    worker.deliverDue().block();

    assertThat(contexts.get(0).answerExpires()).isEqualTo(NOW.plusSeconds(7200));
    assertThat(contexts.get(1).answerExpires())
        .isEqualTo(clock.instant().plus(Duration.ofHours(1)));
  }

  @Test
  void rulesChannelNeverGetsTokenNotEvenForAnAction() {
    final Notification n = action();
    when(inputs.findById("r1")).thenReturn(Mono.just(request(InputState.OPEN, "hook")));
    outbox.enqueueAll(n, List.of(HOOK)).block();
    outbox.enqueueAll(info("run.failed"), List.of(HOOK)).block();

    worker.deliverDue().block();

    assertThat(contexts).hasSize(2);
    assertThat(contexts)
        .allSatisfy(c -> {
          assertThat(c.answerToken()).isNull();
          assertThat(c.answerExpires()).isNull();
          assertThat(c.settings()).doesNotContainKey("x");
        });
    assertThat(contexts.stream().filter(c -> c.request() != null)).hasSize(1);
  }

  @Test
  void channelWithoutNameUsesTheDefaultTarget() {
    final Notification n = info("run.failed");
    store.enqueueDelivery(NotificationDelivery.pending(n.id(), "webhook", null, NOW)).block();

    worker.deliverDue().block();

    assertThat(contexts.getFirst().name()).isEqualTo("default");
    assertThat(contexts.getFirst().settings()).isEmpty();
  }

  // ---- not needed any more ----

  @Test
  void questionThatEndedOrLeftTheTierOrIsGoneNeedsNoDelivery() {
    final Notification n = action();
    outbox.enqueueTier(n, request(InputState.OPEN, "hook")).block();
    when(inputs.findById("r1"))
        .thenReturn(
            Mono.just(request(InputState.ANSWERED, "hook")),
            Mono.just(request(InputState.OPEN, "next")),
            Mono.empty());

    worker.deliverDue().block();
    assertThat(due()).isEmpty();
    store.enqueueDelivery(NotificationDelivery.pending(n.id(), "webhook:b", "hook", NOW)).block();
    worker.deliverDue().block();
    store.enqueueDelivery(NotificationDelivery.pending(n.id(), "webhook:c", "hook", NOW)).block();
    worker.deliverDue().block();

    assertThat(contexts).isEmpty();
    assertThat(due()).isEmpty();
  }

  @Test
  void notificationThatIsGoneIsDropped() {
    store.enqueueDelivery(NotificationDelivery.pending("missing", HOOK, null, NOW)).block();

    worker.deliverDue().block();

    assertThat(contexts).isEmpty();
    assertThat(due()).isEmpty();
  }

  @Test
  void actionWithoutRequestIdIsNotSent() {
    final Notification n = info("input");
    final Notification noRequest =
        service
            .raise(
                NotificationDraft.builder()
                    .key("input:odd")
                    .kind(NotificationKind.ACTION)
                    .severity(n.severity())
                    .category("input")
                    .title("odd")
                    .audience(List.of())
                    .cooldown(Duration.ZERO)
                    .build())
            .block();
    store.enqueueDelivery(NotificationDelivery.pending(noRequest.id(), HOOK, null, NOW)).block();

    worker.deliverDue().block();

    assertThat(contexts).isEmpty();
  }

  // ---- failure ----

  @Test
  void failedDeliveryIsTriedAgainWithBackoff() {
    final Notification n = info("run.failed");
    outbox.enqueueAll(n, List.of(HOOK)).block();
    fail.set(true);

    worker.deliverDue().block();

    assertThat(due())
        .singleElement()
        .satisfies(d -> {
          assertThat(d.attempts()).isEqualTo(1);
          assertThat(d.lastError()).isEqualTo("IllegalStateException");
          assertThat(d.nextAttempt()).isEqualTo(clock.instant().plusSeconds(1));
        });
    assertThat(
            meters
                .counter("yukta.notifications.delivery", "channel", HOOK, "outcome", "retry")
                .count())
        .isEqualTo(1.0);
    fail.set(false);
    clock.advance(Duration.ofSeconds(2));
    worker.deliverDue().block();
    assertThat(due()).isEmpty();
  }

  @Test
  void backoffDoublesUpToFiveMinutes() {
    assertThat(NotificationDeliveryWorker.backoff(0)).isEqualTo(Duration.ofSeconds(1));
    assertThat(NotificationDeliveryWorker.backoff(3)).isEqualTo(Duration.ofSeconds(8));
    assertThat(NotificationDeliveryWorker.backoff(9)).isEqualTo(Duration.ofMinutes(5));
    assertThat(NotificationDeliveryWorker.backoff(50)).isEqualTo(Duration.ofMinutes(5));
  }

  private void failUntilGivenUp(final Notification n, final String channel, final String tier) {
    store.enqueueDelivery(NotificationDelivery.pending(n.id(), channel, tier, NOW)).block();
    fail.set(true);
    for (int i = 0; i < NotificationDeliveryWorker.MAX_ATTEMPTS; i++) {
      clock.advance(Duration.ofMinutes(6));
      worker.deliverDue().block();
    }
  }

  @Test
  void givingUpRaisesDeliveryFailedOnceForAdministratorsAndLogsForAnAction() {
    final Notification n = action();
    final InputRequest request = request(InputState.OPEN, "hook");
    when(inputs.findById("r1")).thenReturn(Mono.just(request));

    failUntilGivenUp(n, HOOK, "hook");
    clock.advance(Duration.ofMinutes(6));
    worker.deliverDue().block();

    assertThat(
            store
                .find(NotificationQuery.ALL)
                .filter(x -> "delivery.failed".equals(x.category()))
                .collectList()
                .block())
        .singleElement()
        .satisfies(x -> {
          assertThat(x.audience()).containsExactly("role:ADMIN");
          assertThat(x.title()).isEqualTo("Delivery failed: webhook:ops");
          assertThat(x.sessionId()).isEqualTo("s");
        });
    verify(broker).logDelivery(eq(request), eq("INPUT_DELIVERY_FAILED"), any());
    assertThat(
            meters
                .counter("yukta.notifications.delivery", "channel", HOOK, "outcome", "failed")
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void failedDeliveryFailedDeliveryRaisesNothingMore() {
    final Notification alert = info("delivery.failed");

    failUntilGivenUp(alert, HOOK, null);

    assertThat(store.find(NotificationQuery.ALL).count().block()).isEqualTo(1);
    verify(broker, never()).logDelivery(any(), anyString(), any());
  }

  @Test
  void deliveryFailedRuleCanAddChannelsAndTurnsTheDefaultOffWhenItDoesNotMatch() {
    worker.stop();
    worker =
        newWorker(
            Map.of(
                "failed",
                new NotificationRuleSettings(
                    "delivery.failed",
                    List.of(),
                    List.of(),
                    List.of("key:arun"),
                    com.infenia.yukta.plugin.notify.NotificationSeverity.ERROR,
                    List.of(HOOK),
                    Duration.ZERO),
                "other",
                new NotificationRuleSettings(
                    "run.failed",
                    List.of(),
                    List.of(),
                    List.of(),
                    null,
                    List.of(),
                    Duration.ZERO)));
    final Notification n = info("run.failed");

    failUntilGivenUp(n, HOOK, null);

    final Notification raised =
        store
            .find(NotificationQuery.ALL)
            .filter(x -> "delivery.failed".equals(x.category()))
            .blockFirst();
    assertThat(raised.audience()).containsExactly("key:arun");
    assertThat(raised.severity())
        .isEqualTo(com.infenia.yukta.plugin.notify.NotificationSeverity.ERROR);
    assertThat(due())
        .singleElement()
        .satisfies(d -> assertThat(d.notificationId()).isEqualTo(raised.id()));
  }

  @Test
  void unknownChannelTypeIsFailureNotCrash() {
    final Notification n = info("run.failed");
    store.enqueueDelivery(NotificationDelivery.pending(n.id(), "slack:team", null, NOW)).block();

    worker.deliverDue().block();

    assertThat(due()).singleElement().satisfies(d -> assertThat(d.attempts()).isEqualTo(1));
  }

  @Test
  void storeThatFailsIsLoggedNotRaised() {
    final Notification n = info("run.failed");
    outbox.enqueueAll(n, List.of(HOOK)).block();
    when(inputs.findById(anyString())).thenReturn(Mono.error(new IllegalStateException("db")));
    final Notification a = action();
    outbox.enqueueAll(a, List.of(HOOK)).block();

    worker.deliverDue().block();

    assertThat(contexts).hasSize(1);
  }

  // ---- timer ----

  @Test
  void theTimerDeliversOnTheLeaderOnly() {
    final Notification n = info("run.failed");
    outbox.enqueueAll(n, List.of(HOOK)).block();
    leading.set(false);
    worker.start();
    assertThat(worker.isRunning()).isTrue();

    scheduler.advanceTimeBy(Duration.ofSeconds(3));
    assertThat(contexts).isEmpty();
    leading.set(true);
    scheduler.advanceTimeBy(Duration.ofSeconds(3));

    assertThat(contexts).hasSize(1);
    worker.stop();
    assertThat(worker.isRunning()).isFalse();
  }

  @Test
  void theSpringConstructorReadsTheSweepInterval() {
    final YuktaProperties properties =
        new YuktaProperties(
            null,
            null,
            null,
            null,
            null,
            new YuktaProperties.Input(
                Duration.ofHours(1),
                Duration.ofHours(2),
                5,
                50,
                10,
                Duration.ofSeconds(1),
                Duration.ofDays(1),
                List.of(),
                Map.of()),
            new YuktaProperties.Notifications(
                true, Duration.ofDays(1), 10, Duration.ZERO, Map.of(), Map.of()),
            null,
            null,
            null,
            null,
            null,
            null);
    @SuppressWarnings("unchecked")
    final ObjectProvider<NotificationChannel> provider = mock(ObjectProvider.class);

    final NotificationDeliveryWorker spring =
        new NotificationDeliveryWorker(
            store,
            inputs,
            routes,
            tokens,
            broker,
            provider,
            service,
            outbox,
            new NotificationRules(properties),
            leading::get,
            meters,
            properties);

    assertThat(spring.isRunning()).isFalse();
  }

  @Test
  void infoNotificationOnTierDeliveryGetsNoTokenBecauseThereIsNoQuestion() {
    final Notification n = info("run.failed");
    store.enqueueDelivery(NotificationDelivery.pending(n.id(), HOOK, "hook", NOW)).block();

    worker.deliverDue().block();

    assertThat(contexts).singleElement().satisfies(c -> assertThat(c.answerToken()).isNull());
  }
}
