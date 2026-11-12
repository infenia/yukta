// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.process;

import static org.assertj.core.api.Assertions.assertThat;

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@NoArgsConstructor
class InMemoryAgentSessionStoreTest {

  @Test
  void oneTurnAtATime_andTheTokenSurvivesTheLease() {
    final var store = new InMemoryAgentSessionStore();

    final var first = store.tryAcquire("k");
    assertThat(store.tryAcquire("k")).as("busy").isNull();
    first.complete("t1");
    first.close();
    first.close();

    final var second = store.tryAcquire("k");
    assertThat(second.token()).isEqualTo("t1");
    assertThat(second.turns()).isEqualTo(1);
  }

  @Test
  void aBlankTokenKeepsTheOldOne() {
    final var store = new InMemoryAgentSessionStore();
    final var a = store.tryAcquire("k");
    a.complete("t1");
    a.close();
    final var b = store.tryAcquire("k");
    b.complete("");
    b.close();

    assertThat(store.tryAcquire("k").token()).isEqualTo("t1");
  }

  @Test
  void idleSessionsBeyondTheCapAreForgotten_busyOnesStay() {
    final var store = new InMemoryAgentSessionStore();
    final var busy = store.tryAcquire("busy");
    for (int i = 0; i < InMemoryAgentSessionStore.MAX_SESSIONS + 10; i++) {
      store.tryAcquire("s" + i).close();
    }

    assertThat(store.tryAcquire("busy")).as("still held").isNull();
    busy.close();
  }
}
