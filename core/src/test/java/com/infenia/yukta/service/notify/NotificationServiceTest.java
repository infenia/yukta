// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.notify;

import static com.infenia.yukta.service.notify.NotifyTestSupport.action;
import static com.infenia.yukta.service.notify.NotifyTestSupport.info;
import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.notify.Notification;
import com.infenia.yukta.service.input.InputTestSupport.MutableClock;
import com.infenia.yukta.service.redact.InputRedactor;
import java.time.Duration;
import java.util.List;
import java.util.NoSuchElementException;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

@NoArgsConstructor
class NotificationServiceTest {

  private final MutableClock clock = new MutableClock();
  private final InMemoryNotificationStore store = new InMemoryNotificationStore(100);
  private final NotificationService service =
      new NotificationService(store, new InputRedactor(List.of()), clock);

  @Test
  void raisingTheSameKeyTwiceKeepsOneItemAndSequenceGrows() {
    final Notification first = service.raise(action("input:r1").build()).block();
    final Notification second = service.raise(action("input:r1").build()).block();

    assertThat(second.id()).isEqualTo(first.id());
    assertThat(second.seq()).isGreaterThan(first.seq());
    assertThat(store.find(NotificationQuery.ALL).collectList().block()).hasSize(1);
  }

  @Test
  void aRepeatInsideTheCooldownRaisesTheCountOnly() {
    final var draft = info("run.failed:e1").cooldown(Duration.ofMinutes(5)).build();
    service.raise(draft).block();
    clock.advance(Duration.ofMinutes(1));
    final Notification again = service.raise(draft).block();

    assertThat(again.count()).isEqualTo(2);
    clock.advance(Duration.ofMinutes(10));
    assertThat(service.raise(draft).block().count()).isEqualTo(2);
  }

  @Test
  void aResolvedItemNeverBecomesActiveAgain() {
    service.raise(action("input:r1").build()).block();
    final Notification resolved = service.resolve("input:r1", "expired").block();
    final long seq = resolved.seq();

    final Notification again = service.raise(action("input:r1").build()).block();

    assertThat(again.state()).isEqualTo(Notification.State.RESOLVED);
    assertThat(again.seq()).isEqualTo(seq);
    assertThat(service.resolve("input:r1", "again").block()).isNull();
  }

  @Test
  void titleAndBodyAreRedactedAndClipped() {
    final Notification n =
        service.raise(info("k").title("t".repeat(300)).body("b".repeat(5000)).build()).block();

    assertThat(n.title()).hasSize(Notification.TITLE_MAX);
    assertThat(n.body()).hasSize(Notification.BODY_MAX);
  }

  @Test
  void receiptsAreKeptPerRecipientAndNoReceiptMeansUnread() {
    final Notification n = service.raise(info("k").build()).block();

    service.markRead("arun", List.of(n.id())).block();

    assertThat(service.receipts("arun", List.of(n.id())).collectList().block())
        .singleElement()
        .satisfies(r -> assertThat(r.readAt()).isNotNull());
    assertThat(service.receipts("local", List.of(n.id())).collectList().block()).isEmpty();
  }

  @Test
  void anOpenActionCannotBeDismissedButAResolvedOneCan() {
    final Notification n = service.raise(action("input:r1").build()).block();

    StepVerifier.create(service.dismiss("arun", n.id()))
        .expectError(IllegalStateException.class)
        .verify();
    service.resolve("input:r1", "cancelled").block();
    StepVerifier.create(service.dismiss("arun", n.id())).verifyComplete();
    assertThat(service.receipts("arun", List.of(n.id())).blockFirst().dismissedAt()).isNotNull();
    StepVerifier.create(service.dismiss("arun", "nope"))
        .expectError(NoSuchElementException.class)
        .verify();
  }

  @Test
  void everyChangeGoesToTheStream() {
    StepVerifier.create(service.changes().take(3))
        .then(() -> {
          service.raise(action("input:r1").build()).block();
          service.raise(action("input:r1").build()).block();
          service.resolve("input:r1", "answered").block();
        })
        .expectNextMatches(c -> c.kind() == NotificationChange.Kind.CREATED)
        .expectNextMatches(c -> c.kind() == NotificationChange.Kind.UPDATED)
        .expectNextMatches(c -> c.kind() == NotificationChange.Kind.RESOLVED)
        .verifyComplete();
  }
}
