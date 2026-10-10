// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.store;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.infenia.yukta.message.Message;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class NodeCheckpointStoreTest {

  /** A store that only knows the last message, as stores written before getAll existed do. */
  private static final class LastOnlyStore implements NodeCheckpointStore {
    private final Message<?> last;

    LastOnlyStore(final Message<?> last) {
      this.last = last;
    }

    @Override
    public Mono<Void> save(
        final String executionId, final String nodeId, final Message<?> message) {
      return Mono.empty();
    }

    @Override
    public Mono<Message<?>> get(final String executionId, final String nodeId) {
      return last == null ? Mono.empty() : Mono.just(last);
    }

    @Override
    public void clear(final String executionId) {
      // nothing recorded
    }
  }

  @Test
  void getAll_defaultsToTheLastMessage() {
    final Message<?> last = mock(Message.class);

    StepVerifier.create(new LastOnlyStore(last).getAll("e", "n")).expectNext(last).verifyComplete();
  }

  @Test
  void getAll_defaultIsEmptyWhenNothingIsRecorded() {
    StepVerifier.create(new LastOnlyStore(null).getAll("e", "n")).verifyComplete();
  }

  @Test
  void isComplete_defaultsToTrue() {
    assertTrue(new LastOnlyStore(null).isComplete("e", "n"));
  }

  @Test
  void markComplete_defaultsToDoingNothing_andNothingIsFinished() {
    final LastOnlyStore store = new LastOnlyStore(null);

    StepVerifier.create(store.markComplete("e", "n")).verifyComplete();

    org.junit.jupiter.api.Assertions.assertFalse(store.isFinished("e", "n"));
  }
}
