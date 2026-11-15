// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.notify;

import static com.infenia.yukta.service.notify.NotifyTestSupport.action;
import static com.infenia.yukta.service.notify.NotifyTestSupport.info;
import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.notify.Notification;
import com.infenia.yukta.plugin.notify.NotificationKind;
import com.infenia.yukta.plugin.notify.NotificationSeverity;
import com.infenia.yukta.security.ApiKeys;
import com.infenia.yukta.security.ApiRole;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

@NoArgsConstructor
class RecipientsTest {

  private static final String OPERATOR = "role:OPERATOR";

  private static Notification build(
      final NotificationKind kind, final @Nullable String session, final String... audience) {
    return new Notification(
        "id",
        "k",
        kind,
        NotificationSeverity.INFO,
        "input",
        "t",
        null,
        session,
        null,
        null,
        null,
        null,
        List.of(audience),
        Notification.State.ACTIVE,
        null,
        1,
        1,
        Instant.EPOCH,
        Instant.EPOCH);
  }

  private static ApiKeys.Match key(
      final String name, final ApiRole role, final boolean agent, final String... sessions) {
    return new ApiKeys.Match(name, role, Set.of(sessions), agent);
  }

  @Test
  void roleSelectorMatchesTheRoleAndAbove() {
    final Notification n = build(NotificationKind.INFO, null, OPERATOR);

    assertThat(Recipients.sees(n, key("v", ApiRole.VIEWER, false))).isFalse();
    assertThat(Recipients.sees(n, key("o", ApiRole.OPERATOR, false))).isTrue();
    assertThat(Recipients.sees(n, key("a", ApiRole.ADMIN, false))).isTrue();
  }

  @Test
  void viewerSelectorLetsEveryoneSeeAnInfo() {
    assertThat(
            Recipients.sees(
                build(NotificationKind.INFO, null, "role:VIEWER"), key("v", ApiRole.VIEWER, false)))
        .isTrue();
  }

  @Test
  void keySelectorMatchesOnlyThatKey() {
    final Notification n = build(NotificationKind.INFO, null, "key:arun");

    assertThat(Recipients.sees(n, key("arun", ApiRole.VIEWER, false))).isTrue();
    assertThat(Recipients.sees(n, key("sam", ApiRole.ADMIN, false))).isFalse();
  }

  @Test
  void sessionLimitedKeySeesOnlyItsSessionsAndThoseWithoutOne() {
    final ApiKeys.Match scoped = key("team", ApiRole.OPERATOR, false, "a");

    assertThat(Recipients.sees(build(NotificationKind.INFO, "a", OPERATOR), scoped)).isTrue();
    assertThat(Recipients.sees(build(NotificationKind.INFO, "b", OPERATOR), scoped)).isFalse();
    assertThat(Recipients.sees(build(NotificationKind.INFO, null, OPERATOR), scoped)).isTrue();
  }

  @Test
  void anAgentKeySeesNoActionByRoleButSeesOneThatNamesIt() {
    final ApiKeys.Match agent = key("bot", ApiRole.ADMIN, true);

    assertThat(Recipients.sees(build(NotificationKind.ACTION, null, OPERATOR), agent)).isFalse();
    assertThat(Recipients.sees(build(NotificationKind.ACTION, null, "key:bot"), agent)).isTrue();
    assertThat(Recipients.sees(build(NotificationKind.INFO, null, OPERATOR), agent)).isTrue();
  }

  @Test
  void openServerAndUnnamedKeyShareTheLocalInbox() {
    final Notification n = build(NotificationKind.ACTION, null, "key:local");

    assertThat(Recipients.recipient(null)).isEqualTo(Recipients.LOCAL);
    assertThat(Recipients.recipient(key("unnamed", ApiRole.ADMIN, false)))
        .isEqualTo(Recipients.LOCAL);
    assertThat(Recipients.recipient(key("arun", ApiRole.ADMIN, false))).isEqualTo("arun");
    assertThat(Recipients.sees(n, null)).isTrue();
    assertThat(Recipients.sees(n, key("unnamed", ApiRole.VIEWER, false))).isTrue();
  }

  @Test
  void selectorsAreValidated() {
    assertThat(Recipients.isSelector("role:VIEWER")).isTrue();
    assertThat(Recipients.isSelector("key:arun")).isTrue();
    assertThat(Recipients.isSelector("supervisor:*")).isFalse();
    assertThat(Recipients.isSelector("role:ROOT")).isFalse();
    assertThat(Recipients.isSelector(null)).isFalse();
    assertThat(action("x").build().audience()).containsExactly(OPERATOR);
    assertThat(info("y").build().audience()).containsExactly(OPERATOR);
  }

  @Test
  void selectorOfNeitherShapeMatchesNobody() {
    assertThat(
            Recipients.sees(
                build(NotificationKind.INFO, null, "supervisor:x"), key("a", ApiRole.ADMIN, false)))
        .isFalse();
  }
}
