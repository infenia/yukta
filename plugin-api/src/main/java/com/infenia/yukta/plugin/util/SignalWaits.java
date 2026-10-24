// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.util;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * The waits of one plugin for a named signal (an approval, a decision) per execution and node. A
 * plugin calls {@link #await} where it must hold, and {@link #deliver} from its {@code
 * onControlSignal}; the signal command ({@code signal_node}, REST, CLI) reaches it through the same
 * review and audit as any other command. The waits are in memory.
 *
 * <p>A signal that arrives before its wait is kept for {@link #EARLY_TTL} (at most {@link
 * #EARLY_CAP} at a time) and answered {@link #QUEUED}; the wait that follows takes it at once. A
 * signal that follows an accepted one on the same key within {@link #DUPLICATE_WINDOW} is taken for
 * a double submit and answered {@link #NOT_WAITING}, so it cannot be kept for a later wait.
 */
@SuppressWarnings({"PMD.OnlyOneReturn", "PMD.TooManyMethods"})
@SuppressFBWarnings(
    value = "SING_SINGLETON_HAS_NONPRIVATE_CONSTRUCTOR",
    justification = "shared() is a convenience; tests and plugins may still own an instance")
public final class SignalWaits {

  /** Answer to a signal that a waiting node took. */
  public static final String ACCEPTED = "ACCEPTED";

  /** Answer to a signal when the node is not waiting for it. */
  public static final String NOT_WAITING = "NOT_WAITING";

  /** Answer to a signal that came before its wait and was kept for it. */
  public static final String QUEUED = "QUEUED";

  /** How long a signal that came early is kept. */
  public static final Duration EARLY_TTL = Duration.ofMinutes(10);

  /** How long after an accepted signal another one on the same key counts as a duplicate. */
  public static final Duration DUPLICATE_WINDOW = Duration.ofSeconds(5);

  /** Most early signals kept at once; further ones are answered {@link #NOT_WAITING}. */
  public static final int EARLY_CAP = 100;

  /** The instance the engine's plugins share, so one list shows every wait. */
  private static final SignalWaits INSTANCE = new SignalWaits();

  /** Tells the time, for the age of a signal that came early. */
  private final Clock clock;

  /** Waits by execution, node and signal name; waits on one key are answered oldest first. */
  private final Map<String, Deque<Waiter>> waiting = new ConcurrentHashMap<>();

  /** Signals that came before their wait, by the same key. */
  private final Map<String, Early> early = new ConcurrentHashMap<>();

  /** When a signal was last accepted, by the same key. */
  private final Map<String, Instant> accepted = new ConcurrentHashMap<>();

  /** Keeps the capacity check and the put of {@link #early} together. */
  private final ReentrantLock earlyLock = new ReentrantLock();

  /**
   * A wait; {@code open} is cleared when it ends (timeout, cancel) so no signal is handed to it.
   */
  private record Waiter(Sinks.One<Decision> sink, Instant since, AtomicBoolean open) {}

  private record Early(Decision decision, Instant stored) {}

  /**
   * A wait that is open now.
   *
   * @param executionId the execution
   * @param nodeId the node that waits
   * @param name the signal name it waits for
   * @param since when the wait began
   */
  public record Pending(String executionId, String nodeId, String name, Instant since) {}

  /** Creates waits that tell the time by the system clock. */
  public SignalWaits() {
    this(Clock.systemUTC());
  }

  /* default */ SignalWaits(final Clock clock) {
    this.clock = clock;
  }

  /**
   * The instance shared by the plugins of the engine.
   *
   * @return the shared waits
   */
  public static SignalWaits shared() {
    return INSTANCE;
  }

  /**
   * The waits that are open now, oldest first.
   *
   * @return one entry per open wait
   */
  public List<Pending> pending() {
    final List<Pending> all = new ArrayList<>();
    waiting.forEach(
        (key, queue) -> {
          final String[] parts = key.split("\0", 3);
          queue.forEach(w -> all.add(new Pending(parts[0], parts[1], parts[2], w.since())));
        });
    all.sort(Comparator.comparing(Pending::since));
    return all;
  }

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
          final Sinks.One<Decision> sink = Sinks.one();
          final Waiter waiter = new Waiter(sink, Instant.now(clock), new AtomicBoolean(true));
          final AtomicBoolean oldest = new AtomicBoolean();
          waiting.compute(
              key,
              (_, queue) -> {
                final Deque<Waiter> waits = queue == null ? new ArrayDeque<>() : queue;
                oldest.set(waits.isEmpty());
                waits.add(waiter);
                return waits;
              });
          // After registering, so a signal that came in between is not lost. Only the oldest wait
          // takes a signal that came early: an older one that is still open has the right to it.
          final Decision sent = oldest.get() ? takeEarly(key) : null;
          if (sent != null) {
            waiter.open().set(false); // answered: a later signal must not be handed to this wait
            sink.tryEmitValue(sent);
          }
          return sink.asMono()
              .timeout(timeout)
              .doOnError(_ -> waiter.open().set(false))
              .doOnCancel(() -> waiter.open().set(false))
              .doFinally(_ -> forget(key, waiter));
        });
  }

  /**
   * Hand a signal to the wait it names.
   *
   * @param signal a message whose payload is a map with {@code executionId}, {@code nodeId}, {@code
   *     name}, {@code decision} and {@code data}
   * @return {@link #ACCEPTED}, {@link #QUEUED} or {@link #NOT_WAITING} as the payload; empty if the
   *     payload is not a map
   */
  public Mono<Message<?>> deliver(final Message<?> signal) {
    if (!(signal.payload() instanceof Map<?, ?> body)) {
      return Mono.empty();
    }
    final String executionId = text(body.get("executionId"));
    final String nodeId = text(body.get("nodeId"));
    final String name = text(body.get("name"));
    if (executionId.isBlank() || nodeId.isBlank() || name.isBlank()) {
      // A signal that names no wait must not take one of the early slots under a made-up key.
      return Mono.just(DefaultMessage.create(UUID.randomUUID(), NOT_WAITING));
    }
    final String key = key(executionId, nodeId, name);
    final Decision decision =
        new Decision(
            String.valueOf(body.get("decision")),
            body.get("data") == null ? "" : String.valueOf(body.get("data")));
    final String answer;
    if (handToWaiter(key, decision)) {
      answer = ACCEPTED;
      final Instant now = Instant.now(clock);
      accepted.values().removeIf(t -> t.plus(DUPLICATE_WINDOW).isBefore(now));
      accepted.put(key, now);
    } else if (isDuplicate(key)) {
      answer = NOT_WAITING;
    } else {
      answer = keepEarly(key, decision) ? QUEUED : NOT_WAITING;
    }
    return Mono.just(DefaultMessage.create(UUID.randomUUID(), answer));
  }

  private static String text(final Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  /**
   * Gives the decision to the oldest wait that can still take it. A wait that has just timed out,
   * been cancelled or already took an early signal is closed but may not have left the queue yet:
   * it is skipped, so the caller is not told ACCEPTED for a decision nobody received. Closing the
   * wait is atomic, so exactly one caller gets to emit to its sink.
   */
  private boolean handToWaiter(final String key, final Decision decision) {
    Waiter waiter = next(key);
    while (waiter != null && !waiter.open().getAndSet(false)) {
      waiter = next(key);
    }
    if (waiter != null) {
      waiter.sink().tryEmitValue(decision);
    }
    return waiter != null;
  }

  private boolean isDuplicate(final String key) {
    final Instant last = accepted.get(key);
    return last != null && !last.plus(DUPLICATE_WINDOW).isBefore(Instant.now(clock));
  }

  private boolean keepEarly(final String key, final Decision decision) {
    final Instant now = Instant.now(clock);
    earlyLock.lock(); // the size check and the put must not interleave
    try {
      early.values().removeIf(e -> e.stored().plus(EARLY_TTL).isBefore(now));
      final boolean room = early.size() < EARLY_CAP || early.containsKey(key);
      if (room) {
        early.put(key, new Early(decision, now));
      }
      return room;
    } finally {
      earlyLock.unlock();
    }
  }

  private Decision takeEarly(final String key) {
    final Early kept = early.remove(key);
    return kept == null || kept.stored().plus(EARLY_TTL).isBefore(Instant.now(clock))
        ? null
        : kept.decision();
  }

  /** The oldest wait on the key, taken off its queue, or null. */
  private Waiter next(final String key) {
    final AtomicReference<Waiter> oldest = new AtomicReference<>();
    waiting.computeIfPresent(
        key,
        (_, queue) -> {
          oldest.set(queue.poll());
          return queue.isEmpty() ? null : queue;
        });
    return oldest.get();
  }

  private void forget(final String key, final Waiter waiter) {
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
