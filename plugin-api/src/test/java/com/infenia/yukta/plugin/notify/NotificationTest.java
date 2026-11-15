// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

@NoArgsConstructor
class NotificationTest {

  private static final Instant AT = Instant.parse("2026-11-15T10:00:00Z");

  private static Notification make(final String title, final @Nullable String body) {
    return new Notification(
        "id",
        "key",
        NotificationKind.ACTION,
        NotificationSeverity.WARN,
        "input",
        title,
        body,
        "s",
        "w",
        "e",
        "n",
        "r",
        List.of("role:OPERATOR"),
        Notification.State.ACTIVE,
        null,
        1,
        3,
        AT,
        AT);
  }

  @Test
  void theKindsAndSeveritiesAreOrderedFromQuietToLoud() {
    assertThat(NotificationKind.values())
        .containsExactly(NotificationKind.INFO, NotificationKind.ACTION);
    assertThat(NotificationSeverity.valueOf("ERROR")).isGreaterThan(NotificationSeverity.WARN);
    assertThat(NotificationSeverity.WARN).isGreaterThan(NotificationSeverity.INFO);
  }

  @Test
  void titleAndBodyHaveLengthLimit() {
    assertThat(make("t".repeat(Notification.TITLE_MAX), "b".repeat(Notification.BODY_MAX)))
        .isNotNull();
    assertThatThrownBy(() -> make("t".repeat(Notification.TITLE_MAX + 1), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("title");
    assertThatThrownBy(() -> make("t", "b".repeat(Notification.BODY_MAX + 1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("body");
  }

  @Test
  void requiredPartsAreChecked() {
    assertThatThrownBy(() ->
            new Notification(
                null,
                "key",
                NotificationKind.INFO,
                NotificationSeverity.INFO,
                "c",
                "t",
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                Notification.State.ACTIVE,
                null,
                1,
                0,
                AT,
                AT))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("id");
  }

  @Test
  void theAudienceIsCopied() {
    final List<String> audience = new ArrayList<>(List.of("role:OPERATOR"));
    final Notification n =
        new Notification(
            "id",
            "key",
            NotificationKind.INFO,
            NotificationSeverity.INFO,
            "c",
            "t",
            null,
            null,
            null,
            null,
            null,
            null,
            audience,
            Notification.State.ACTIVE,
            null,
            1,
            0,
            AT,
            AT);
    audience.add("role:ADMIN");

    assertThat(n.audience()).containsExactly("role:OPERATOR");
  }

  @Test
  void resolvedAndStoredKeepEverythingElse() {
    final Notification n = make("t", "b");

    final Notification done = n.resolved("answered", 9, AT.plusSeconds(5));
    final Notification stored = n.stored("new-id", 12);

    assertThat(done.state()).isEqualTo(Notification.State.RESOLVED);
    assertThat(done.resolution()).isEqualTo("answered");
    assertThat(done.seq()).isEqualTo(9);
    assertThat(done.updatedAt()).isEqualTo(AT.plusSeconds(5));
    assertThat(done.createdAt()).isEqualTo(AT);
    assertThat(done.title()).isEqualTo("t");
    assertThat(stored.id()).isEqualTo("new-id");
    assertThat(stored.seq()).isEqualTo(12);
    assertThat(stored.state()).isEqualTo(Notification.State.ACTIVE);
  }

  @Test
  void specKeepsCopiesAndNeedsTitleAndSeverity() {
    final List<String> audience = new ArrayList<>(List.of("role:VIEWER"));
    final NotificationSpec spec =
        new NotificationSpec(
            "t", null, NotificationSeverity.INFO, audience, null, List.of("webhook:a"));
    audience.add("role:ADMIN");

    assertThat(spec.audience()).containsExactly("role:VIEWER");
    assertThatThrownBy(() ->
            new NotificationSpec(null, null, NotificationSeverity.INFO, List.of(), null, List.of()))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new NotificationSpec("t", null, null, List.of(), null, List.of()))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void channelContextKeepsCopyOfItsSettings() {
    final Map<String, Object> settings =
        new java.util.concurrent.ConcurrentHashMap<>(Map.of("url", "x"));
    final ChannelContext context = new ChannelContext("ops", settings, null, null, null);
    settings.put("other", 1);

    assertThat(context.settings()).containsOnlyKeys("url");
    assertThat(context.answerToken()).isNull();
  }
}
