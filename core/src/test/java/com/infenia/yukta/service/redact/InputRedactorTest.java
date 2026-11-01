// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.redact;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.input.InputRequestSpec;
import com.infenia.yukta.service.input.InputTestSupport;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@NoArgsConstructor
class InputRedactorTest {

  private final InputRedactor redactor = new InputRedactor(List.of("customerNote"));

  @Test
  void promptHasSecretsBearerTokensAndEnvReferencesRemoved() {
    final InputRequestSpec spec = withContext(Map.of());

    final InputRequestSpec clean = redactor.redact(spec);

    assertThat(clean.prompt()).doesNotContain("hunter2", "abcdefghijkl", "DB_PASS");
    assertThat(clean.prompt()).contains(SecretRedactor.REDACTED);
    assertThat(clean.key()).isEqualTo(spec.key());
  }

  @Test
  void contextIsCleanedByKeyNameAndNestedValues() {
    final InputRequestSpec clean =
        redactor.redact(
            withContext(
                Map.of(
                    "customerNote", "private",
                    "nested", Map.of("password", "p", "text", "token=zzzz1234"),
                    "fine", "kept")));

    assertThat(clean.context()).containsEntry("customerNote", SecretRedactor.REDACTED);
    assertThat(clean.context()).containsEntry("fine", "kept");
    assertThat(clean.context().toString()).doesNotContain("private", "zzzz1234");
  }

  private static InputRequestSpec withContext(final Map<String, Object> context) {
    final InputRequestSpec base = InputTestSupport.spec("k");
    return new InputRequestSpec(
        base.key(),
        base.kind(),
        "Use Bearer abcdefghijkl and ${env.DB_PASS} and password=hunter2?",
        context,
        base.options(),
        base.responseSchema(),
        base.risk(),
        base.audience(),
        base.ttl(),
        base.onTimeout(),
        base.defaultDecision(),
        base.routeName(),
        base.requester());
  }
}
