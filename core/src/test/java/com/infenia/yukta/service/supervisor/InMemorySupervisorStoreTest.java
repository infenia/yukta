// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.service.supervisor.SupervisorStore.Hold;
import org.junit.jupiter.api.Test;

/** The in-memory store keeps the hold ledger: a delete names a holder, or every holder of a run. */
class InMemorySupervisorStoreTest {

  @Test
  void deleteHoldWithoutHolderForgetsTheRunForEveryHolder() {
    final InMemorySupervisorStore store = new InMemorySupervisorStore();
    store.saveHold(new Hold("a", "e1")).block();
    store.saveHold(new Hold("b", "e1")).block();
    store.saveHold(new Hold("a", "e2")).block();

    store.deleteHold(null, "e1").block();

    assertThat(store.holds().collectList().block()).containsExactly(new Hold("a", "e2"));
  }

  @Test
  void deleteHoldWithHolderForgetsOnlyThatHoldersHoldOfTheRun() {
    final InMemorySupervisorStore store = new InMemorySupervisorStore();
    store.saveHold(new Hold("a", "e1")).block();
    store.saveHold(new Hold("b", "e1")).block();
    store.saveHold(new Hold("a", "e2")).block();

    store.deleteHold("a", "e1").block();

    assertThat(store.holds().collectList().block())
        .containsExactly(new Hold("b", "e1"), new Hold("a", "e2"));
  }
}
