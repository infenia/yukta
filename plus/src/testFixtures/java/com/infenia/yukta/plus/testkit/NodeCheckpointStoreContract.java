// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plus.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.store.NodeCheckpointStore;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/**
 * What every {@link NodeCheckpointStore} must do. Extend it in the test of an implementation and
 * return a store that starts empty from {@link #create()}.
 */
public abstract class NodeCheckpointStoreContract {

  /** Creates the contract. */
  protected NodeCheckpointStoreContract() {
    super();
  }

  /**
   * A new, empty store.
   *
   * @return the store under test
   */
  protected abstract NodeCheckpointStore create();

  private static Message<?> message(final String payload) {
    return DefaultMessage.create(UUID.randomUUID(), payload);
  }

  @Test
  void nodeThatSavedNothingIsEmpty() {
    final NodeCheckpointStore store = create();

    StepVerifier.create(store.get("e1", "n1")).verifyComplete();
    StepVerifier.create(store.getAll("e1", "n1")).verifyComplete();
  }

  @Test
  void lastMessageComesFromGet_andGetAllKeepsTheOrder() {
    final NodeCheckpointStore store = create();
    store.save("e1", "n1", message("a")).block();
    store.save("e1", "n1", message("b")).block();

    StepVerifier.create(store.get("e1", "n1"))
        .assertNext(msg -> assertThat(msg.payload()).isEqualTo("b"))
        .verifyComplete();
    final var all = store.getAll("e1", "n1").map(Message::payload).collectList().block();
    assertThat(all).last().isEqualTo("b");
  }

  @Test
  void clearRemovesOnlyThatExecution() {
    final NodeCheckpointStore store = create();
    store.save("e1", "n1", message("a")).block();
    store.save("e2", "n1", message("b")).block();

    store.clear("e1");

    StepVerifier.create(store.get("e1", "n1")).verifyComplete();
    StepVerifier.create(store.get("e2", "n1"))
        .assertNext(msg -> assertThat(msg.payload()).isEqualTo("b"))
        .verifyComplete();
  }

  @Test
  void nodeIsFinishedOnlyAfterMarkComplete() {
    final NodeCheckpointStore store = create();
    store.save("e1", "n1", message("a")).block();
    final boolean before = store.isFinished("e1", "n1");

    store.markComplete("e1", "n1").block();

    assertThat(before).isFalse();
    assertThat(store.isComplete("e1", "n1")).isTrue();
  }
}
