// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.stream;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for {@link PayloadCopy}. */
@NoArgsConstructor
@SuppressWarnings("unchecked")
class PayloadCopyTest {

  @Test
  void editingCopiedContainersDoesNotTouchTheOriginal() {
    final List<Object> inner = new ArrayList<>(List.of("a"));
    final Set<Object> tags = new HashSet<>(Set.of("t"));
    final byte[] bytes = {1};
    final Map<String, Object> payload = Map.of("items", inner, "tags", tags, "raw", bytes);
    final Message<?> original =
        DefaultMessage.create(UUID.randomUUID(), payload).withHeader("h", inner);

    final Message<?> copy = PayloadCopy.copyOf(original);
    final Map<String, Object> copied = (Map<String, Object>) copy.payload();
    ((List<Object>) copied.get("items")).add("b");
    ((Set<Object>) copied.get("tags")).add("u");
    ((byte[]) copied.get("raw"))[0] = 9;
    ((List<Object>) copy.metadata().get("h")).add("c");

    assertThat(inner).containsExactly("a");
    assertThat(tags).containsExactly("t");
    assertThat(bytes).containsExactly(1);
  }

  @Test
  void leavesOtherPayloadsAlone() {
    assertThat(PayloadCopy.copyOf(DefaultMessage.create(UUID.randomUUID(), "text")).payload())
        .isEqualTo("text");
    assertThat(PayloadCopy.copyOf(DefaultMessage.create(UUID.randomUUID(), null)).payload())
        .isNull();
  }
}
