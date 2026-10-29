// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.message.DefaultMessage;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@NoArgsConstructor
class ResultCollectorTest {

  @Test
  void collectsMessages_andCompletionsInOrder_skippingNullMessages() {
    final ResultCollector collector = new ResultCollector();
    collector.add(DefaultMessage.create(UUID.randomUUID(), "a"));
    collector.add(null);
    collector.addCompletion("done", Map.of("reached", true));
    collector.addCompletion("out", Map.of("outcome", "RETRY"));

    assertThat(collector.getResults().stream().<Object>map(m -> m.payload()).toList())
        .containsExactly("a");
    assertThat(collector.getCompletions())
        .extracting(ResultCollector.Completion::nodeId)
        .containsExactly("done", "out");
  }

  @Test
  void completionKeepsAnImmutableCopyOfItsMetadata() {
    final Map<String, Object> metadata = new ConcurrentHashMap<>(Map.of("outcome", "SUCCESS"));
    final ResultCollector.Completion completion = new ResultCollector.Completion("t", metadata);
    metadata.put("reason", "later");

    assertThat(completion.metadata()).containsOnlyKeys("outcome");
    assertThatThrownBy(() -> completion.metadata().put("x", 1))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void clear_dropsMessagesAndCompletions() {
    final ResultCollector collector = new ResultCollector();
    collector.add(DefaultMessage.create(UUID.randomUUID(), "a"));
    collector.addCompletion("done", Map.of());

    collector.clear();

    assertThat(collector.getResults()).isEmpty();
    assertThat(collector.getCompletions()).isEmpty();
  }
}
