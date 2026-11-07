// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Secrets are hidden in what the API shows and taken back from storage on update. */
@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class SupervisorSettingsMaskTest {

  @Test
  void secretLookingKeysAndDeclaredSecretsAreHiddenAtEveryDepth() {
    final Map<String, Object> shown =
        SupervisorSettingsMask.hide(
            Map.of(
                "apiKey", "sk-1",
                "bearer", "abc",
                "passphrase", "pp",
                "hmac", "h",
                "model", "m",
                "nested", Map.of("password", "pw", "url", "http://x"),
                "list", List.of(Map.of("token", "t", "url", "u"))),
            Set.of("hmac"));

    assertThat(shown)
        .containsEntry("apiKey", "***")
        .containsEntry("bearer", "***")
        .containsEntry("passphrase", "***")
        .containsEntry("hmac", "***")
        .containsEntry("model", "m")
        .containsEntry("nested", Map.of("password", "***", "url", "http://x"))
        .containsEntry("list", List.of(Map.of("token", "***", "url", "u")));
  }

  @Test
  void nullValuesAreLeftOut() {
    assertThat(SupervisorSettingsMask.hide(java.util.Collections.singletonMap("k", null), Set.of()))
        .isEmpty();
  }

  @Test
  void theMarkerKeepsTheStoredValueAtEveryDepthAndStaysWhenNothingIsStored() {
    final Map<String, Object> kept =
        SupervisorSettingsMask.keepHidden(
            Map.of(
                "apiKey", "***",
                "other", "***",
                "nested", Map.of("password", "***", "url", "new")),
            Map.of("apiKey", "real", "nested", Map.of("password", "pw", "url", "old")));

    assertThat(kept)
        .containsEntry("apiKey", "real")
        .containsEntry("other", "***")
        .containsEntry("nested", Map.of("password", "pw", "url", "new"));
  }

  @Test
  void nestedValueWithNothingStoredBehindItIsKeptAsSent() {
    assertThat(
            SupervisorSettingsMask.keepHidden(
                Map.of("nested", Map.of("password", "***")), Map.of("nested", "text")))
        .containsEntry("nested", Map.of("password", "***"));
  }
}
