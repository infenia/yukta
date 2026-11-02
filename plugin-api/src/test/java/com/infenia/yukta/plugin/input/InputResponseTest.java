// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.input;

import static com.infenia.yukta.plugin.input.InputFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.UseConcurrentHashMap"})
class InputResponseTest {

  private static final Responder HUMAN = new Responder(Responder.Type.HUMAN, "key:ops");

  private static InputResponse make(
      final String decision, final String text, final Map<String, Object> data, final Double c) {
    return new InputResponse("r1", decision, text, data, HUMAN, "because", c, NOW);
  }

  @Test
  void validAnswerKeepsItsParts() {
    final InputResponse response = make("approve", "fine", Map.of("a", 1), 0.9);
    assertThat(response.decision()).isEqualTo("approve");
    assertThat(response.data()).containsEntry("a", 1);
    assertThat(response.confidence()).isEqualTo(0.9);
  }

  @Test
  void theDataIsCopiedAndMayHoldNulls() {
    final Map<String, Object> data = new HashMap<>();
    data.put("a", null);
    final InputResponse response = make("approve", null, data, null);
    data.put("b", 2);
    assertThat(response.data()).containsOnlyKeys("a");
    assertThatThrownBy(() -> response.data().put("c", 3))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(make("approve", null, null, null).data()).isEmpty();
  }

  @Test
  void theLimitsApply() {
    assertThatThrownBy(() -> make(" ", null, Map.of(), null))
        .hasMessage("decision must not be blank");
    assertThatThrownBy(() -> make("a", "x".repeat(9000), Map.of(), null))
        .hasMessage("text is longer than 8192 bytes");
    assertThatThrownBy(() -> make("a", null, Map.of("k", "x".repeat(17_000)), null))
        .hasMessage("data is longer than 16384 bytes");
    assertThatThrownBy(() -> make("a", null, Map.of(), 2.0))
        .hasMessage("confidence must be between 0 and 1");
    assertThatThrownBy(
            () -> new InputResponse("r1", "a", null, Map.of(), HUMAN, "x".repeat(9000), null, NOW))
        .hasMessage("rationale is longer than 8192 bytes");
  }

  @Test
  void theRequiredPartsAreChecked() {
    assertThatThrownBy(() -> new InputResponse(" ", "a", null, Map.of(), HUMAN, null, null, NOW))
        .hasMessage("requestId must not be blank");
    assertThatThrownBy(() -> new InputResponse("r", "a", null, Map.of(), null, null, null, NOW))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new InputResponse("r", "a", null, Map.of(), HUMAN, null, null, null))
        .isInstanceOf(NullPointerException.class);
  }
}
