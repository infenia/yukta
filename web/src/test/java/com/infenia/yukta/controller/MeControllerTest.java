// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.security.ApiKeys;
import com.infenia.yukta.security.ApiRole;
import com.infenia.yukta.security.CallerIdentity;
import java.util.Objects;
import java.util.Set;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.util.context.Context;

/** Tests for {@link MeController}. */
@NoArgsConstructor
class MeControllerTest {

  @Test
  void me_describesTheKeyOfTheRequest() {
    final var data =
        Objects.requireNonNull(
                new MeController()
                    .me()
                    .contextWrite(
                        Context.of(
                            CallerIdentity.CONTEXT_KEY,
                            new ApiKeys.Match("bob", ApiRole.OPERATOR, Set.of("s1"))))
                    .block())
            .data();

    assertThat(data.keyName()).isEqualTo("bob");
    assertThat(data.role()).isEqualTo(ApiRole.OPERATOR);
    assertThat(data.sessions()).containsExactly("s1");
    assertThat(data.authEnabled()).isTrue();
  }

  @Test
  void me_withNoKeyConfiguredIsAnAdminWithAuthOff() {
    final var data = Objects.requireNonNull(new MeController().me().block()).data();

    assertThat(data.authEnabled()).isFalse();
    assertThat(data.role()).isEqualTo(ApiRole.ADMIN);
  }
}
