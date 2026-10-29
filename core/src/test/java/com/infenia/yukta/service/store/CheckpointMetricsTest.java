// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.plugin.store.NodeCheckpointStore;
import com.infenia.yukta.testsupport.ExpectedErrorLog;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

@NoArgsConstructor
@ExpectedErrorLog("ERROR com.infenia.yukta.service.store.FileNodeCheckpointStore - ")
class CheckpointMetricsTest {

  @TempDir /* default */ Path dir;

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
  void fileStore_reportsBytesWritten_andReleasesThemOnClear() {
    final FileNodeCheckpointStore store =
        new FileNodeCheckpointStore(dir.toString(), JsonMapper.builder().build());
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    new CheckpointMetrics(store).bindTo(registry);

    store.save("e1", "n1", DefaultMessage.create(UUID.randomUUID(), "x")).block();

    assertThat(registry.get("yukta.checkpoint.bytes").gauge().value()).isPositive();
    store.clear("e1");
    assertThat(store.bytesHeld()).isZero();
  }

  @Test
  void storeThatCannotTellItsSize_isNotPublished() {
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    new CheckpointMetrics(mock(NodeCheckpointStore.class)).bindTo(registry);

    assertThat(registry.getMeters()).isEmpty();
  }

  @Test
  void fileStore_publishesMarkerFailures() throws java.io.IOException {
    java.nio.file.Files.createFile(dir.resolve("e-e1"));
    final FileNodeCheckpointStore store =
        new FileNodeCheckpointStore(dir.toString(), JsonMapper.builder().build());
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    new CheckpointMetrics(store).bindTo(registry);

    store.markComplete("e1", "n1").block();

    assertThat(registry.get("yukta.checkpoint.errors").functionCounter().count()).isEqualTo(1.0);
  }

  @Test
  void inMemoryStore_reportsNoMarkerFailures() {
    assertThat(new InMemoryNodeCheckpointStore().markerFailures()).isZero();
  }
}
