// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Set;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.util.context.Context;

/** Tests for {@link CallerIdentity}. */
@NoArgsConstructor
class CallerIdentityTest {

  private static CallerIdentity.Whoami whoAs(final ApiRole role, final String... sessions) {
    return CallerIdentity.whoami(
        Context.of(CallerIdentity.CONTEXT_KEY, new ApiKeys.Match("alice", role, Set.of(sessions))));
  }

  @Test
  void noKeyConfigured_isAnAdminWithEverythingAllowed() {
    final var who = CallerIdentity.whoami(Context.empty());

    assertThat(who.authEnabled()).isFalse();
    assertThat(who.authenticated()).isFalse();
    assertThat(who.keyName()).isNull();
    assertThat(who.role()).isEqualTo(ApiRole.ADMIN);
    assertThat(who.sessions()).isNull();
    assertThat(who.permissions())
        .isEqualTo(new CallerIdentity.Permissions(true, true, true, true, true, true));
  }

  @Test
  void viewer_mayChangeNothing() {
    final var who = whoAs(ApiRole.VIEWER);

    assertThat(who.authEnabled()).isTrue();
    assertThat(who.authenticated()).isTrue();
    assertThat(who.keyName()).isEqualTo("alice");
    assertThat(who.permissions())
        .isEqualTo(new CallerIdentity.Permissions(false, false, false, false, false, false));
  }

  @Test
  void operator_mayStartAndControlRunsOnly() {
    assertThat(whoAs(ApiRole.OPERATOR).permissions())
        .isEqualTo(new CallerIdentity.Permissions(true, true, false, false, false, false));
  }

  @Test
  void admin_mayDoEverything() {
    assertThat(whoAs(ApiRole.ADMIN).permissions())
        .isEqualTo(new CallerIdentity.Permissions(true, true, true, true, true, true));
  }

  @Test
  void sessionLimitedKeyListsThemSortedAndCannotReachSessionlessPaths() {
    final var who = whoAs(ApiRole.ADMIN, "team-b", "team-a");

    assertThat(who.sessions()).containsExactly("team-a", "team-b");
    assertThat(who.permissions())
        .isEqualTo(new CallerIdentity.Permissions(true, true, false, false, false, false));
  }

  @Test
  void theIdentityHasNoPlaceForKeyOrHash() {
    assertThat(CallerIdentity.Whoami.class.getRecordComponents())
        .extracting(RecordComponent::getName)
        .containsExactly(
            "authenticated", "keyName", "role", "sessions", "authEnabled", "permissions");
  }
}
