// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core.flow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.IntStream;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/** Tests for InMemoryKeyValueStore. */
@NoArgsConstructor
class InMemoryKeyValueStoreTest {

  @Test
  void concurrentPutsNeverExceedTheCap() {
    final InMemoryKeyValueStore store = new InMemoryKeyValueStore();
    IntStream.range(0, 2000)
        .parallel()
        .forEach(i -> store.put("k" + i, i, 10).onErrorResume(e -> Mono.empty()).block());
    final long stored =
        IntStream.range(0, 2000).filter(i -> store.get("k" + i).block() != null).count();
    assertThat(stored).isEqualTo(10);
  }
}
