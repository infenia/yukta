// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.notify;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.input.Audience;
import com.infenia.yukta.plugin.input.InputKind;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.input.InputRequestSpec;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.plugin.input.InputState;
import com.infenia.yukta.plugin.input.TimeoutAction;
import com.infenia.yukta.plugin.notify.Notification;
import com.infenia.yukta.service.redact.InputRedactor;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@NoArgsConstructor
class NotificationOutboxTest {

  private static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");

  private final InMemoryNotificationStore store = new InMemoryNotificationStore(10);
  private final NotificationService service =
      new NotificationService(store, new InputRedactor(List.of()));
  private final NotificationOutbox outbox = new NotificationOutbox(store);

  private static InputRequest request(final String... targets) {
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
            Duration.ofMinutes(5),
            TimeoutAction.FAIL,
            null,
            null,
            "T");
    return new InputRequest(
        "r1",
        "k",
        "s",
        "w",
        "e",
        "n",
        spec,
        InputState.OPEN,
        0,
        "hook",
        List.of(targets),
        null,
        NOW,
        NOW,
        List.of(),
        NOW,
        NOW,
        List.of());
  }

  private Notification notification() {
    return service.raise(NotifyTestSupport.action("input:r1").build()).block();
  }

  @Test
  void onlyTheChannelsOfTierAreQueuedAndTwiceChangesNothing() {
    final Notification n = notification();
    final InputRequest hook = request("role:OPERATOR", "external:webhook:ops", "external:chat");

    outbox.enqueueTier(n, hook).block();
    outbox.enqueueTier(n, hook).block();

    assertThat(store.dueDeliveries(Instant.now().plus(Duration.ofDays(1))).collectList().block())
        .extracting(NotificationDelivery::channel, NotificationDelivery::tier)
        .containsExactlyInAnyOrder(
            org.assertj.core.api.Assertions.tuple("webhook:ops", "hook"),
            org.assertj.core.api.Assertions.tuple("chat", "hook"));
    assertThat(NotificationOutbox.isExternal("external:x")).isTrue();
    assertThat(NotificationOutbox.isExternal("role:OPERATOR")).isFalse();
  }
}
