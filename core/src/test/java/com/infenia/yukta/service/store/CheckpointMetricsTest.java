// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.plugin.store.NodeCheckpointStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@NoArgsConstructor
class CheckpointMetricsTest {

  @Test
  void inMemoryStore_reportsBytesHeld_andReleasesThemOnClear() {
    final InMemoryNodeCheckpointStore store = new InMemoryNodeCheckpointStore();
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    new CheckpointMetrics(store).bindTo(registry);

    store.save("e1", "n1", DefaultMessage.create(UUID.randomUUID(), "12345")).block();
    store.save("e2", "n1", DefaultMessage.create(UUID.randomUUID(), "123")).block();

    assertThat(registry.get("yukta.checkpoint.bytes").gauge().value()).isEqualTo(8.0);
    store.clear("e1");
    assertThat(registry.get("yukta.checkpoint.bytes").gauge().value()).isEqualTo(3.0);
    store.clear("never-seen");
    assertThat(store.bytesHeld()).isEqualTo(3);
  }

  @Test
  void storeThatCannotTellItsSize_isNotPublished() {
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    new CheckpointMetrics(mock(NodeCheckpointStore.class)).bindTo(registry);

    assertThat(registry.getMeters()).isEmpty();
  }

  @Test
  void inMemoryStore_reportsNoMarkerFailures() {
    assertThat(new InMemoryNodeCheckpointStore().markerFailures()).isZero();
  }
}
