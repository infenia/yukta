// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

/** Tests for {@link PayloadFreeze}. */
@NoArgsConstructor
@SuppressWarnings("unchecked")
class PayloadFreezeTest {

  @Test
  void frozenContainersRejectEditsAndLeaveTheOriginalAlone() {
    final List<Object> inner = new ArrayList<>(List.of("a"));
    final Set<Object> tags = new HashSet<>(Set.of("t"));
    final Map<String, Object> payload = Map.of("items", inner, "tags", tags);
    final Message<?> original =
        DefaultMessage.create(UUID.randomUUID(), payload).withHeader("h", inner);

    final Message<?> frozen = PayloadFreeze.freezeOf(original);
    final Map<String, Object> copied = (Map<String, Object>) frozen.payload();

    assertThat(copied).containsEntry("items", List.of("a"));
    assertThatThrownBy(() -> copied.put("x", 1)).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> ((List<Object>) copied.get("items")).add("b"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> ((Set<Object>) copied.get("tags")).add("u"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> ((List<Object>) frozen.metadata().get("h")).add("c"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(inner).containsExactly("a");
  }

  @Test
  void leavesOtherPayloadsAlone() {
    assertThat(PayloadFreeze.freezeOf(DefaultMessage.create(UUID.randomUUID(), "text")).payload())
        .isEqualTo("text");
    assertThat(PayloadFreeze.freezeOf(DefaultMessage.create(UUID.randomUUID(), null)).payload())
        .isNull();
  }
}
