// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.workflow.NodePolicy;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

@NoArgsConstructor
@SuppressWarnings({"PMD.CommentRequired", "PMD.TooManyMethods"})
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

  /** Emits {@code partial} messages and then fails on the first {@code failures} calls. */
  private Flux<Message<?>> failsAfterPartialOutput(
      final AtomicInteger calls, final int failures, final int partial) {
    return Flux.defer(
        () -> {
          final Flux<Message<?>> some = Flux.range(0, partial).map(_ -> input);
          return calls.incrementAndGet() <= failures
              ? some.concatWith(Flux.error(new IllegalStateException("boom")))
              : some;
        });
  }

  @Test
  void retry_dropsOutputOfFailedAttempt() {
    final AtomicInteger calls = new AtomicInteger();
    final NodePolicy policy =
        new NodePolicy(RETRY3, NodePolicy.OnError.FAIL, NodePolicy.JoinRule.ALL_SUCCESS);

    StepVerifier.withVirtualTime(
            () ->
                NodePolicies.apply(
                    policy, "n1", input, _ -> failsAfterPartialOutput(calls, 1, 2), () -> {}))
        .thenAwait(Duration.ofSeconds(30))
        .expectNext(input, input)
        .verifyComplete();
  }

  @Test
  void route_sendsOnlyTheDeadLetter_notTheOutputOfTheFailedAttempt() {
    final AtomicInteger calls = new AtomicInteger();
    final NodePolicy policy =
        new NodePolicy(null, NodePolicy.OnError.ROUTE, NodePolicy.JoinRule.ALL_SUCCESS);

    StepVerifier.create(
            NodePolicies.apply(
                policy, "n1", input, _ -> failsAfterPartialOutput(calls, 1, 2), () -> {}))
        .assertNext(dead -> assertThat(dead.sourcePort()).isEqualTo(NodePolicy.ERROR_PORT))
        .verifyComplete();
  }

  @Test
  @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
  void runAborted_isNeverRetried_dropped_orRouted() {
    for (final NodePolicy.OnError onError :
        new NodePolicy.OnError[] {
          NodePolicy.OnError.FAIL, NodePolicy.OnError.CONTINUE, NodePolicy.OnError.ROUTE
        }) {
      final AtomicInteger calls = new AtomicInteger();
      final NodePolicy policy = new NodePolicy(RETRY3, onError, NodePolicy.JoinRule.ALL_SUCCESS);

      StepVerifier.create(
              NodePolicies.apply(
                  policy,
                  "n1",
                  input,
                  _ ->
                      Flux.<Message<?>>defer(
                          () -> {
                            calls.incrementAndGet();
                            return Flux.error(
                                new com.infenia.yukta.plugin.exception.RunAbortedException(
                                    "budget"));
                          }),
                  () -> {}))
          .expectError(com.infenia.yukta.plugin.exception.RunAbortedException.class)
          .verify(Duration.ofSeconds(2));

      assertThat(calls).as(onError.name()).hasValue(1);
    }
  }

  @Test
  void retry_succeedsOnLaterAttempt_afterBackoff() {
    final AtomicInteger calls = new AtomicInteger();
    final NodePolicy policy =
        new NodePolicy(RETRY3, NodePolicy.OnError.FAIL, NodePolicy.JoinRule.ALL_SUCCESS);

    StepVerifier.withVirtualTime(
            () -> NodePolicies.apply(policy, "n1", input, _ -> flaky(calls, 2), () -> {}))
        .expectSubscription()
        .expectNoEvent(Duration.ofMillis(400))
        .thenAwait(Duration.ofSeconds(30))
        .expectNext(input)
        .verifyComplete();

    assertThat(calls).hasValue(3);
  }

  @Test
  void retry_countsEveryAttemptAsActivity() {
    final AtomicInteger calls = new AtomicInteger();
    final AtomicInteger attempts = new AtomicInteger();
    final NodePolicy policy =
        new NodePolicy(RETRY3, NodePolicy.OnError.FAIL, NodePolicy.JoinRule.ALL_SUCCESS);

    StepVerifier.withVirtualTime(
            () ->
                NodePolicies.apply(
                    policy, "n1", input, _ -> flaky(calls, 2), attempts::incrementAndGet))
        .thenAwait(Duration.ofSeconds(30))
        .expectNext(input)
        .verifyComplete();

    assertThat(attempts).hasValue(3);
  }

  @Test
  void retry_doesNotRepeatAnInvalidInput() {
    final AtomicInteger calls = new AtomicInteger();
    final NodePolicy policy =
        new NodePolicy(RETRY3, NodePolicy.OnError.FAIL, NodePolicy.JoinRule.ALL_SUCCESS);
    final Flux<Message<?>> invalid =
        Flux.defer(
            () -> {
              calls.incrementAndGet();
              return Flux.<Message<?>>error(new IllegalArgumentException("bad input"));
            });

    StepVerifier.create(NodePolicies.apply(policy, "n1", input, _ -> invalid, () -> {}))
        .expectError(IllegalArgumentException.class)
        .verify();

    assertThat(calls).hasValue(1);
  }

  @Test
  void retry_exhausted_failsWithTheOriginalError() {
    final AtomicInteger calls = new AtomicInteger();
    final NodePolicy policy =
        new NodePolicy(RETRY3, NodePolicy.OnError.FAIL, NodePolicy.JoinRule.ALL_SUCCESS);

    StepVerifier.withVirtualTime(
            () -> NodePolicies.apply(policy, "n1", input, _ -> flaky(calls, 99), () -> {}))
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

    StepVerifier.create(NodePolicies.apply(policy, "n1", input, _ -> flaky(calls, 99), () -> {}))
        .expectError(IllegalStateException.class)
        .verify();

    assertThat(calls).hasValue(1);
  }

  @Test
  void continue_dropsTheFailingMessage() {
    final NodePolicy policy =
        new NodePolicy(null, NodePolicy.OnError.CONTINUE, NodePolicy.JoinRule.ALL_SUCCESS);

    StepVerifier.create(
            NodePolicies.apply(policy, "n1", input, _ -> flaky(new AtomicInteger(), 99), () -> {}))
        .verifyComplete();
  }

  @Test
  void route_sendsTheFailingMessageToTheErrorPortTaggedWithItsFailure() {
    final NodePolicy policy =
        new NodePolicy(null, NodePolicy.OnError.ROUTE, NodePolicy.JoinRule.ALL_SUCCESS);

    StepVerifier.create(
            NodePolicies.apply(policy, "n1", input, _ -> flaky(new AtomicInteger(), 99), () -> {}))
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
            NodePolicies.apply(
                NodePolicy.DEFAULT, "n1", input, _ -> flaky(new AtomicInteger(), 99), () -> {}))
        .expectError(IllegalStateException.class)
        .verify();
  }

  @Test
  void retryThenRoute_routesOnlyAfterRetriesAreUsedUp() {
    final AtomicInteger calls = new AtomicInteger();
    final NodePolicy policy =
        new NodePolicy(RETRY3, NodePolicy.OnError.ROUTE, NodePolicy.JoinRule.ALL_SUCCESS);

    StepVerifier.withVirtualTime(
            () -> NodePolicies.apply(policy, "n1", input, _ -> flaky(calls, 99), () -> {}))
        .expectSubscription()
        .thenAwait(Duration.ofSeconds(60))
        .assertNext(routed -> assertThat(routed.sourcePort()).isEqualTo("error"))
        .verifyComplete();

    assertThat(calls).hasValue(3);
  }

  @Test
  void eachAttempt_carriesItsNumberInTheAttemptHeader() {
    final java.util.List<Object> seen = new java.util.ArrayList<>();
    final AtomicInteger calls = new AtomicInteger();
    final NodePolicy policy =
        new NodePolicy(RETRY3, NodePolicy.OnError.FAIL, NodePolicy.JoinRule.ALL_SUCCESS);

    StepVerifier.withVirtualTime(
            () ->
                NodePolicies.apply(
                    policy,
                    "n1",
                    input,
                    attempt -> {
                      seen.add(attempt.metadata().get(NodePolicies.ATTEMPT_HEADER));
                      return flaky(calls, 2);
                    },
                    () -> {}))
        .expectSubscription()
        .thenAwait(Duration.ofSeconds(60))
        .expectNextCount(1)
        .verifyComplete();

    assertThat(seen).containsExactly(1, 2, 3);
  }

  @Test
  void elementTimeout_failsSlowMessage_soRetryOrRouteApply() {
    final NodePolicy policy =
        new NodePolicy(
            null, NodePolicy.OnError.ROUTE, NodePolicy.JoinRule.ALL_SUCCESS, Duration.ofSeconds(5));

    StepVerifier.withVirtualTime(
            () -> NodePolicies.apply(policy, "n1", input, _ -> Flux.never(), () -> {}))
        .expectSubscription()
        .thenAwait(Duration.ofSeconds(6))
        .assertNext(routed -> assertThat(routed.sourcePort()).isEqualTo("error"))
        .verifyComplete();
  }

  private static NodePolicy.Retry retryOnly(
      final java.util.Set<String> listed, final java.util.Set<String> excluded) {
    return new NodePolicy.Retry(3, Duration.ofMillis(10), Duration.ofMillis(10), listed, excluded);
  }

  private Flux<Message<?>> failing(final AtomicInteger calls, final Throwable error) {
    return Flux.defer(
        () -> {
          calls.incrementAndGet();
          return Flux.<Message<?>>error(error);
        });
  }

  @Test
  void retryLists_matchRemoteClassNames() {
    final com.infenia.yukta.plugin.exception.RemoteFailureException remote =
        new com.infenia.yukta.plugin.exception.RemoteFailureException(
            "w1 failed",
            java.util.Set.of(
                "java.net.SocketTimeoutException", "java.io.IOException", "a.B$Inner"));
    final Throwable wrapped = new IllegalStateException("wrapped", remote);

    assertThat(retryOnly(java.util.Set.of("IOException"), java.util.Set.of()).shouldRetry(wrapped))
        .isTrue();
    assertThat(retryOnly(java.util.Set.of(), java.util.Set.of("Inner")).shouldRetry(remote))
        .isFalse();
    assertThat(
            retryOnly(java.util.Set.of(), java.util.Set.of("java.io.IOException"))
                .shouldRetry(remote))
        .isFalse();
    assertThat(retryOnly(java.util.Set.of("SQLException"), java.util.Set.of()).shouldRetry(remote))
        .isFalse();
  }

  @Test
  void retryOn_retriesOnlyListedExceptions_bySimpleOrFullName() {
    final AtomicInteger ioCalls = new AtomicInteger();
    final AtomicInteger other = new AtomicInteger();
    final NodePolicy policy =
        new NodePolicy(
            retryOnly(java.util.Set.of("java.io.IOException"), java.util.Set.of()),
            NodePolicy.OnError.FAIL,
            NodePolicy.JoinRule.ALL_SUCCESS);

    StepVerifier.create(
            NodePolicies.apply(
                policy,
                "n",
                input,
                _ -> failing(ioCalls, new java.io.FileNotFoundException("x")),
                () -> {}))
        .expectError(java.io.FileNotFoundException.class)
        .verify(Duration.ofSeconds(5));
    StepVerifier.create(
            NodePolicies.apply(
                policy, "n", input, _ -> failing(other, new IllegalStateException("x")), () -> {}))
        .expectError(IllegalStateException.class)
        .verify(Duration.ofSeconds(5));

    assertThat(ioCalls).hasValue(3); // a subclass of a listed type counts
    assertThat(other).hasValue(1);
  }

  @Test
  void noRetryOn_stopsRetryingThatException_evenWhenItIsCause() {
    final AtomicInteger calls = new AtomicInteger();
    final NodePolicy policy =
        new NodePolicy(
            retryOnly(java.util.Set.of(), java.util.Set.of("SecurityException")),
            NodePolicy.OnError.FAIL,
            NodePolicy.JoinRule.ALL_SUCCESS);
    final Throwable wrapped = new RuntimeException("wrapper", new SecurityException("denied"));

    StepVerifier.create(
            NodePolicies.apply(policy, "n", input, _ -> failing(calls, wrapped), () -> {}))
        .expectError(RuntimeException.class)
        .verify(Duration.ofSeconds(5));

    assertThat(calls).hasValue(1);
  }

  @Test
  void illegalArgument_isRetriedOnlyWhenListedInRetryOn() {
    final AtomicInteger calls = new AtomicInteger();
    final NodePolicy policy =
        new NodePolicy(
            retryOnly(java.util.Set.of("IllegalArgumentException"), java.util.Set.of()),
            NodePolicy.OnError.FAIL,
            NodePolicy.JoinRule.ALL_SUCCESS);

    StepVerifier.create(
            NodePolicies.apply(
                policy,
                "n",
                input,
                _ -> failing(calls, new IllegalArgumentException("x")),
                () -> {}))
        .expectError(IllegalArgumentException.class)
        .verify(Duration.ofSeconds(5));

    assertThat(calls).hasValue(3);
  }

  @Test
  void routedMessage_carriesAttemptsExceptionAndTime() {
    final AtomicInteger calls = new AtomicInteger();
    final NodePolicy policy =
        new NodePolicy(
            retryOnly(java.util.Set.of(), java.util.Set.of()),
            NodePolicy.OnError.ROUTE,
            NodePolicy.JoinRule.ALL_SUCCESS);

    StepVerifier.create(
            NodePolicies.apply(
                policy, "n1", input, _ -> failing(calls, new IllegalStateException("x")), () -> {}))
        .assertNext(
            routed -> {
              assertThat(routed.sourcePort()).isEqualTo("error");
              assertThat(routed.origDest()).isEqualTo("n1");
              assertThat(routed.metadata())
                  .containsEntry(NodePolicies.FAILURE_ATTEMPTS_HEADER, 3)
                  .containsEntry(
                      NodePolicies.FAILURE_EXCEPTION_HEADER, "java.lang.IllegalStateException");
              assertThat(
                      java.time.Instant.parse(
                          (String) routed.metadata().get(NodePolicies.FAILURE_TIME_HEADER)))
                  .isNotNull();
            })
        .verifyComplete();
  }

  @Test
  void failureBudget_failsTheNodeOnceTooManyMessagesFailed() {
    final NodePolicy policy =
        new NodePolicy(
            null,
            NodePolicy.OnError.CONTINUE,
            NodePolicy.JoinRule.ALL_SUCCESS,
            null,
            null,
            new NodePolicy.FailureBudget(2, Duration.ofMinutes(1)));
    final FailureLedger ledger = new FailureLedger(policy.failureBudget());
    final AtomicInteger calls = new AtomicInteger();

    final Flux<Message<?>> run =
        Flux.range(0, 5)
            .concatMap(
                _ ->
                    NodePolicies.apply(
                        policy,
                        "n",
                        input,
                        _ -> failing(calls, new IllegalStateException("x")),
                        () -> {},
                        ledger));

    StepVerifier.create(run)
        .expectErrorSatisfies(e -> assertThat(e).hasMessageContaining("failed more than 2 times"))
        .verify(Duration.ofSeconds(5));

    assertThat(calls).hasValue(3); // the third failure is the one over budget
  }

  @Test
  void failureBudget_doesNotCountFailuresOutsideTheWindow() throws InterruptedException {
    final FailureLedger ledger =
        new FailureLedger(new NodePolicy.FailureBudget(1, Duration.ofMillis(50)));

    assertThat(ledger.record()).isFalse();
    Thread.sleep(120);
    assertThat(ledger.record()).isFalse();
    assertThat(ledger.record()).isTrue();
  }

  @Test
  void failureBudget_alsoStopsRoutingOnceExceeded() {
    final NodePolicy policy =
        new NodePolicy(
            null,
            NodePolicy.OnError.ROUTE,
            NodePolicy.JoinRule.ALL_SUCCESS,
            null,
            null,
            new NodePolicy.FailureBudget(1, Duration.ofMinutes(1)));
    final FailureLedger ledger = new FailureLedger(policy.failureBudget());
    final AtomicInteger calls = new AtomicInteger();

    final Flux<Message<?>> run =
        Flux.range(0, 3)
            .concatMap(
                _ ->
                    NodePolicies.apply(
                        policy,
                        "n",
                        input,
                        _ -> failing(calls, new IllegalStateException("x")),
                        () -> {},
                        ledger));

    StepVerifier.create(run)
        .expectNextCount(1)
        .expectErrorSatisfies(e -> assertThat(e).hasMessageContaining("failed more than 1"))
        .verify(Duration.ofSeconds(5));
  }

  @Test
  void edgeBuffered_withoutBuffer_passesInputThrough() {
    final Flux<Message<?>> input = Flux.just(DefaultMessage.create(UUID.randomUUID(), "a"));

    assertThat(NodePolicies.edgeBuffered(null, input, mock(TaskTrackerService.class), "T"))
        .isSameAs(input);
  }

  private static void emitTen(final Sinks.Many<Message<?>> sink) {
    for (int i = 0; i < 10; i++) {
      sink.tryEmitNext(DefaultMessage.create(UUID.randomUUID(), i));
    }
    sink.tryEmitComplete();
  }

  @Test
  void edgeBuffered_dropsOldestOrNewest_andCountsEveryDrop() {
    final TaskTrackerService tracker = mock(TaskTrackerService.class);
    final Sinks.Many<Message<?>> oldest = Sinks.many().unicast().onBackpressureBuffer();
    final Sinks.Many<Message<?>> newest = Sinks.many().unicast().onBackpressureBuffer();

    StepVerifier.create(
            NodePolicies.edgeBuffered(
                new NodePolicy.EdgeBuffer(3, NodePolicy.Overflow.DROP_OLDEST),
                oldest.asFlux(),
                tracker,
                "T"),
            0)
        .then(() -> emitTen(oldest))
        .thenRequest(Long.MAX_VALUE)
        .expectNextMatches(m -> Integer.valueOf(7).equals(m.payload()))
        .expectNextCount(2)
        .verifyComplete();
    StepVerifier.create(
            NodePolicies.edgeBuffered(
                new NodePolicy.EdgeBuffer(3, NodePolicy.Overflow.DROP_NEWEST),
                newest.asFlux(),
                tracker,
                "T"),
            0)
        .then(() -> emitTen(newest))
        .thenRequest(Long.MAX_VALUE)
        .expectNextMatches(m -> Integer.valueOf(0).equals(m.payload()))
        .expectNextCount(2)
        .verifyComplete();

    verify(tracker, times(14)).recordEdgeDropped("T");
  }
}
