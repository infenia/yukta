// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.redact;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@NoArgsConstructor
class SecretRedactorTest {

  private final SecretRedactor redactor = new SecretRedactor(null, null);

  @Test
  void textWithBearerTokensAndKeyValueSecretsIsCleaned() {
    assertThat(redactor.redactText("Authorization: Bearer abcdefgh12345 then password=hunter2"))
        .doesNotContain("abcdefgh12345", "hunter2");
    assertThat(redactor.redactText("plain text")).isEqualTo("plain text");
  }

  @Test
  void mapValueIsReplacedWhenItsKeyNamesSecretAndNestedValuesAreWalked() {
    @SuppressWarnings("unchecked")
    final Map<String, Object> cleaned =
        (Map<String, Object>)
            redactor.redact(
                Map.of(
                    "api_key", "x", "list", List.of("token=abc12345"), "n", Map.of("secret", 1)));

    assertThat(cleaned.get("api_key")).isEqualTo(SecretRedactor.REDACTED);
    assertThat(cleaned.get("list").toString()).doesNotContain("abc12345");
    assertThat(cleaned.get("n").toString()).contains(SecretRedactor.REDACTED);
  }

  @Test
  void extraKeysAreMatchedWholeAndIgnoringCase() {
    final SecretRedactor withKeys = new SecretRedactor(null, List.of("Note"));

    final Map<String, Object> cleaned =
        withKeys.redactMap(Map.of("note", "hide", "notebook", "keep"));

    assertThat(cleaned)
        .containsEntry("note", SecretRedactor.REDACTED)
        .containsEntry("notebook", "keep");
  }

  @Test
  void structureNestedTooDeepIsReplacedInsteadOfOverflowingTheStack() {
    Object nested = "leaf";
    for (int i = 0; i < 100; i++) {
      nested = List.of(nested);
    }

    assertThat(redactor.redact(nested).toString()).contains(SecretRedactor.REDACTED);
  }
}
