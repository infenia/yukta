// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.mcp.dto.NotificationList;
import com.infenia.yukta.plugin.notify.Notification;
import com.infenia.yukta.plugin.notify.NotificationKind;
import com.infenia.yukta.plugin.notify.NotificationSeverity;
import com.infenia.yukta.security.ApiKeys;
import com.infenia.yukta.security.ApiRole;
import com.infenia.yukta.security.CallerIdentity;
import com.infenia.yukta.service.leader.NotLeaderException;
import com.infenia.yukta.service.notify.InMemoryNotificationStore;
import com.infenia.yukta.service.notify.NotificationDraft;
import com.infenia.yukta.service.notify.NotificationService;
import com.infenia.yukta.service.redact.InputRedactor;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** The inbox tools show what the key may see, and nothing more. */
@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class NotificationProviderTest {

  private static final ApiKeys.Match ADMIN = new ApiKeys.Match("admin", ApiRole.ADMIN, Set.of());
  private static final ApiKeys.Match SCOPED =
      new ApiKeys.Match("scoped", ApiRole.OPERATOR, Set.of("s1"));
  private static final ApiKeys.Match VIEWER = new ApiKeys.Match("viewer", ApiRole.VIEWER, Set.of());

  private final NotificationService service =
      new NotificationService(new InMemoryNotificationStore(1000), new InputRedactor(List.of()));
  private final AtomicBoolean leader = new AtomicBoolean(true);
  private final NotificationProvider provider = new NotificationProvider(service, leader::get);

  private Notification raise(
      final String key, final NotificationKind kind, final String session, final String body) {
    return service
        .raise(
            NotificationDraft.builder()
                .key(key)
                .kind(kind)
                .severity(NotificationSeverity.WARN)
                .category("input")
                .title("t-" + key)
                .body(body)
                .sessionId(session)
                .executionId("e1")
                .inputRequestId(kind == NotificationKind.ACTION ? "r-" + key : null)
                .audience(List.of("role:VIEWER"))
                .cooldown(Duration.ZERO)
                .build())
        .block();
  }

  private static <T> Mono<T> as(final ApiKeys.Match key, final Mono<T> call) {
    return call.contextWrite(ctx -> ctx.put(CallerIdentity.CONTEXT_KEY, key));
  }

  @Test
  void keyListsOnlyWhatItsSessionsAndRolesReach() {
    raise("a", NotificationKind.INFO, "s1", "hello");
    raise("b", NotificationKind.INFO, "s2", null);
    service
        .raise(
            NotificationDraft.builder()
                .key("c")
                .kind(NotificationKind.INFO)
                .severity(NotificationSeverity.INFO)
                .category("run.failed")
                .title("admins only")
                .audience(List.of("role:ADMIN"))
                .cooldown(Duration.ZERO)
                .build())
        .block();

    StepVerifier.create(as(ADMIN, provider.list(null, null)))
        .assertNext(l -> assertThat(l.notifications()).hasSize(3))
        .verifyComplete();
    StepVerifier.create(as(SCOPED, provider.list(null, null)))
        .assertNext(l ->
            assertThat(l.notifications())
                .extracting(NotificationList.Entry::title)
                .containsExactly("t-a"))
        .verifyComplete();
    StepVerifier.create(as(VIEWER, provider.list(null, null)))
        .assertNext(l -> assertThat(l.notifications()).hasSize(2))
        .verifyComplete();
  }

  @Test
  void anActionCarriesTheIdsToAnswerWithAndFiltersByKindAndUnread() {
    final Notification info = raise("a", NotificationKind.INFO, "s1", "x".repeat(2000));
    raise("q", NotificationKind.ACTION, "s1", null);
    service.markRead("admin", List.of(info.id())).block();

    StepVerifier.create(as(ADMIN, provider.list(null, "action")))
        .assertNext(l -> {
          assertThat(l.notifications())
              .singleElement()
              .satisfies(e -> {
                assertThat(e.executionId()).isEqualTo("e1");
                assertThat(e.requestId()).isEqualTo("r-q");
                assertThat(e.read()).isFalse();
              });
        })
        .verifyComplete();
    StepVerifier.create(as(ADMIN, provider.list(true, " ")))
        .assertNext(l ->
            assertThat(l.notifications()).extracting(NotificationList.Entry::category).hasSize(1))
        .verifyComplete();
    StepVerifier.create(as(ADMIN, provider.list(false, null)))
        .assertNext(l -> {
          final NotificationList.Entry read =
              l.notifications().stream()
                  .filter(e -> e.id().equals(info.id()))
                  .findFirst()
                  .orElseThrow();
          assertThat(read.read()).isTrue();
          assertThat(read.body()).hasSize(NotificationProvider.MAX_BODY + 1).endsWith("…");
        })
        .verifyComplete();
    StepVerifier.create(as(ADMIN, provider.list(null, "nope")))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void theListIsCutAndDismissedOnesAreLeftOut() {
    for (int i = 0; i < NotificationProvider.MAX_NOTIFICATIONS + 3; i++) {
      raise("k" + i, NotificationKind.INFO, null, null);
    }
    final Notification gone = raise("gone", NotificationKind.INFO, null, null);
    service.dismiss("admin", gone.id()).block();

    StepVerifier.create(as(ADMIN, provider.list(null, null)))
        .assertNext(l -> {
          assertThat(l.notifications()).hasSize(NotificationProvider.MAX_NOTIFICATIONS);
          assertThat(l.truncated()).isTrue();
          assertThat(l.notifications()).noneMatch(e -> e.id().equals(gone.id()));
        })
        .verifyComplete();
  }

  @Test
  void markReadMarksTheListedIdsOrAllAndIgnoresWhatTheKeyCannotSee() {
    final Notification a = raise("a", NotificationKind.INFO, "s1", null);
    final Notification b = raise("b", NotificationKind.INFO, "s2", null);

    StepVerifier.create(as(SCOPED, provider.markRead(List.of(a.id(), b.id()), false)))
        .assertNext(m -> assertThat(m.marked()).isEqualTo(1))
        .verifyComplete();
    StepVerifier.create(as(ADMIN, provider.markRead(null, true)))
        .assertNext(m -> assertThat(m.marked()).isEqualTo(2))
        .verifyComplete();
    StepVerifier.create(as(ADMIN, provider.markRead(null, true)))
        .assertNext(m -> assertThat(m.marked()).isZero())
        .verifyComplete();
    StepVerifier.create(as(ADMIN, provider.markRead(List.of(), false)))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(as(ADMIN, provider.markRead(null, false)))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void standbyServerAnswers503AndOpenServerHasTheSharedInbox() {
    raise("a", NotificationKind.INFO, null, null);

    StepVerifier.create(provider.list(null, null))
        .assertNext(l -> assertThat(l.notifications()).hasSize(1))
        .verifyComplete();
    leader.set(false);
    StepVerifier.create(provider.list(null, null)).expectError(NotLeaderException.class).verify();
  }
}
