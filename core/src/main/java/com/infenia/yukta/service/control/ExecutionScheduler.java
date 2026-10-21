// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control;

import com.infenia.yukta.service.leader.LeaderElection;
import com.infenia.yukta.service.leader.NotLeaderException;
import com.infenia.yukta.service.leader.SingleServerLeader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
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

  /** Longest a start may wait in the queue; zero or negative means as long as it takes. */
  private final Duration queueTimeout;

  /** Limit per session; 0 means unlimited. */
  private final int maxPerSession;

  /** How many starts of one workflow may wait; 0 means only the overall capacity applies. */
  private final int workflowQueueCapacity;

  /** Starts refused, by reason. */
  private final Map<RejectReason, LongAdder> rejections = new ConcurrentHashMap<>();

  /** Guards all state below. */
  private final Object lock = new Object();

  /** Tells whether this server admits runs; a single server always does. */
  private LeaderElection election = new SingleServerLeader();

  /** Starts waiting for a slot: highest priority first, oldest first within a priority. */
  private final List<Waiter> queue = new ArrayList<>();

  /** Running executions per workflow. */
  private final Map<String, Integer> runningPerKey = new ConcurrentHashMap<>();

  /** Running executions per session. */
  private final Map<String, Integer> runningPerSession = new ConcurrentHashMap<>();

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
   * @param queueTimeout longest a start may wait for a slot, zero for no limit
   * @param maxPerSession per-session limit, 0 for none
   * @param workflowQueueCapacity how many starts of one workflow may wait, 0 for no extra limit
   */
  @Autowired
  public ExecutionScheduler(
      @Value("${yukta.execution.max-concurrent:0}") final int maxConcurrent,
      @Value("${yukta.execution.max-concurrent-per-workflow:0}") final int maxPerWorkflow,
      @Value("${yukta.execution.queue-capacity:100}") final int queueCapacity,
      @Value("${yukta.execution.on-limit:QUEUE}") final OnLimit onLimit,
      @Value("${yukta.execution.shutdown-timeout:30s}") final Duration shutdownTimeout,
      @Value("${yukta.execution.queue-timeout:0}") final Duration queueTimeout,
      @Value("${yukta.execution.max-concurrent-per-session:0}") final int maxPerSession,
      @Value("${yukta.execution.queue-capacity-per-workflow:0}") final int workflowQueueCapacity) {
    this.maxPerSession = Math.max(0, maxPerSession);
    this.workflowQueueCapacity = Math.max(0, workflowQueueCapacity);
    this.maxConcurrent = Math.max(0, maxConcurrent);
    this.maxPerWorkflow = Math.max(0, maxPerWorkflow);
    this.queueCapacity = Math.max(0, queueCapacity);
    this.onLimit = onLimit;
    this.shutdownTimeout = shutdownTimeout;
    this.queueTimeout = queueTimeout;
  }

  /**
   * Creates a scheduler without per-session or per-workflow queue limits.
   *
   * @param maxConcurrent global limit, 0 for none
   * @param maxPerWorkflow per-workflow limit, 0 for none
   * @param queueCapacity how many starts may wait
   * @param onLimit what to do with a start that does not fit
   * @param shutdownTimeout how long running executions are given on shutdown
   * @param queueTimeout longest a start may wait for a slot, zero for no limit
   */
  public ExecutionScheduler(
      final int maxConcurrent,
      final int maxPerWorkflow,
      final int queueCapacity,
      final OnLimit onLimit,
      final Duration shutdownTimeout,
      final Duration queueTimeout) {
    this(
        maxConcurrent, maxPerWorkflow, queueCapacity, onLimit, shutdownTimeout, queueTimeout, 0, 0);
  }

  /**
   * Creates a scheduler whose queued starts wait as long as it takes.
   *
   * @param maxConcurrent global limit, 0 for none
   * @param maxPerWorkflow per-workflow limit, 0 for none
   * @param queueCapacity how many starts may wait
   * @param onLimit what to do with a start that does not fit
   * @param shutdownTimeout how long running executions are given on shutdown
   */
  public ExecutionScheduler(
      final int maxConcurrent,
      final int maxPerWorkflow,
      final int queueCapacity,
      final OnLimit onLimit,
      final Duration shutdownTimeout) {
    this(maxConcurrent, maxPerWorkflow, queueCapacity, onLimit, shutdownTimeout, Duration.ZERO);
  }

  /**
   * Refuses a start that could not be admitted or queued, so the caller finds out immediately.
   *
   * @param sessionId the session
   * @param workflowId the workflow
   * @throws ExecutionRejectedException if the start would be refused
   */
  public void assertCanAccept(final String sessionId, final String workflowId) {
    assertLeader();
    synchronized (lock) {
      if (closed) {
        throw refusal(RejectReason.SHUTDOWN, "The server is shutting down");
      }
      final String key = key(sessionId, workflowId);
      if (!fits(key)) {
        final RejectReason reason = cannotQueue(key);
        if (reason != null) {
          throw refusal(
              reason,
              switch (reason) {
                case LIMIT -> "Execution limit reached";
                case WORKFLOW_QUEUE_FULL ->
                    "Execution limit reached and the queue of this workflow is full";
                default -> "Execution limit reached and the queue is full";
              });
        }
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
    return acquire(sessionId, workflowId, priority, null);
  }

  /**
   * Asks for an execution slot with a priority, for an execution that can be found later.
   *
   * @param sessionId the session
   * @param workflowId the workflow
   * @param priority higher is admitted first when starts have to wait
   * @param executionId the execution asking, so {@link #changePriority} can find it while it waits
   * @return the slot at once if there is room, otherwise a Mono that waits for one
   */
  public Admission acquire(
      final String sessionId,
      final String workflowId,
      final int priority,
      @Nullable final String executionId) {
    assertLeader();
    final String key = key(sessionId, workflowId);
    synchronized (lock) {
      if (closed) {
        return refused(refusal(RejectReason.SHUTDOWN, "The server is shutting down"));
      }
      if (fits(key)) {
        return new Admission(true, Mono.just(take(key)));
      }
      final RejectReason reason = cannotQueue(key);
      if (reason != null) {
        return refused(refusal(reason, "Execution limit reached"));
      }
      final Waiter waiter =
          new Waiter(
              key, executionId, new AtomicInteger(priority), Sinks.one(), new AtomicReference<>());
      enqueue(waiter);
      final Mono<Permit> grant = waiter.grant().asMono().doOnCancel(() -> abandon(waiter));
      return new Admission(
          false,
          queueTimeout.isPositive()
              ? grant
                  .timeout(queueTimeout)
                  .onErrorMap(
                      TimeoutException.class,
                      _ -> {
                        count(RejectReason.QUEUE_TIMEOUT);
                        return new QueueTimeoutException(
                            "Waited " + queueTimeout + " for an execution slot");
                      })
              : grant);
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
   * An admission that takes no slot: a child run uses the slot its parent already holds, so a
   * parent that waits for its children cannot starve them. Children do not count toward the limits
   * or the running gauge; they stay bounded by the parent node and the nesting depth.
   *
   * @return an immediate admission whose permit releases nothing
   */
  public Admission borrowed() {
    return new Admission(true, Mono.just(new Permit("")));
  }

  /**
   * The per-workflow limit now in force.
   *
   * @return the limit, 0 for none
   */
  public int perWorkflowLimit() {
    synchronized (lock) {
      return maxPerWorkflow;
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
   * Changes the priority of a start that is waiting for a slot; it moves behind the starts of
   * higher or equal priority that were already waiting.
   *
   * @param executionId the waiting execution
   * @param priority the new priority
   * @return true if the execution was waiting, false if it is running, finished or unknown
   */
  public boolean changePriority(final String executionId, final int priority) {
    synchronized (lock) {
      for (final Waiter waiter : queue) {
        if (executionId.equals(waiter.executionId())) {
          queue.remove(waiter);
          waiter.priority().set(priority);
          enqueue(waiter);
          return true;
        }
      }
      return false;
    }
  }

  /** Inserts a waiter after every waiter of higher or equal priority. */
  private void enqueue(final Waiter waiter) {
    int index = queue.size();
    while (index > 0 && queue.get(index - 1).priority().get() < waiter.priority().get()) {
      index--;
    }
    queue.add(index, waiter);
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

  private static Admission refused(final ExecutionRejectedException error) {
    return new Admission(true, Mono.error(error), true);
  }

  /** Only the leader admits runs; a standby refuses them (HTTP 503). */
  private void assertLeader() {
    if (!election.isLeader()) {
      count(RejectReason.NOT_LEADER);
      throw new NotLeaderException();
    }
  }

  /**
   * Lets the election decide whether this server admits runs; without one it always does.
   *
   * @param leaderElection the election
   */
  @Autowired(required = false)
  public void setLeaderElection(final LeaderElection leaderElection) {
    this.election = leaderElection;
  }

  /** Counts a refusal and builds its exception. */
  private ExecutionRejectedException refusal(final RejectReason reason, final String message) {
    count(reason);
    return new ExecutionRejectedException(message);
  }

  private void count(final RejectReason reason) {
    rejections.computeIfAbsent(reason, _ -> new LongAdder()).increment();
  }

  /**
   * Why a start that does not fit cannot wait either, or null if it can join the queue.
   *
   * @param key the workflow of the start
   */
  @Nullable
  private RejectReason cannotQueue(final String key) {
    if (onLimit == OnLimit.REJECT) {
      return RejectReason.LIMIT;
    }
    if (queue.size() >= queueCapacity) {
      return RejectReason.QUEUE_FULL;
    }
    if (workflowQueueCapacity > 0
        && queue.stream().filter(waiter -> waiter.key().equals(key)).count()
            >= workflowQueueCapacity) {
      return RejectReason.WORKFLOW_QUEUE_FULL;
    }
    return null;
  }

  /**
   * How many starts were refused for a reason.
   *
   * @param reason why they were refused
   * @return the count since startup
   */
  public long rejected(final RejectReason reason) {
    final LongAdder count = rejections.get(reason);
    return count == null ? 0 : count.sum();
  }

  private static String sessionOf(final String key) {
    return key.substring(0, key.indexOf('\0'));
  }

  private boolean fits(final String key) {
    return (maxConcurrent == 0 || runningTotal < maxConcurrent)
        && (maxPerWorkflow == 0 || runningPerKey.getOrDefault(key, 0) < maxPerWorkflow)
        && (maxPerSession == 0
            || runningPerSession.getOrDefault(sessionOf(key), 0) < maxPerSession);
  }

  private Permit take(final String key) {
    runningTotal++;
    runningPerKey.merge(key, 1, Integer::sum);
    runningPerSession.merge(sessionOf(key), 1, Integer::sum);
    return new Permit(key);
  }

  private void free(final String key) {
    final List<Waiter> admitted;
    synchronized (lock) {
      runningTotal--;
      runningPerKey.computeIfPresent(key, (_, count) -> count > 1 ? count - 1 : null);
      runningPerSession.computeIfPresent(
          sessionOf(key), (_, count) -> count > 1 ? count - 1 : null);
      admitted = admitWaiting();
      if (closed && runningTotal == 0) {
        drained.tryEmitEmpty();
      }
    }
    grant(admitted);
  }

  /**
   * Grants slots to waiting starts that fit: highest priority first, and within a priority the
   * session with the fewest running executions first (oldest first on a tie), so one busy session
   * cannot starve the others. Called with the lock held; the caller hands the slots over with
   * {@link #grant} once it has let go of the lock.
   *
   * @return the starts that now hold a slot
   */
  private List<Waiter> admitWaiting() {
    final List<Waiter> admitted = new ArrayList<>();
    Waiter next = nextToAdmit();
    while (next != null) {
      queue.remove(next);
      next.granted().set(take(next.key()));
      admitted.add(next);
      next = nextToAdmit();
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

  /** The waiting start that fits and goes first, or null if none fits. */
  @Nullable
  private Waiter nextToAdmit() {
    Waiter best = null;
    int bestRunning = 0;
    for (final Waiter waiter : queue) {
      if (!fits(waiter.key())) {
        continue;
      }
      final int running = runningPerSession.getOrDefault(sessionOf(waiter.key()), 0);
      // the queue is ordered by priority, so a later waiter is never above the best so far
      if (best == null
          || (waiter.priority().get() == best.priority().get() && running < bestRunning)) {
        best = waiter;
        bestRunning = running;
      }
    }
    return best;
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
      // A stopped scheduler can be started again (Spring pauses and resumes cached test contexts).
      closed = false;
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

  /** Why a start was refused. */
  public enum RejectReason {
    SHUTDOWN,
    LIMIT,
    QUEUE_FULL,
    WORKFLOW_QUEUE_FULL,
    QUEUE_TIMEOUT,
    NOT_LEADER
  }

  /** What to do with a start that does not fit within the limits. */
  public enum OnLimit {
    QUEUE,
    REJECT
  }

  /** A claim on one execution slot; release it when the execution ends. */
  public final class Permit {
    /** The workflow the slot was taken for; empty for a borrowed one, which holds nothing. */
    private final String key;

    /** Makes releasing idempotent. */
    private final AtomicBoolean released = new AtomicBoolean();

    private Permit(final String key) {
      this.key = key;
    }

    /** Gives the slot back and admits the next waiting start that fits. */
    public void release() {
      if (!key.isEmpty() && released.compareAndSet(false, true)) {
        free(key);
      }
    }
  }

  /**
   * Outcome of asking for a slot.
   *
   * @param immediate true if the slot was granted without waiting
   * @param permit emits the permit, once granted
   * @param refused true if the start was refused, in which case {@code permit} errors with why
   */
  public record Admission(boolean immediate, Mono<Permit> permit, boolean refused) {

    /**
     * An admission that was not refused.
     *
     * @param immediate true if the slot was granted without waiting
     * @param permit emits the permit, once granted
     */
    public Admission(final boolean immediate, final Mono<Permit> permit) {
      this(immediate, permit, false);
    }
  }

  /** A start waiting for a slot. */
  private record Waiter(
      String key,
      @Nullable String executionId,
      AtomicInteger priority,
      Sinks.One<Permit> grant,
      AtomicReference<Permit> granted) {}
}
