// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.stream;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

@NoArgsConstructor
@SuppressWarnings("PMD.CommentRequired")
class NodeBroadcastTest {

  private static Flux<Message<?>> messages(final int count) {
    return Flux.range(0, count)
        .map(i -> (Message<?>) DefaultMessage.create(UUID.randomUUID(), "m" + i));
  }

  private static NodeBroadcast broadcast(final int count, final List<Disposable> disposables) {
    return new NodeBroadcast(messages(count).publish(4), disposables);
  }

  @Test
  void connectsOnlyAfterLastExpectedSubscriberAndDeliversToAll() {
    final List<Disposable> disposables = new ArrayList<>();
    final NodeBroadcast broadcast = broadcast(100, disposables);
    broadcast.expectSubscriber();
    broadcast.expectSubscriber();

    final List<Message<?>> first = new ArrayList<>();
    broadcast.subscribe(first::add);
    assertThat(disposables).isEmpty();
    assertThat(first).isEmpty();

    final List<Message<?>> second = new ArrayList<>();
    broadcast.subscribe(second::add);

    assertThat(disposables).hasSize(1);
    assertThat(first).hasSize(100);
    assertThat(second).hasSize(100);
  }

  @Test
  void connectIfUnobserved_drainsNodeWithoutSubscribers() {
    final List<Disposable> disposables = new ArrayList<>();
    final NodeBroadcast broadcast = broadcast(100, disposables);

    broadcast.connectIfUnobserved();
    broadcast.connectIfUnobserved();

    assertThat(disposables).hasSize(1);
  }

  @Test
  void connectIfUnobserved_isNoOpWhenSubscribersAreExpected() {
    final List<Disposable> disposables = new ArrayList<>();
    final NodeBroadcast broadcast = broadcast(10, disposables);
    broadcast.expectSubscriber();

    broadcast.connectIfUnobserved();
    assertThat(disposables).isEmpty();

    StepVerifier.create(broadcast).expectNextCount(10).verifyComplete();
    assertThat(disposables).hasSize(1);
  }

  @Test
  void extraSubscriptionDoesNotConnectAgain() {
    final List<Disposable> disposables = new ArrayList<>();
    final NodeBroadcast broadcast = broadcast(5, disposables);
    broadcast.expectSubscriber();

    StepVerifier.create(broadcast).expectNextCount(5).verifyComplete();
    // A late subscriber must not trigger a second connection.
    broadcast.subscribe(_ -> {});

    assertThat(disposables).hasSize(1);
  }

  @Test
  void connectIfUnobserved_swallowsSourceErrorOfUnobservedNode() {
    final List<Disposable> disposables = new ArrayList<>();
    final NodeBroadcast broadcast =
        new NodeBroadcast(
            Flux.<Message<?>>error(new IllegalStateException("boom")).publish(4), disposables);

    broadcast.connectIfUnobserved();

    assertThat(disposables).hasSize(1);
  }
}
