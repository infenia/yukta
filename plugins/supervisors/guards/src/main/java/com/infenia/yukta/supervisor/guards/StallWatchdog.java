// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.supervisor.guards;

import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.StopMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

/**
 * Acts on nodes that run too long or say nothing for too long. It needs a clock, so it is a
 * supervisor of its own and not a rule: it follows {@code NodeStatusChanged} and {@code LogLine}
 * events and looks at what it follows on a timer.
 *
 * <p>Its state is a bounded map of the nodes it follows (the oldest is dropped past {@value
 * #MAX_NODES}). After {@code EventsMissed} it forgets everything and does not act on what it can no
 * longer see: a gap in the events never stops anything.
 */
@SuppressWarnings({
  "PMD.UseConcurrentHashMap", // insertion order decides which node is dropped
  "PMD.AvoidSynchronizedStatement" // two threads, short critical sections
})
public final class StallWatchdog implements ExecutionSupervisor {

  /** The status of a node that runs. */
  private static final String RUNNING = "RUNNING";

  /** The status of a node that has not started. */
  private static final String PENDING = "PENDING";

  /** The status of a node that waits for a slot. */
  private static final String QUEUED = "QUEUED";

  /** Most nodes followed at once. */
  public static final int MAX_NODES = 10_000;

  private final Config config;

  private final Scheduler scheduler;

  /** Followed nodes by {@code executionId/nodeId}, oldest first. */
  private final Map<String, Watched> runs =
      new LinkedHashMap<>() {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(final Map.Entry<String, Watched> eldest) {
          return size() > MAX_NODES;
        }
      };

  /**
   * Creates the watchdog.
   *
   * @param config what it is set to
   * @param scheduler gives the time and the timer; a virtual one in tests
   */
  public StallWatchdog(final Config config, final Scheduler scheduler) {
    this.config = config;
    this.scheduler = scheduler;
  }

  @Override
  @SuppressWarnings("PMD.ShortMethodName")
  public String id() {
    return "stall-watchdog";
  }

  @Override
  public Flux<ExecutionControlCommand> supervise(final Flux<ExecutionEvent> events) {
    return events.publish(
        shared ->
            Flux.merge(
                shared.doOnNext(this::observe).<ExecutionControlCommand>handle((e, sink) -> {}),
                Flux.interval(config.checkEvery(), scheduler)
                    .takeUntilOther(shared.then(Mono.just(true)))
                    .concatMapIterable(tick -> due())));
  }

  private long now() {
    return scheduler.now(TimeUnit.MILLISECONDS);
  }

  private void observe(final ExecutionEvent event) {
    synchronized (runs) {
      switch (event) {
        case ExecutionEvent.NodeStatusChanged node -> follow(node);
        case ExecutionEvent.LogLine line -> {
          final Watched run = runs.get(key(line.executionId(), line.nodeId()));
          if (run != null) {
            run.lastSeen = now();
          }
        }
        case ExecutionEvent.EventsMissed _ -> runs.clear();
        default -> {
          // nothing else tells the watchdog anything
        }
      }
    }
  }

  private void follow(final ExecutionEvent.NodeStatusChanged node) {
    final String key = key(node.executionId(), node.nodeId());
    if (RUNNING.equals(node.status())) {
      if (Selectors.selects(config.nodes(), node.nodeId())) {
        runs.putIfAbsent(key, new Watched(now()));
      }
    } else if (!PENDING.equals(node.status()) && !QUEUED.equals(node.status())) {
      runs.remove(key);
    }
  }

  /** The commands for the nodes that have stalled by now. */
  private List<ExecutionControlCommand> due() {
    final List<ExecutionControlCommand> out = new ArrayList<>();
    final long now = now();
    synchronized (runs) {
      runs.forEach(
          (key, run) -> {
            final String why = stalled(run, now);
            if (why != null && !(config.once() && run.acted)) {
              run.acted = true;
              out.add(command(key, why));
            }
          });
    }
    return out;
  }

  private @Nullable String stalled(final Watched run, final long now) {
    final Duration running = config.maxRunning();
    if (running != null && now - run.startedAt >= running.toMillis()) {
      return "running longer than " + running.toSeconds() + "s";
    }
    final Duration silence = config.maxSilence();
    if (silence != null && now - run.lastSeen >= silence.toMillis()) {
      return "no log line for " + silence.toSeconds() + "s";
    }
    return null;
  }

  private ExecutionControlCommand command(final String key, final String why) {
    final int slash = key.indexOf('/');
    final String executionId = key.substring(0, slash);
    final String nodeId = key.substring(slash + 1);
    final String reason = "stall-watchdog: node " + nodeId + " " + why;
    return switch (config.action()) {
      case PAUSE_NODE -> new ExecutionControlCommand.PauseNodeCommand(executionId, nodeId);
      case STOP_NODE ->
          new ExecutionControlCommand.StopNodeCommand(executionId, nodeId, false, reason);
      case PAUSE_WORKFLOW -> new ExecutionControlCommand.PauseWorkflowCommand(executionId);
      case STOP_WORKFLOW ->
          new ExecutionControlCommand.StopWorkflowCommand(executionId, reason, StopMode.DRAIN);
    };
  }

  /** Execution ids have no slash; node ids may, so the first slash splits the key. */
  private static String key(final String executionId, final String nodeId) {
    return executionId + "/" + nodeId;
  }

  /** A node being followed. */
  private static final class Watched {
    /** When it started, in the scheduler's milliseconds. */
    private final long startedAt;

    /** When it last logged, or its start. */
    private long lastSeen;

    /** Whether the watchdog has acted on it. */
    private boolean acted;

    /* default */ Watched(final long now) {
      this.startedAt = now;
      this.lastSeen = now;
    }
  }

  /** What the watchdog does to a node that stalls. */
  public enum Action {
    /** Pause the node. */
    PAUSE_NODE("PauseNodeCommand"),
    /** Stop the node. */
    STOP_NODE("StopNodeCommand"),
    /** Pause its workflow. */
    PAUSE_WORKFLOW("PauseWorkflowCommand"),
    /** Drain and stop its workflow. */
    STOP_WORKFLOW("StopWorkflowCommand");

    /** The command type it sends. */
    private final String commandName;

    Action(final String command) {
      this.commandName = command;
    }

    /**
     * The command type it sends.
     *
     * @return the simple class name of the command
     */
    public String command() {
      return commandName;
    }
  }

  /**
   * What the watchdog is set to.
   *
   * @param maxRunning longest a node may run, or null for no limit
   * @param maxSilence longest a running node may go without a log line, or null for no limit
   * @param nodes the node ids or {@code <prefix>.*} it watches, or null for every node
   * @param action what it does
   * @param once whether it acts once per run of a node, or at every check while the node stalls
   * @param checkEvery how often it looks
   */
  @SuppressWarnings("PMD.NullAssignment") // null is the documented value for every node
  public record Config(
      @Nullable Duration maxRunning,
      @Nullable Duration maxSilence,
      @Nullable List<String> nodes,
      Action action,
      boolean once,
      Duration checkEvery) {

    /** Keeps an immutable copy of the selectors. */
    public Config {
      nodes = nodes == null ? null : List.copyOf(nodes);
    }
  }
}
