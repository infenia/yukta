// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.stream;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.workflow.NodePolicy;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

@NoArgsConstructor
@SuppressWarnings("PMD.CommentRequired")
class NodePoliciesTest {

  private static final NodePolicy.Retry RETRY3 =
      new NodePolicy.Retry(3, Duration.ofSeconds(1), Duration.ofSeconds(8));

  private final Message<?> input = DefaultMessage.create(UUID.randomUUID(), "in");

  /** A plugin call that fails {@code failures} times and then returns {@code out}. */
  private Flux<Message<?>> flaky(final AtomicInteger calls, final int failures) {
    return Flux.defer(
        () ->
            calls.incrementAndGet() <= failures
                ? Flux.<Message<?>>error(new IllegalStateException("boom"))
                : Flux.<Message<?>>just(input));
  }

  @Test
  void retry_succeedsOnLaterAttempt_afterBackoff() {
    final AtomicInteger calls = new AtomicInteger();
    final NodePolicy policy =
        new NodePolicy(RETRY3, NodePolicy.OnError.FAIL, NodePolicy.JoinRule.ALL_SUCCESS);

    StepVerifier.withVirtualTime(() -> NodePolicies.apply(policy, "n1", input, flaky(calls, 2)))
        .expectSubscription()
        .expectNoEvent(Duration.ofMillis(400))
        .thenAwait(Duration.ofSeconds(30))
        .expectNext(input)
        .verifyComplete();

    assertThat(calls).hasValue(3);
  }

  @Test
  void retry_exhausted_failsWithTheOriginalError() {
    final AtomicInteger calls = new AtomicInteger();
    final NodePolicy policy =
        new NodePolicy(RETRY3, NodePolicy.OnError.FAIL, NodePolicy.JoinRule.ALL_SUCCESS);

    StepVerifier.withVirtualTime(() -> NodePolicies.apply(policy, "n1", input, flaky(calls, 99)))
        .expectSubscription()
        .thenAwait(Duration.ofSeconds(60))
        .expectErrorMatches(
            e -> e instanceof IllegalStateException && "boom".equals(e.getMessage()))
        .verify();

    assertThat(calls).hasValue(3);
  }

  @Test
  void singleAttempt_doesNotRetry() {
    final AtomicInteger calls = new AtomicInteger();
    final NodePolicy policy =
        new NodePolicy(
            new NodePolicy.Retry(1, Duration.ZERO, Duration.ZERO),
            NodePolicy.OnError.FAIL,
            NodePolicy.JoinRule.ALL_SUCCESS);

    StepVerifier.create(NodePolicies.apply(policy, "n1", input, flaky(calls, 99)))
        .expectError(IllegalStateException.class)
        .verify();

    assertThat(calls).hasValue(1);
  }

  @Test
  void continue_dropsTheFailingMessage() {
    final NodePolicy policy =
        new NodePolicy(null, NodePolicy.OnError.CONTINUE, NodePolicy.JoinRule.ALL_SUCCESS);

    StepVerifier.create(NodePolicies.apply(policy, "n1", input, flaky(new AtomicInteger(), 99)))
        .verifyComplete();
  }

  @Test
  void route_sendsTheFailingMessageToTheErrorPortTaggedWithItsFailure() {
    final NodePolicy policy =
        new NodePolicy(null, NodePolicy.OnError.ROUTE, NodePolicy.JoinRule.ALL_SUCCESS);

    StepVerifier.create(NodePolicies.apply(policy, "n1", input, flaky(new AtomicInteger(), 99)))
        .assertNext(
            routed -> {
              assertThat(routed.sourcePort()).isEqualTo(NodePolicy.ERROR_PORT);
              assertThat(routed.failureReason()).isEqualTo("PROCESSING_FAILED");
              assertThat(routed.payload()).isEqualTo(input.payload());
            })
        .verifyComplete();
  }

  @Test
  void fail_withoutRetry_passesTheErrorThrough() {
    StepVerifier.create(
            NodePolicies.apply(NodePolicy.DEFAULT, "n1", input, flaky(new AtomicInteger(), 99)))
        .expectError(IllegalStateException.class)
        .verify();
  }

  @Test
  void retryThenRoute_routesOnlyAfterRetriesAreUsedUp() {
    final AtomicInteger calls = new AtomicInteger();
    final NodePolicy policy =
        new NodePolicy(RETRY3, NodePolicy.OnError.ROUTE, NodePolicy.JoinRule.ALL_SUCCESS);

    StepVerifier.withVirtualTime(() -> NodePolicies.apply(policy, "n1", input, flaky(calls, 99)))
        .expectSubscription()
        .thenAwait(Duration.ofSeconds(60))
        .assertNext(routed -> assertThat(routed.sourcePort()).isEqualTo("error"))
        .verifyComplete();

    assertThat(calls).hasValue(3);
  }
}
