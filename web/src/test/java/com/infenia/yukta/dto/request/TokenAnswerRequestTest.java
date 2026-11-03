// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** The answer with a token keeps its data as a copy. */
@NoArgsConstructor
class TokenAnswerRequestTest {

  @Test
  void dataIsCopiedAndMissingDataIsEmpty() {
    final Map<String, Object> data = new java.util.concurrent.ConcurrentHashMap<>(Map.of("a", 1));
    final TokenAnswerRequest with =
        new TokenAnswerRequest("r1", "hook", 10, "t", "approve", null, data, null);
    data.clear();

    assertThat(with.data()).containsEntry("a", 1);
    assertThat(new TokenAnswerRequest("r1", "hook", 10, "t", "approve", null, null, null).data())
        .isEmpty();
  }
}
