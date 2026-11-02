// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.redact;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.UseConcurrentHashMap"}) // nulls
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

  @Test
  void customPatternsReplaceTheDefaultsAndAnEmptyListKeepsThem() {
    final SecretRedactor custom = new SecretRedactor(List.of("foo\\d+"), null);
    final SecretRedactor empty = new SecretRedactor(List.of(), null);

    assertThat(custom.redactText("foo123 and password=hunter2"))
        .doesNotContain("foo123")
        .contains("hunter2");
    assertThat(empty.redactText("password=hunter2")).doesNotContain("hunter2");
  }

  @Test
  void blankAndMissingExtraKeyNamesAreIgnored() {
    final SecretRedactor withKeys =
        new SecretRedactor(null, java.util.Arrays.asList("note", null, "  "));

    assertThat(withKeys.redactMap(Map.of("note", "hide", "other", "keep")))
        .containsEntry("note", SecretRedactor.REDACTED)
        .containsEntry("other", "keep");
  }

  @Test
  void nestedMapsWithOddKeysAndOtherValueTypesAreCopied() {
    final Map<Object, Object> inner = new java.util.HashMap<>();
    inner.put(null, "nullKey");
    inner.put(7, "token=abc12345");
    inner.put("count", 5);
    inner.put("nothing", null);
    final Map<String, Object> outer = new java.util.HashMap<>();
    outer.put("inner", inner);
    outer.put("flag", true);
    outer.put("items", java.util.Set.of("plain"));

    final Map<String, Object> cleaned = redactor.redactMap(outer);

    assertThat(cleaned).containsEntry("flag", true);
    assertThat(cleaned.get("items")).isEqualTo(List.of("plain"));
    assertThat(cleaned.get("inner")).isInstanceOf(Map.class);
    final Map<?, ?> copy = (Map<?, ?>) cleaned.get("inner");
    assertThat(copy.get(null)).isEqualTo("nullKey");
    assertThat(copy.get(7).toString()).doesNotContain("abc12345");
    assertThat(copy.get("count")).isEqualTo(5);
    assertThat(copy.containsKey("nothing")).isTrue();
  }
}
