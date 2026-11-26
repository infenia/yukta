// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.log;

import com.infenia.yukta.config.YuktaProperties;
import com.infenia.yukta.plugin.store.NodeCheckpointStore;
import com.infenia.yukta.service.leader.LeaderElection;
import com.infenia.yukta.service.leader.LeaderTimer;
import java.time.Clock;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Removes runs that ended more than {@code yukta.execution-log.max-age} ago from the execution log,
 * and their checkpoints, next to the count limit ({@code retained-runs}). It runs every hour and
 * only while this server is the leader, so one server purges a shared store; without a maximum age
 * it does nothing. ponytail: a removed run stays in the in-memory history until the next restart.
 */
@Slf4j
@Component
public class ExecutionLogAgeRetention implements SmartLifecycle {

  /** Time between two purges. */
  private static final Duration INTERVAL = Duration.ofHours(1);

  /** The execution log. */
  private final ExecutionLogStore store;

  /** The recorded node outputs of the runs. */
  private final NodeCheckpointStore checkpoints;

  /** How long an ended run is kept, or null for no limit. */
  private final @Nullable Duration maxAge;

  /** Runs {@link #purge} on the leader at a fixed interval. */
  private final LeaderTimer timer;

  /** The time of a purge. */
  private final Clock clock;

  /**
   * Creates the job.
   *
   * @param store the execution log
   * @param checkpoints the recorded node outputs
   * @param leader tells whether this server is the leader
   * @param properties the {@code yukta.execution-log} settings
   */
  @Autowired
  public ExecutionLogAgeRetention(
      final ExecutionLogStore store,
      final NodeCheckpointStore checkpoints,
      final LeaderElection leader,
      final YuktaProperties properties) {
    this(
        store,
        checkpoints,
        leader,
        properties.executionLog().maxAge(),
        INTERVAL,
        Schedulers.parallel(),
        Clock.systemUTC());
  }

  /**
   * Creates the job with an explicit timer.
   *
   * @param store the execution log
   * @param checkpoints the recorded node outputs
   * @param leader tells whether this server is the leader
   * @param maxAge how long an ended run is kept, or null for no limit
   * @param interval time between two purges
   * @param scheduler runs the timer; a virtual-time scheduler in tests
   * @param clock the time of a purge
   */
  public ExecutionLogAgeRetention(
      final ExecutionLogStore store,
      final NodeCheckpointStore checkpoints,
      final LeaderElection leader,
      final @Nullable Duration maxAge,
      final Duration interval,
      final Scheduler scheduler,
      final Clock clock) {
    this.store = store;
    this.checkpoints = checkpoints;
    this.maxAge = maxAge;
    this.timer = new LeaderTimer(leader, interval, scheduler, this::purge);
    this.clock = clock;
  }

  /**
   * Purges once: the old runs from the log, then their checkpoints.
   *
   * @return completes when they are removed; a failure is logged, never an error
   */
  /* default */ Mono<Void> purge() {
    final Duration age = maxAge;
    if (age == null) {
      return Mono.empty();
    }
    return Mono.defer(() -> store.purgeEndedBefore(clock.instant().minus(age)))
        .flatMap(gone ->
            Flux.fromIterable(gone)
                .concatMap(id -> checkpoints.clearAsync(id).onErrorResume(_ -> Mono.empty()))
                .then(Mono.just(gone.size())))
        .doOnNext(removed -> {
          if (removed > 0) {
            log.atInfo()
                .addKeyValue("removed", removed)
                .log("Runs older than the maximum age removed");
          }
        })
        .onErrorResume(error -> {
          log.atWarn().setCause(error).log("Run history purge failed");
          return Mono.empty();
        })
        .then();
  }

  @Override
  public void start() {
    if (maxAge != null) {
      timer.start();
    }
  }

  @Override
  public void stop() {
    timer.stop();
  }

  @Override
  public boolean isRunning() {
    return timer.isRunning();
  }
}
