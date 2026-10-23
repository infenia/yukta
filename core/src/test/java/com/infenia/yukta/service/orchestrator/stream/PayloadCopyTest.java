// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.stream;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PayloadCopyTest {

  @Test
  void editingACopiedPayloadDoesNotTouchTheOriginal() {
    final List<Object> inner = new ArrayList<>(List.of("a"));
    final Map<String, Object> payload = Map.of("items", inner);
    final Message<?> original =
        DefaultMessage.create(UUID.randomUUID(), payload).withHeader("tags", inner);

    final Message<?> copy = PayloadCopy.of(original);
    ((List<Object>) ((Map<?, ?>) copy.payload()).get("items")).add("b");
    ((List<Object>) copy.metadata().get("tags")).add("c");

    assertThat(inner).containsExactly("a");
    assertThat(copy.payload()).isEqualTo(Map.of("items", List.of("a", "b")));
  }

  @Test
  void leavesNonContainerPayloadsAlone() {
    final Message<?> original = DefaultMessage.create(UUID.randomUUID(), "text");

    assertThat(PayloadCopy.of(original).payload()).isSameAs("text");
  }
}
