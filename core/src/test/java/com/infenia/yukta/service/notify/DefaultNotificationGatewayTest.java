// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.notify;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.config.YuktaProperties;
import com.infenia.yukta.plugin.notify.Notification;
import com.infenia.yukta.plugin.notify.NotificationGateway;
import com.infenia.yukta.plugin.notify.NotificationKind;
import com.infenia.yukta.plugin.notify.NotificationLimitException;
import com.infenia.yukta.plugin.notify.NotificationSeverity;
import com.infenia.yukta.plugin.notify.NotificationSpec;
import com.infenia.yukta.service.input.InputTestSupport.MutableClock;
import com.infenia.yukta.service.redact.InputRedactor;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@NoArgsConstructor
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.TooManyMethods"})
class DefaultNotificationGatewayTest {

  private final MutableClock clock = new MutableClock();
  private final InMemoryNotificationStore store = new InMemoryNotificationStore(100_000);
  private final NotificationService service =
      new NotificationService(store, new InputRedactor(List.of()), clock);
  private final NotificationOutbox outbox = new NotificationOutbox(store, clock);
  private final DefaultNotificationGateway gateway =
      new DefaultNotificationGateway(service, outbox, properties(), clock);

  private static YuktaProperties properties() {
    return new YuktaProperties(
        null,
        null,
        null,
        null,
        null,
        null,
        new YuktaProperties.Notifications(
            true,
            Duration.ofDays(7),
            1000,
            Duration.ofSeconds(2),
            Map.of(),
            Map.of("webhook", Map.of("ops", Map.<String, Object>of("url", "https://h.example/x")))),
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private static NotificationSpec spec(final String key, final String... channels) {
    return new NotificationSpec(
        "Build done", "all green", NotificationSeverity.WARN, List.of(), key, List.of(channels));
  }

  private static <T> Mono<T> inRun(final String execution, final String node, final Mono<T> call) {
    return call.contextWrite(ctx ->
        ctx.put("sessionId", "s1")
            .put("workflowId", "wf")
            .put("executionId", execution)
            .put("nodeId", node));
  }

  private List<Notification> items() {
    return store.find(NotificationQuery.ALL).collectList().block();
  }

  @Test
  void theNotificationBelongsToTheNodeThatRaisedItAndItsAudienceDefaultsToOperators() {
    StepVerifier.create(inRun("e1", "n1", gateway.notify(spec(null)))).verifyComplete();

    assertThat(items())
        .singleElement()
        .satisfies(n -> {
          assertThat(n.category()).isEqualTo("workflow");
          assertThat(n.kind()).isEqualTo(NotificationKind.INFO);
          assertThat(n.severity()).isEqualTo(NotificationSeverity.WARN);
          assertThat(n.sessionId()).isEqualTo("s1");
          assertThat(n.workflowId()).isEqualTo("wf");
          assertThat(n.executionId()).isEqualTo("e1");
          assertThat(n.nodeId()).isEqualTo("n1");
          assertThat(n.audience()).containsExactly("role:OPERATOR");
          assertThat(n.key()).startsWith("workflow:e1:n1:");
          assertThat(n.body()).isEqualTo("all green");
        });
  }

  @Test
  void anAuthorsKeyIsScopedToTheRunSoItCannotReachAnotherNotification() {
    service.raise(NotifyTestSupport.action("input:r1").build()).block();

    StepVerifier.create(inRun("e1", "n1", gateway.notify(spec("input:r1")))).verifyComplete();
    StepVerifier.create(inRun("e1", "n2", gateway.notify(spec("input:r1")))).verifyComplete();
    StepVerifier.create(inRun("e2", "n1", gateway.notify(spec("input:r1")))).verifyComplete();

    assertThat(items())
        .extracting(Notification::key)
        .containsExactlyInAnyOrder("input:r1", "workflow:e1:input:r1", "workflow:e2:input:r1");
    assertThat(
            items().stream()
                .filter(n -> "input:r1".equals(n.key()))
                .findFirst()
                .orElseThrow()
                .kind())
        .isEqualTo(NotificationKind.ACTION);
  }

  @Test
  void anAudienceTheAuthorNamesIsKept() {
    final NotificationSpec admins =
        new NotificationSpec(
            "t", null, NotificationSeverity.INFO, List.of("role:ADMIN"), null, List.of());

    StepVerifier.create(inRun("e1", "n1", gateway.notify(admins))).verifyComplete();

    assertThat(items().getFirst().audience()).containsExactly("role:ADMIN");
  }

  @Test
  void channelMustBeConfiguredAndIsQueuedWithoutToken() {
    StepVerifier.create(inRun("e1", "n1", gateway.notify(spec(null, "webhook:ops"))))
        .verifyComplete();
    for (final String bad : List.of("webhook:nope", "slack:team", "nocolon")) {
      StepVerifier.create(inRun("e1", "n1", gateway.notify(spec(null, bad))))
          .expectError(IllegalArgumentException.class)
          .verify();
    }

    assertThat(store.dueDeliveries(Instant.now().plus(Duration.ofDays(1))).collectList().block())
        .singleElement()
        .satisfies(d -> {
          assertThat(d.channel()).isEqualTo("webhook:ops");
          assertThat(d.tier()).isNull();
        });
    assertThat(items()).hasSize(1);
  }

  @Test
  void runMayRaiseTwentyPerMinuteAndTheNamedLimitHolds() {
    for (int i = 0; i < NotificationGateway.MAX_PER_MINUTE; i++) {
      StepVerifier.create(inRun("e1", "n1", gateway.notify(spec(null)))).verifyComplete();
    }

    StepVerifier.create(inRun("e1", "n1", gateway.notify(spec(null))))
        .expectErrorSatisfies(e ->
            assertThat(e)
                .isInstanceOf(NotificationLimitException.class)
                .hasMessageContaining("20 notifications in a minute")
                .hasMessageContaining("e1"))
        .verify();
    StepVerifier.create(inRun("e2", "n1", gateway.notify(spec(null)))).verifyComplete();
    clock.advance(Duration.ofSeconds(61));
    StepVerifier.create(inRun("e1", "n1", gateway.notify(spec(null)))).verifyComplete();
  }

  @Test
  void runMayRaiseTwoHundredInAll() {
    for (int i = 0; i < NotificationGateway.MAX_PER_EXECUTION; i++) {
      StepVerifier.create(inRun("e1", "n1", gateway.notify(spec(null)))).verifyComplete();
      clock.advance(Duration.ofSeconds(4));
    }

    StepVerifier.create(inRun("e1", "n1", gateway.notify(spec(null))))
        .expectErrorSatisfies(e ->
            assertThat(e)
                .isInstanceOf(NotificationLimitException.class)
                .hasMessageContaining("200 notifications"))
        .verify();
  }

  @Test
  void staleRunsAreForgottenWhenManyAreTracked() {
    for (int i = 0; i < 10_002; i++) {
      StepVerifier.create(inRun("run-" + i, "n", gateway.notify(spec(null)))).verifyComplete();
    }
    clock.advance(Duration.ofHours(2));

    StepVerifier.create(inRun("fresh", "n", gateway.notify(spec(null)))).verifyComplete();
  }

  @Test
  void outsideNodeThereIsNoIdentityAndSoNoNotification() {
    StepVerifier.create(gateway.notify(spec(null)))
        .expectError(IllegalStateException.class)
        .verify();
  }

  @Test
  void theSpringConstructorUsesTheSystemClock() {
    final DefaultNotificationGateway spring =
        new DefaultNotificationGateway(service, outbox, properties());

    StepVerifier.create(inRun("e9", "n", spring.notify(spec(null)))).verifyComplete();
  }
}
