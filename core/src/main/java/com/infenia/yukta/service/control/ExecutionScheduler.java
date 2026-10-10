// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Admission control for workflow executions.
 *
 * <p>Optionally limits how many executions run at once, overall and per workflow. A start that does
 * not fit either waits in a bounded queue ({@code QUEUE}) or is refused ({@code REJECT}). With no
 * limit configured (the default) every start is admitted at once. On shutdown no new execution is
 * admitted, queued ones are refused, and running ones are given time to finish.
 *
 * <p>Waiting starts are admitted by priority (a payload key {@value #PRIORITY_KEY}, higher first,
 * default 0), then in arrival order. The limits can be changed while running with {@link
 * #setLimits}; raising them admits waiting starts at once.
 */
@Slf4j
@Component
@SuppressWarnings({"PMD.AvoidSynchronizedStatement", "PMD.TooManyMethods", "PMD.OnlyOneReturn"})
public class ExecutionScheduler implements SmartLifecycle {

  /** Start payload key that sets the priority of a start. */
  public static final String PRIORITY_KEY = "yukta.priority";

  /** Global limit; 0 means unlimited. Changed at run time by {@link #setLimits}. */
  private int maxConcurrent;

  /** Limit per workflow; 0 means unlimited. Changed at run time by {@link #setLimits}. */
  private int maxPerWorkflow;

  /** How many starts may wait. */
  private final int queueCapacity;

  /** Behaviour when a start does not fit. */
  private final OnLimit onLimit;

  /** How long running executions are given on shutdown. */
  private final Duration shutdownTimeout;

  /** Guards all state below. */
  private final Object lock = new Object();

  /** Starts waiting for a slot: highest priority first, oldest first within a priority. */
  private final List<Waiter> queue = new ArrayList<>();

  /** Running executions per workflow. */
  private final Map<String, Integer> runningPerKey = new ConcurrentHashMap<>();

  /** Signals that the last running execution has finished. */
  private final Sinks.Empty<Void> drained = Sinks.empty();

  /** Total running executions. */
  private int runningTotal;

  /** True from the start of shutdown. */
  private boolean closed;

  /** True while the lifecycle is started. */
  private boolean started;

  /**
   * Creates the scheduler.
   *
   * @param maxConcurrent global limit, 0 for none
   * @param maxPerWorkflow per-workflow limit, 0 for none
   * @param queueCapacity how many starts may wait
   * @param onLimit what to do with a start that does not fit
   * @param shutdownTimeout how long running executions are given on shutdown
   */
  public ExecutionScheduler(
      @Value("${yukta.execution.max-concurrent:0}") final int maxConcurrent,
      @Value("${yukta.execution.max-concurrent-per-workflow:0}") final int maxPerWorkflow,
      @Value("${yukta.execution.queue-capacity:100}") final int queueCapacity,
      @Value("${yukta.execution.on-limit:QUEUE}") final OnLimit onLimit,
      @Value("${yukta.execution.shutdown-timeout:30s}") final Duration shutdownTimeout) {
    this.maxConcurrent = Math.max(0, maxConcurrent);
    this.maxPerWorkflow = Math.max(0, maxPerWorkflow);
    this.queueCapacity = Math.max(0, queueCapacity);
    this.onLimit = onLimit;
    this.shutdownTimeout = shutdownTimeout;
  }

  /**
   * Refuses a start that could not be admitted or queued, so the caller finds out immediately.
   *
   * @param sessionId the session
   * @param workflowId the workflow
   * @throws ExecutionRejectedException if the start would be refused
   */
  public void assertCanAccept(final String sessionId, final String workflowId) {
    synchronized (lock) {
      if (closed) {
        throw new ExecutionRejectedException("The server is shutting down");
      }
      if (!fits(key(sessionId, workflowId))
          && (onLimit == OnLimit.REJECT || queue.size() >= queueCapacity)) {
        throw new ExecutionRejectedException(
            onLimit == OnLimit.REJECT
                ? "Execution limit reached"
                : "Execution limit reached and the queue is full");
      }
    }
  }

  /**
   * Asks for an execution slot.
   *
   * @param sessionId the session
   * @param workflowId the workflow
   * @return the slot at once if there is room, otherwise a Mono that waits for one; errors with
   *     {@link ExecutionRejectedException} if the start has to be refused
   */
  public Admission acquire(final String sessionId, final String workflowId) {
    return acquire(sessionId, workflowId, 0);
  }

  /**
   * Asks for an execution slot with a priority.
   *
   * @param sessionId the session
   * @param workflowId the workflow
   * @param priority higher is admitted first when starts have to wait
   * @return the slot at once if there is room, otherwise a Mono that waits for one
   */
  public Admission acquire(final String sessionId, final String workflowId, final int priority) {
    final String key = key(sessionId, workflowId);
    synchronized (lock) {
      if (closed) {
        return refused("The server is shutting down");
      }
      if (fits(key)) {
        return new Admission(true, Mono.just(take(key)));
      }
      if (onLimit == OnLimit.REJECT || queue.size() >= queueCapacity) {
        return refused("Execution limit reached");
      }
      final Waiter waiter = new Waiter(key, priority, Sinks.one(), new AtomicReference<>());
      int index = queue.size();
      while (index > 0 && queue.get(index - 1).priority() < priority) {
        index--;
      }
      queue.add(index, waiter);
      return new Admission(false, waiter.grant().asMono().doOnCancel(() -> abandon(waiter)));
    }
  }

  /**
   * Number of executions currently holding a slot.
   *
   * @return the running count
   */
  public int running() {
    synchronized (lock) {
      return runningTotal;
    }
  }

  /**
   * Number of starts waiting for a slot.
   *
   * @return the queue length
   */
  public int queued() {
    synchronized (lock) {
      return queue.size();
    }
  }

  /**
   * Changes the limits while running. Raising them admits waiting starts at once; lowering them
   * lets running executions finish.
   *
   * @param maxConcurrent global limit, 0 for none
   * @param maxPerWorkflow per-workflow limit, 0 for none
   */
  public void setLimits(final int maxConcurrent, final int maxPerWorkflow) {
    final List<Waiter> admitted;
    synchronized (lock) {
      this.maxConcurrent = Math.max(0, maxConcurrent);
      this.maxPerWorkflow = Math.max(0, maxPerWorkflow);
      admitted = admitWaiting();
    }
    grant(admitted);
  }

  /**
   * Reads the priority of a start from its payload.
   *
   * @param payload the start payload
   * @return the priority, 0 if absent or not a number
   */
  public static int priorityOf(final Map<String, Object> payload) {
    return payload != null && payload.get(PRIORITY_KEY) instanceof Number number
        ? number.intValue()
        : 0;
  }

  private static String key(final String sessionId, final String workflowId) {
    return sessionId + "\0" + workflowId;
  }

  private Admission refused(final String reason) {
    return new Admission(true, Mono.error(new ExecutionRejectedException(reason)));
  }

  private boolean fits(final String key) {
    return (maxConcurrent == 0 || runningTotal < maxConcurrent)
        && (maxPerWorkflow == 0 || runningPerKey.getOrDefault(key, 0) < maxPerWorkflow);
  }

  private Permit take(final String key) {
    runningTotal++;
    runningPerKey.merge(key, 1, Integer::sum);
    return new Permit(key);
  }

  private void free(final String key) {
    final List<Waiter> admitted;
    synchronized (lock) {
      runningTotal--;
      runningPerKey.computeIfPresent(key, (_, count) -> count > 1 ? count - 1 : null);
      admitted = admitWaiting();
      if (closed && runningTotal == 0) {
        drained.tryEmitEmpty();
      }
    }
    grant(admitted);
  }

  /**
   * Grants slots to waiting starts, highest priority first, skipping ones whose workflow is at its
   * limit. Called with the lock held; the caller hands the slots over with {@link #grant} once it
   * has let go of the lock.
   *
   * @return the starts that now hold a slot
   */
  private List<Waiter> admitWaiting() {
    final List<Waiter> admitted = new ArrayList<>();
    final var iterator = queue.iterator();
    while (iterator.hasNext()) {
      final Waiter waiter = iterator.next();
      if (fits(waiter.key())) {
        iterator.remove();
        waiter.granted().set(take(waiter.key()));
        admitted.add(waiter);
      }
    }
    return admitted;
  }

  /**
   * Tells the starts that were admitted. A subscriber runs here, on the caller's thread, and may
   * start another execution, so this must happen outside the lock.
   */
  private static void grant(final List<Waiter> admitted) {
    admitted.forEach(waiter -> waiter.grant().tryEmitValue(waiter.granted().get()));
  }

  /** Called when a waiting start is cancelled; gives back a slot it was granted in the meantime. */
  private void abandon(final Waiter waiter) {
    synchronized (lock) {
      if (queue.remove(waiter)) {
        return;
      }
    }
    // Granted between the cancel and now (or never granted, if shutdown refused it).
    Optional.ofNullable(waiter.granted().get()).ifPresent(Permit::release);
  }

  @Override
  public void start() {
    synchronized (lock) {
      started = true;
    }
  }

  @Override
  public void stop() {
    stop(() -> {});
  }

  @Override
  public void stop(final Runnable callback) {
    synchronized (lock) {
      closed = true;
      started = false;
      queue.forEach(
          waiter ->
              waiter
                  .grant()
                  .tryEmitError(new ExecutionRejectedException("The server is shutting down")));
      queue.clear();
      if (runningTotal == 0) {
        callback.run();
        return;
      }
      log.atInfo()
          .addKeyValue("running", runningTotal)
          .addKeyValue("timeout", shutdownTimeout)
          .log("Waiting for running executions to finish");
    }
    drained
        .asMono()
        .timeout(shutdownTimeout)
        .onErrorResume(
            _ -> {
              log.atWarn().addKeyValue("running", running()).log("Shutdown timeout, giving up");
              return Mono.empty();
            })
        .doFinally(_ -> callback.run())
        .subscribe();
  }

  @Override
  public boolean isRunning() {
    synchronized (lock) {
      return started;
    }
  }

  @Override
  public int getPhase() {
    return Integer.MAX_VALUE - 100;
  }

  /** What to do with a start that does not fit within the limits. */
  public enum OnLimit {
    QUEUE,
    REJECT
  }

  /** A claim on one execution slot; release it when the execution ends. */
  public final class Permit {
    /** The workflow the slot was taken for. */
    private final String key;

    /** Makes releasing idempotent. */
    private final AtomicBoolean released = new AtomicBoolean();

    private Permit(final String key) {
      this.key = key;
    }

    /** Gives the slot back and admits the next waiting start that fits. */
    public void release() {
      if (released.compareAndSet(false, true)) {
        free(key);
      }
    }
  }

  /**
   * Outcome of asking for a slot.
   *
   * @param immediate true if the slot was granted without waiting
   * @param permit emits the permit, once granted
   */
  public record Admission(boolean immediate, Mono<Permit> permit) {}

  /** A start waiting for a slot. */
  private record Waiter(
      String key, int priority, Sinks.One<Permit> grant, AtomicReference<Permit> granted) {}
}
