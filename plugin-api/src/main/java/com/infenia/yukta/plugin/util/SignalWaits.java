// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.util;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * The waits of one plugin for a named signal (an approval, a decision) per execution and node. A
 * plugin calls {@link #await} where it must hold, and {@link #deliver} from its {@code
 * onControlSignal}; the signal command ({@code signal_node}, REST, CLI) reaches it through the same
 * review and audit as any other command. The waits are in memory.
 */
@SuppressWarnings({"PMD.AtLeastOneConstructor", "PMD.OnlyOneReturn"})
public final class SignalWaits {

  /** Answer to a signal that a waiting node took. */
  public static final String ACCEPTED = "ACCEPTED";

  /** Answer to a signal when the node is not waiting for it. */
  public static final String NOT_WAITING = "NOT_WAITING";

  /** Waits by execution, node and signal name; waits on one key are answered oldest first. */
  private final Map<String, Deque<Sinks.One<Decision>>> waiting = new ConcurrentHashMap<>();

  /**
   * What the sender decided.
   *
   * @param decision the decision text, e.g. {@code approve} or {@code reject}
   * @param data free text that came with it
   */
  public record Decision(String decision, String data) {}

  /**
   * Wait for a signal.
   *
   * @param executionId the execution
   * @param nodeId the node that waits
   * @param name the signal name
   * @param timeout the longest wait
   * @return the decision; fails with a {@link java.util.concurrent.TimeoutException} when none
   *     arrives in time
   */
  public Mono<Decision> await(
      final String executionId, final String nodeId, final String name, final Duration timeout) {
    return Mono.defer(
        () -> {
          final String key = key(executionId, nodeId, name);
          final Sinks.One<Decision> waiter = Sinks.one();
          waiting.compute(
              key,
              (_, queue) -> {
                final Deque<Sinks.One<Decision>> waits = queue == null ? new ArrayDeque<>() : queue;
                waits.add(waiter);
                return waits;
              });
          return waiter.asMono().timeout(timeout).doFinally(_ -> forget(key, waiter));
        });
  }

  /**
   * Hand a signal to the wait it names.
   *
   * @param signal a message whose payload is a map with {@code executionId}, {@code nodeId}, {@code
   *     name}, {@code decision} and {@code data}
   * @return {@link #ACCEPTED} or {@link #NOT_WAITING} as the payload; empty if the payload is not a
   *     map
   */
  public Mono<Message<?>> deliver(final Message<?> signal) {
    if (!(signal.payload() instanceof Map<?, ?> body)) {
      return Mono.empty();
    }
    final String key =
        key(
            String.valueOf(body.get("executionId")),
            String.valueOf(body.get("nodeId")),
            String.valueOf(body.get("name")));
    final Decision decision =
        new Decision(
            String.valueOf(body.get("decision")),
            body.get("data") == null ? "" : String.valueOf(body.get("data")));
    final Sinks.One<Decision> waiter = next(key);
    final boolean taken = waiter != null;
    if (taken) {
      waiter.tryEmitValue(decision); // a fresh sink; only this call ever emits to it
    }
    return Mono.just(DefaultMessage.create(UUID.randomUUID(), taken ? ACCEPTED : NOT_WAITING));
  }

  /** The oldest wait on the key, taken off its queue, or null. */
  private Sinks.One<Decision> next(final String key) {
    final AtomicReference<Sinks.One<Decision>> oldest = new AtomicReference<>();
    waiting.computeIfPresent(
        key,
        (_, queue) -> {
          oldest.set(queue.poll());
          return queue.isEmpty() ? null : queue;
        });
    return oldest.get();
  }

  private void forget(final String key, final Sinks.One<Decision> waiter) {
    waiting.computeIfPresent(
        key,
        (_, queue) -> {
          queue.remove(waiter);
          return queue.isEmpty() ? null : queue;
        });
  }

  private static String key(final String executionId, final String nodeId, final String name) {
    return executionId + "\0" + nodeId + "\0" + name;
  }
}
