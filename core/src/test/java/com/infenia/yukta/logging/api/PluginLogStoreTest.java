// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.logging.api;

import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import com.infenia.yukta.plugin.core.LogLevel;
import java.time.Instant;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

@NoArgsConstructor
class PluginLogStoreTest {

  @Test
  void storeThatDropsNothingNumbersItsLinesFromZero() {
    final PluginLogStore store = mock(PluginLogStore.class, CALLS_REAL_METHODS);
    final PluginLogEntry first = line("one");
    final PluginLogEntry second = line("two");
    doReturn(Flux.just(first, second)).when(store).readExecution("e1");

    StepVerifier.create(store.readIndexed("e1"))
        .expectNext(new IndexedLog(0, first), new IndexedLog(1, second))
        .verifyComplete();
  }

  private static PluginLogEntry line(final String message) {
    return new PluginLogEntry(
        "e1",
        "s1",
        "n1",
        "Node",
        LogStream.STDOUT,
        message,
        LogLevel.INFO,
        Instant.parse("2026-01-01T00:00:00Z"),
        null,
        null);
  }
}
