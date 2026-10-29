// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.stream;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.workflow.ParentEdgeInfo;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import com.infenia.yukta.service.store.InMemoryNodeCheckpointStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

/** Backpressure and throughput checks for the node broadcast pipeline. */
@Slf4j
@NoArgsConstructor
@SuppressWarnings({"PMD.AvoidInstantiatingObjectsInLoops", "PMD.GuardLogStatement"})
class StreamTopologyDecoratorBackpressureTest {

  private static final int MESSAGES = 10_000;

  private final StreamTopologyDecorator decorator =
      new StreamTopologyDecorator(
          Mockito.mock(TaskTrackerService.class), new InMemoryNodeCheckpointStore());

  private static Flux<Message<?>> source(final int count) {
    return Flux.range(0, count)
        .map(i -> (Message<?>) DefaultMessage.create(UUID.randomUUID(), "m" + i));
  }

  /** Builds a linear chain of decorated nodes and connects it the way the compiler does. */
  @SuppressWarnings("unchecked")
  private Flux<Message<?>> linearChain(
      final int nodes, final int bufferSize, final List<Disposable> disposables) {
    final List<Runnable> connectors = new ArrayList<>();
    Flux<Message<?>> stream = source(MESSAGES);
    for (int i = 0; i < nodes; i++) {
      final String nodeId = "node-" + i;
      final Flux<Message<?>> out =
          decorator.applyLoggingAndBroadcasting(
              "exec", nodeId, stream, bufferSize, disposables, connectors);
      // One outgoing edge per node, like the compiler's edge routing.
      stream = decorator.applyEdgeRouting(new Flux[] {out}, new ParentEdgeInfo(0, nodeId, null));
    }
    final Flux<Message<?>> tail = stream;
    // Connectors run when the workflow starts, in reverse order.
    return tail.doOnSubscribe(
        _ -> {
          for (int i = connectors.size() - 1; i >= 0; i--) {
            connectors.get(i).run();
          }
        });
  }

  @Test
  void slowConsumer_doesNotOverflowSmallBuffer() {
    final List<Disposable> disposables = new ArrayList<>();
    final AtomicLong seen = new AtomicLong();

    final Flux<Message<?>> slow =
        linearChain(1, 16, disposables)
            .publishOn(Schedulers.newSingle("slow-consumer"), 1)
            .doOnNext(
                _ -> {
                  seen.incrementAndGet();
                  java.util.concurrent.locks.LockSupport.parkNanos(20_000);
                });

    StepVerifier.create(slow)
        .expectNextCount(MESSAGES)
        .expectComplete()
        .verify(Duration.ofSeconds(20));

    assertThat(seen).hasValue(MESSAGES);
    disposables.forEach(Disposable::dispose);
  }

  @Test
  void twentyNodeChain_throughput() {
    final List<Disposable> disposables = new ArrayList<>();
    final Flux<Message<?>> chain = linearChain(20, 256, disposables);

    final long start = System.nanoTime();
    StepVerifier.create(chain)
        .expectNextCount(MESSAGES)
        .expectComplete()
        .verify(Duration.ofSeconds(20));
    final long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

    log.info(
        "THROUGHPUT nodes=20 messages={} elapsedMs={} msgPerSec={}",
        MESSAGES,
        elapsedMs,
        MESSAGES * 1000L / Math.max(1, elapsedMs));
    disposables.forEach(Disposable::dispose);
  }
}
