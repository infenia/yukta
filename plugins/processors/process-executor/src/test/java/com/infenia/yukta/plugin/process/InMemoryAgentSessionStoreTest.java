// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.process;

import static org.assertj.core.api.Assertions.assertThat;

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@NoArgsConstructor
@SuppressWarnings("PMD.CloseResource")
class InMemoryAgentSessionStoreTest {

  private static AgentSessionStore.Lease acquire(final AgentSessionStore store, final String key) {
    return java.util.Objects.requireNonNull(store.tryAcquire(key));
  }

  @Test
  void oneTurnPerSessionAndTheTokenSurvivesTheLease() {
    final var store = new InMemoryAgentSessionStore();

    final var first = acquire(store, "k");
    assertThat(store.tryAcquire("k")).as("busy").isNull();
    first.complete("t1");
    first.close();
    first.close();

    final var second = acquire(store, "k");
    assertThat(second.token()).isEqualTo("t1");
    assertThat(second.turns()).isEqualTo(1);
  }

  @Test
  void blankTokenKeepsTheOldOne() {
    final var store = new InMemoryAgentSessionStore();
    final var a = acquire(store, "k");
    a.complete("t1");
    a.close();
    final var b = acquire(store, "k");
    b.complete("");
    b.close();

    assertThat(acquire(store, "k").token()).isEqualTo("t1");
  }

  @Test
  void idleSessionsBeyondTheCapAreForgotten_busyOnesStay() {
    final var store = new InMemoryAgentSessionStore();
    final var busy = acquire(store, "busy");
    for (int i = 0; i < InMemoryAgentSessionStore.MAX_SESSIONS + 10; i++) {
      acquire(store, "s" + i).close();
    }

    assertThat(store.tryAcquire("busy")).as("still held").isNull();
    busy.close();
  }

  @Test
  void nullTokenKeepsTheOldOne() {
    final var store = new InMemoryAgentSessionStore();
    final var first = acquire(store, "k");
    first.complete("t1");
    first.close();
    final var second = acquire(store, "k");
    second.complete(null);
    second.close();

    assertThat(acquire(store, "k").token()).isEqualTo("t1");
  }

  @Test
  void whenEverySessionIsBusy_noneIsEvicted() {
    final var store = new InMemoryAgentSessionStore();
    final var held = new java.util.ArrayList<AgentSessionStore.Lease>();
    for (int i = 0; i < InMemoryAgentSessionStore.MAX_SESSIONS + 5; i++) {
      held.add(acquire(store, "s" + i));
    }

    assertThat(store.tryAcquire("s0")).as("still held").isNull();
    held.forEach(AgentSessionStore.Lease::close);
  }

  private static void remember(final AgentSessionStore store, final String key) {
    final var lease = acquire(store, key);
    lease.complete("t-" + key);
    lease.close();
  }

  @Test
  void atTheCapNothingIsForgotten_oneOverForgetsOnlyTheOldest() {
    final var store = new InMemoryAgentSessionStore();
    for (int i = 0; i < InMemoryAgentSessionStore.MAX_SESSIONS; i++) {
      remember(store, "s" + i);
    }
    assertThat(acquire(store, "s0").token()).as("at the cap").isEqualTo("t-s0");

    final var store2 = new InMemoryAgentSessionStore();
    for (int i = 0; i <= InMemoryAgentSessionStore.MAX_SESSIONS; i++) {
      remember(store2, "s" + i);
    }
    assertThat(acquire(store2, "s0").token()).as("the oldest is gone").isNull();
    assertThat(acquire(store2, "s2").token()).as("the next one stays").isEqualTo("t-s2");
  }
}
