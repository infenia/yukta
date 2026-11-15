// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.notify;

import static com.infenia.yukta.service.notify.NotifyTestSupport.action;
import static com.infenia.yukta.service.notify.NotifyTestSupport.info;
import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.notify.Notification;
import com.infenia.yukta.security.ApiKeys;
import com.infenia.yukta.security.ApiRole;
import com.infenia.yukta.service.input.InputTestSupport.MutableClock;
import com.infenia.yukta.service.redact.InputRedactor;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

@NoArgsConstructor
@SuppressFBWarnings(
    value = "NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE",
    justification = "block() of a store call that always emits")
class NotificationServiceTest {

  private static final String KEY = "input:r1";
  private static final String ARUN = "arun";

  private final MutableClock clock = new MutableClock();
  private final InMemoryNotificationStore store = new InMemoryNotificationStore(100);
  private final NotificationService service =
      new NotificationService(store, new InputRedactor(List.of()), clock);

  @Test
  void raisingTheSameKeyTwiceKeepsOneItemAndSequenceGrows() {
    final Notification first = service.raise(action(KEY).build()).block();
    final Notification second = service.raise(action(KEY).build()).block();

    assertThat(second.id()).isEqualTo(first.id());
    assertThat(second.seq()).isGreaterThan(first.seq());
    assertThat(store.find(NotificationQuery.ALL).collectList().block()).hasSize(1);
  }

  @Test
  void repeatInsideTheCooldownRaisesTheCountOnly() {
    final var draft = info("run.failed:e1").cooldown(Duration.ofMinutes(5)).build();
    service.raise(draft).block();
    clock.advance(Duration.ofMinutes(1));
    final Notification again = service.raise(draft).block();

    assertThat(again.count()).isEqualTo(2);
    clock.advance(Duration.ofMinutes(10));
    assertThat(service.raise(draft).block().count()).isEqualTo(2);
  }

  @Test
  void resolvedItemNeverBecomesActiveAgain() {
    service.raise(action(KEY).build()).block();
    final Notification resolved = service.resolve(KEY, "expired").block();
    final long seq = resolved.seq();

    final Notification again = service.raise(action(KEY).build()).block();

    assertThat(again.state()).isEqualTo(Notification.State.RESOLVED);
    assertThat(again.seq()).isEqualTo(seq);
    assertThat(service.resolve(KEY, "again").block()).isNull();
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

    service.markRead(ARUN, List.of(n.id())).block();

    assertThat(service.receipts(ARUN, List.of(n.id())).collectList().block())
        .singleElement()
        .satisfies(r -> assertThat(r.readAt()).isNotNull());
    assertThat(service.receipts("local", List.of(n.id())).collectList().block()).isEmpty();
  }

  @Test
  void openActionCannotBeDismissedButResolvedOneCan() {
    final Notification n = service.raise(action(KEY).build()).block();

    StepVerifier.create(service.dismiss(ARUN, n.id()))
        .expectError(IllegalStateException.class)
        .verify();
    service.resolve(KEY, "cancelled").block();
    StepVerifier.create(service.dismiss(ARUN, n.id())).verifyComplete();
    assertThat(service.receipts(ARUN, List.of(n.id())).blockFirst().dismissedAt()).isNotNull();
    StepVerifier.create(service.dismiss(ARUN, "nope"))
        .expectError(com.infenia.yukta.service.NotFoundException.class)
        .verify();
  }

  @Test
  void everyChangeGoesToTheStream() {
    StepVerifier.create(service.changes().take(3))
        .then(() -> {
          service.raise(action(KEY).build()).block();
          service.raise(action(KEY).build()).block();
          service.resolve(KEY, "answered").block();
        })
        .expectNextMatches(c -> c.kind() == NotificationChange.Kind.CREATED)
        .expectNextMatches(c -> c.kind() == NotificationChange.Kind.UPDATED)
        .expectNextMatches(c -> c.kind() == NotificationChange.Kind.RESOLVED)
        .verifyComplete();
  }

  @Test
  void readingTwiceKeepsTheFirstTimeAndDismissingAfterReadingKeepsIt() {
    final Notification n = service.raise(info("k").build()).block();
    service.markRead(ARUN, List.of(n.id())).block();
    final var first = service.receipts(ARUN, List.of(n.id())).blockFirst().readAt();
    clock.advance(Duration.ofMinutes(1));
    service.markRead(ARUN, List.of(n.id())).block();
    service.dismiss(ARUN, n.id()).block();
    clock.advance(Duration.ofMinutes(1));
    service.dismiss(ARUN, n.id()).block();

    final var receipt = service.receipts(ARUN, List.of(n.id())).blockFirst();
    assertThat(receipt.readAt()).isEqualTo(first);
    assertThat(receipt.dismissedAt()).isNotNull().isBefore(clock.instant());
  }

  @Test
  void visibleFiltersByCallerAndPreferencesAndEpochPassThrough() {
    service.raise(info("a").build()).block();
    service.raise(info("b").build()).block();

    assertThat(service.visible(NotificationQuery.ALL, null).collectList().block()).hasSize(2);
    assertThat(
            service
                .visible(NotificationQuery.ALL, new ApiKeys.Match("v", ApiRole.VIEWER, Set.of()))
                .collectList()
                .block())
        .isEmpty();
    service.savePreferences(NotificationPreferences.defaults(ARUN)).block();
    assertThat(service.preferences(ARUN).block().recipient()).isEqualTo(ARUN);
    assertThat(service.epoch()).isEqualTo(store.epoch());
  }

  @Test
  void theSpringConstructorUsesTheSystemClock() {
    final Notification n =
        new NotificationService(store, new InputRedactor(List.of()))
            .raise(info("c").build())
            .block();

    assertThat(n.createdAt()).isNotNull();
  }
}
