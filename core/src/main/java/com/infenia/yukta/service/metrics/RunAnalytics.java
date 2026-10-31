// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.metrics;

import com.infenia.yukta.model.execution.TaskStatus;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.service.supervisor.ExecutionEventFeed;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.BufferOverflowStrategy;
import reactor.core.scheduler.Schedulers;

/**
 * Per-minute aggregates of what happened to the runs, per session and per workflow, built from the
 * execution event feed.
 *
 * <p>Micrometer carries no session or workflow tag, because that would multiply the series. This
 * class keeps the same facts bounded instead: 24 hours of minute buckets for the whole server and
 * for at most {@value #MAX_SCOPES} sessions and {@value #MAX_SCOPES} workflows (the rest are folded
 * into {@code session:other} and {@code workflow:other}), with at most {@value #SAMPLES} duration
 * samples per bucket. History is lost on restart.
 *
 * <p>A scope is {@code global}, {@code session:<id>} or {@code workflow:<id>}; an unknown scope
 * answers with nothing.
 */
@Component
// All state is guarded by this one lock, so plain maps and method-level synchronization are right.
@SuppressWarnings({
  "PMD.TooManyMethods",
  "PMD.UseConcurrentHashMap",
  "PMD.AvoidSynchronizedAtMethodLevel",
  "PMD.AvoidInstantiatingObjectsInLoops",
  "PMD.GodClass",
  "PMD.CouplingBetweenObjects"
})
public class RunAnalytics {

  /** The scope of the whole server. */
  public static final String GLOBAL = "global";

  /** Most sessions, and most workflows, tracked on their own. */
  private static final int MAX_SCOPES = 50;

  /**
   * Duration samples kept per bucket; later runs of the same minute are counted but not sampled.
   */
  private static final int SAMPLES = 64;

  /** Failed run ids kept per bucket. */
  private static final int FAILED_IDS = 20;

  /** Most control annotations kept. */
  private static final int MAX_ANNOTATIONS = 500;

  /** Most runs followed at once. */
  private static final int MAX_LIVE = 10_000;

  /** Milliseconds in a minute. */
  private static final long MINUTE = 60_000L;

  /** How long buckets are kept. */
  private static final Duration RETENTION = Duration.ofHours(24);

  /** Statuses that count as a failure. */
  private static final Set<String> FAILED =
      Set.of(TaskStatus.FAILURE.name(), TaskStatus.ERROR.name(), TaskStatus.TIMED_OUT.name());

  /** The kind of scope for session and workflow keys. */
  private static final String OTHER = "other";

  /** Where the events come from. */
  private final ExecutionEventFeed feed;

  /** The time of "now" for elapsed times. */
  private final Clock clock;

  /** Minute buckets by scope. */
  private final Map<String, NavigableMap<Long, Bucket>> scopes = new HashMap<>();

  /** Runs that have not ended. */
  private final Map<String, RunState> live =
      new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<String, RunState> eldest) {
          return size() > MAX_LIVE;
        }
      };

  /** Control commands, newest last. */
  private final Deque<Annotation> controlLog = new ArrayDeque<>();

  /** The subscription to the feed. */
  private Disposable subscription;

  /**
   * Creates the aggregator.
   *
   * @param feed the event feed
   */
  @Autowired
  public RunAnalytics(final ExecutionEventFeed feed) {
    this(feed, Clock.systemUTC());
  }

  /**
   * Creates the aggregator with a given clock.
   *
   * @param feed the event feed
   * @param clock where "now" comes from
   */
  /* default */ RunAnalytics(final ExecutionEventFeed feed, final Clock clock) {
    this.feed = feed;
    this.clock = clock;
  }

  /** Starts following the feed. */
  @PostConstruct
  /* default */ void start() {
    // own thread and buffer: a dashboard query holding the lock must not stall the tracker
    subscription =
        feed.events()
            .onBackpressureBuffer(10_000, _ -> {}, BufferOverflowStrategy.DROP_LATEST)
            .publishOn(Schedulers.single())
            .subscribe(this::accept);
  }

  /** Stops following the feed. */
  @PreDestroy
  /* default */ void stop() {
    if (subscription != null) {
      subscription.dispose();
    }
  }

  /** What the aggregator keeps per minute. */
  private static final class Bucket {
    private final Map<String, Integer> byStatus = new TreeMap<>();
    private final double[] durations = new double[SAMPLES];
    private int durationCount;
    private final double[] waits = new double[SAMPLES];
    private int waitCount;
    private final List<String> failedRuns = new ArrayList<>();
    private final Map<String, double[]> modules = new TreeMap<>();

    private void addWait(final double wait) {
      waitCount++;
      reserve(waits, waitCount, wait);
    }

    private void addRun(final String executionId, final String status, final double ms) {
      byStatus.merge(status, 1, Integer::sum);
      if (ms >= 0) {
        durationCount++;
        reserve(durations, durationCount, ms);
      }
      if (FAILED.contains(status) && failedRuns.size() < FAILED_IDS) {
        failedRuns.add(executionId);
      }
    }

    /** Reservoir sampling: after {@code seen} values, each one is kept with equal chance. */
    private static void reserve(final double[] sample, final int seen, final double value) {
      if (seen <= SAMPLES) {
        sample[seen - 1] = value;
      } else {
        final int slot = ThreadLocalRandom.current().nextInt(seen);
        if (slot < SAMPLES) {
          sample[slot] = value;
        }
      }
    }

    private List<Double> sampledDurations() {
      return Arrays.stream(durations, 0, Math.min(durationCount, SAMPLES)).boxed().toList();
    }

    private List<Double> sampledWaits() {
      return Arrays.stream(waits, 0, Math.min(waitCount, SAMPLES)).boxed().toList();
    }
  }

  /** A run that has not ended. */
  private static final class RunState {
    private final String sessionId;
    private final String workflowId;
    private Instant queuedAt;
    private Instant startedAt;
    private final Set<String> nodesSeen = new HashSet<>();
    private final Set<String> nodesDone = new HashSet<>();
    private final Map<String, Instant> nodeStarted = new HashMap<>();

    private RunState(final String sessionId, final String workflowId) {
      this.sessionId = sessionId;
      this.workflowId = workflowId;
    }
  }

  /**
   * A control command, for marking charts.
   *
   * @param time epoch milliseconds
   * @param executionId the run it addressed
   * @param sessionId its session, or null
   * @param workflowId its workflow, or null
   * @param command the command name
   * @param actor who sent it, or null for the API
   * @param applied true if applied, false if rejected
   * @param reason why it was rejected, or null
   */
  public record Annotation(
      long time,
      String executionId,
      String sessionId,
      String workflowId,
      String command,
      String actor,
      boolean applied,
      String reason) {}

  /**
   * One minute of the run heat strip.
   *
   * @param time epoch milliseconds of the minute
   * @param runs runs that ended in it
   * @param failed how many of them failed
   * @param failedRuns ids of some of the failed runs, so a click can open one
   */
  public record HeatCell(long time, int runs, int failed, List<String> failedRuns) {

    /** Keeps an immutable copy of the ids. */
    public HeatCell {
      failedRuns = List.copyOf(failedRuns);
    }
  }

  /**
   * How one plugin type did.
   *
   * @param module the plugin type
   * @param executions nodes that ended
   * @param failures how many failed
   * @param avgMs mean node duration in milliseconds
   */
  public record ModuleRow(String module, long executions, long failures, double avgMs) {}

  /**
   * A run in progress.
   *
   * @param executionId the run
   * @param sessionId its session
   * @param workflowId its workflow
   * @param startedAt epoch milliseconds
   * @param elapsedMs time since it started
   * @param nodesDone nodes that ended
   * @param nodesSeen nodes that have started or ended
   * @param p95Ms the workflow's usual p95 duration over the last day, or null when unknown
   */
  public record LiveRun(
      String executionId,
      String sessionId,
      String workflowId,
      long startedAt,
      long elapsedMs,
      int nodesDone,
      int nodesSeen,
      Double p95Ms) {}

  /**
   * Takes one event into the aggregates.
   *
   * @param event the event
   */
  public synchronized void accept(final ExecutionEvent event) {
    switch (event) {
      case ExecutionEvent.WorkflowStatusChanged e -> onRun(e);
      case ExecutionEvent.NodeStatusChanged e -> onNode(e);
      case ExecutionEvent.ControlOutcome e -> onControl(e);
      default -> {
        // log lines and gaps carry nothing to aggregate
      }
    }
  }

  private void onControl(final ExecutionEvent.ControlOutcome e) {
    controlLog.addLast(
        new Annotation(
            e.timestamp().toEpochMilli(),
            e.executionId(),
            e.sessionId(),
            e.workflowId(),
            e.command(),
            e.actor(),
            e.applied(),
            e.reason()));
    while (controlLog.size() > MAX_ANNOTATIONS) {
      controlLog.removeFirst();
    }
  }

  private void onNode(final ExecutionEvent.NodeStatusChanged e) {
    final RunState run =
        live.computeIfAbsent(e.executionId(), _ -> new RunState(e.sessionId(), e.workflowId()));
    run.nodesSeen.add(e.nodeId());
    if (TaskStatus.RUNNING.name().equals(e.status())) {
      run.nodeStarted.putIfAbsent(e.nodeId(), e.timestamp());
    } else if (TaskStatus.isTerminalName(e.status()) && run.nodesDone.add(e.nodeId())) {
      final Instant started = run.nodeStarted.remove(e.nodeId());
      final double ms = started == null ? 0 : Duration.between(started, e.timestamp()).toMillis();
      final boolean failed = FAILED.contains(e.status());
      for (final Bucket b : buckets(run, e.timestamp())) {
        final double[] stat = b.modules.computeIfAbsent(e.module(), _ -> new double[3]);
        stat[0]++;
        stat[1] += failed ? 1 : 0;
        stat[2] += ms;
      }
    }
  }

  private void onRun(final ExecutionEvent.WorkflowStatusChanged e) {
    final RunState run =
        live.computeIfAbsent(e.executionId(), _ -> new RunState(e.sessionId(), e.workflowId()));
    if (TaskStatus.QUEUED.name().equals(e.status())) {
      run.queuedAt = e.timestamp();
    } else if (TaskStatus.RUNNING.name().equals(e.status()) && run.startedAt == null) {
      run.startedAt = e.timestamp();
      if (run.queuedAt != null) {
        final double wait = Duration.between(run.queuedAt, e.timestamp()).toMillis();
        buckets(run, e.timestamp()).forEach(b -> b.addWait(wait));
      }
    } else if (TaskStatus.isTerminalName(e.status())) {
      live.remove(e.executionId());
      final double ms =
          run.startedAt == null ? -1 : Duration.between(run.startedAt, e.timestamp()).toMillis();
      buckets(run, e.timestamp()).forEach(b -> b.addRun(e.executionId(), e.status(), ms));
    }
  }

  /** The buckets of the minute for the server, the run's session and its workflow. */
  private List<Bucket> buckets(final RunState run, final Instant at) {
    final long minute = at.toEpochMilli() / MINUTE;
    final List<Bucket> out = new ArrayList<>(3);
    out.add(bucket(GLOBAL, minute));
    out.add(bucket(scopeKey("session:", run.sessionId), minute));
    out.add(bucket(scopeKey("workflow:", run.workflowId), minute));
    final long oldest = minute - RETENTION.toMinutes();
    for (final NavigableMap<Long, Bucket> series : scopes.values()) {
      series.headMap(oldest, false).clear();
    }
    return out;
  }

  /** The key of a session or workflow, or its "other" key when too many are already tracked. */
  private String scopeKey(final String kind, final String id) {
    final String key = kind + id;
    if (id == null || scopes.containsKey(key)) {
      return id == null ? kind + OTHER : key;
    }
    final long tracked = scopes.keySet().stream().filter(k -> k.startsWith(kind)).count();
    return tracked >= MAX_SCOPES ? kind + OTHER : key;
  }

  private Bucket bucket(final String scope, final long minute) {
    return scopes
        .computeIfAbsent(scope, _ -> new TreeMap<>())
        .computeIfAbsent(minute, _ -> new Bucket());
  }

  /** The buckets of a scope within the range ending now; empty for an unknown scope. */
  private NavigableMap<Long, Bucket> within(final String scope, final Duration range) {
    final NavigableMap<Long, Bucket> all = scopes.get(scope);
    if (all == null) {
      return new TreeMap<>();
    }
    final long now = clock.millis() / MINUTE;
    return all.subMap(now - range.toMinutes(), true, now, true);
  }

  private static double percentile(final List<Double> samples, final double p) {
    final List<Double> sorted = samples.stream().sorted().toList();
    return sorted.get((int) Math.min(sorted.size() - 1, Math.ceil(p * sorted.size()) - 1));
  }

  /**
   * History of the run aggregates. Series are {@code runs.finished}, {@code runs.<STATUS>}, {@code
   * duration.p50|p95|p99} and {@code queue.wait.p95}, in milliseconds; a minute without data has no
   * point.
   *
   * @param scope the scope
   * @param names series names or prefixes to return
   * @param range how far back
   * @return series name to points
   */
  public synchronized Map<String, List<MetricsSampler.Point>> series(
      final String scope, final List<String> names, final Duration range) {
    final Map<String, List<MetricsSampler.Point>> out = new TreeMap<>();
    within(scope, range)
        .forEach(
            (minute, b) -> {
              final Map<String, Double> values = new TreeMap<>();
              if (!b.byStatus.isEmpty()) {
                values.put(
                    "runs.finished", (double) b.byStatus.values().stream().mapToInt(i -> i).sum());
                b.byStatus.forEach((status, n) -> values.put("runs." + status, (double) n));
              }
              if (b.durationCount > 0) {
                values.put("duration.p50", percentile(b.sampledDurations(), 0.5));
                values.put("duration.p95", percentile(b.sampledDurations(), 0.95));
                values.put("duration.p99", percentile(b.sampledDurations(), 0.99));
              }
              if (b.waitCount > 0) {
                values.put("queue.wait.p95", percentile(b.sampledWaits(), 0.95));
              }
              values.forEach(
                  (name, v) -> {
                    if (names.stream().anyMatch(name::startsWith)) {
                      out.computeIfAbsent(name, _ -> new ArrayList<>())
                          .add(new MetricsSampler.Point(minute * MINUTE, v));
                    }
                  });
            });
    return out;
  }

  /**
   * The aggregates of the last hour as single values: {@code runs.finished}, {@code runs.<STATUS>},
   * {@code runs.failed} and {@code duration.p95} in milliseconds.
   *
   * @param scope the scope
   * @return name to value; empty for an unknown scope or nothing in the hour
   */
  public synchronized Map<String, Double> snapshot(final String scope) {
    final Map<String, Double> out = new TreeMap<>();
    final List<Double> durations = new ArrayList<>();
    double finished = 0;
    double failed = 0;
    for (final Bucket b : within(scope, Duration.ofHours(1)).values()) {
      for (final Map.Entry<String, Integer> s : b.byStatus.entrySet()) {
        out.merge("runs." + s.getKey(), (double) s.getValue(), Double::sum);
        finished += s.getValue();
        if (FAILED.contains(s.getKey())) {
          failed += s.getValue();
        }
      }
      durations.addAll(b.sampledDurations());
    }
    if (finished > 0) {
      out.put("runs.finished", finished);
      out.put("runs.failed", failed);
    }
    if (!durations.isEmpty()) {
      out.put("duration.p95", percentile(durations, 0.95));
    }
    return out;
  }

  /**
   * One cell per minute that had runs.
   *
   * @param scope the scope
   * @param range how far back
   * @return the cells, oldest first
   */
  public synchronized List<HeatCell> heatmap(final String scope, final Duration range) {
    final List<HeatCell> out = new ArrayList<>();
    within(scope, range)
        .forEach(
            (minute, b) -> {
              final int runs = b.byStatus.values().stream().mapToInt(i -> i).sum();
              final int failed =
                  b.byStatus.entrySet().stream()
                      .filter(e -> FAILED.contains(e.getKey()))
                      .mapToInt(Map.Entry::getValue)
                      .sum();
              if (runs > 0) {
                out.add(new HeatCell(minute * MINUTE, runs, failed, b.failedRuns));
              }
            });
    return out;
  }

  /**
   * How each plugin type did within the range, busiest first.
   *
   * @param scope the scope
   * @param range how far back
   * @return one row per plugin type
   */
  public synchronized List<ModuleRow> modules(final String scope, final Duration range) {
    final Map<String, double[]> sum = new TreeMap<>();
    within(scope, range)
        .values()
        .forEach(
            b ->
                b.modules.forEach(
                    (module, stat) -> {
                      final double[] total = sum.computeIfAbsent(module, _ -> new double[3]);
                      for (int i = 0; i < total.length; i++) {
                        total[i] += stat[i];
                      }
                    }));
    return sum.entrySet().stream()
        .map(
            e ->
                new ModuleRow(
                    e.getKey(),
                    (long) e.getValue()[0],
                    (long) e.getValue()[1],
                    e.getValue()[2] / e.getValue()[0]))
        .sorted((a, b) -> Long.compare(b.executions(), a.executions()))
        .toList();
  }

  /**
   * The runs in progress, longest running first.
   *
   * @return one entry per run that has started and not ended
   */
  public synchronized List<LiveRun> liveRuns() {
    final long now = clock.millis();
    return live.entrySet().stream()
        .filter(e -> e.getValue().startedAt != null)
        .map(
            e -> {
              final RunState r = e.getValue();
              return new LiveRun(
                  e.getKey(),
                  r.sessionId,
                  r.workflowId,
                  r.startedAt.toEpochMilli(),
                  now - r.startedAt.toEpochMilli(),
                  r.nodesDone.size(),
                  r.nodesSeen.size(),
                  baseline(r.workflowId));
            })
        .sorted((a, b) -> Long.compare(b.elapsedMs(), a.elapsedMs()))
        .toList();
  }

  /** The workflow's p95 duration over its kept buckets, or null without samples. */
  private Double baseline(final String workflowId) {
    final NavigableMap<Long, Bucket> all = scopes.get("workflow:" + workflowId);
    if (all == null) {
      return null;
    }
    final List<Double> pooled = new ArrayList<>();
    all.values().forEach(b -> pooled.addAll(b.sampledDurations()));
    return pooled.isEmpty() ? null : percentile(pooled, 0.95);
  }

  /**
   * Control commands within the range, for marking charts.
   *
   * @param scope the scope; commands whose run is outside it are left out
   * @param range how far back
   * @return the annotations, oldest first
   */
  public synchronized List<Annotation> annotations(final String scope, final Duration range) {
    final long from = clock.millis() - range.toMillis();
    return controlLog.stream()
        .filter(a -> a.time() >= from)
        .filter(
            a ->
                GLOBAL.equals(scope)
                    || scope.equals("session:" + a.sessionId())
                    || scope.equals("workflow:" + a.workflowId()))
        .toList();
  }
}
