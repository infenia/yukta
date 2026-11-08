// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.input;

import com.infenia.yukta.config.YuktaProperties;
import com.infenia.yukta.service.leader.LeaderElection;
import com.infenia.yukta.service.leader.LeaderTimer;
import java.time.Clock;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Removes finished input requests, and their answers, that were last changed more than {@code
 * yukta.input.retention} ago. It runs every hour and only while this server is the leader, so one
 * server purges a shared store. The in-memory store keeps its own bound; the purge is harmless
 * there.
 */
@Slf4j
@Component
public class InputRetention implements SmartLifecycle {

  /** Time between two purges. */
  private static final Duration INTERVAL = Duration.ofHours(1);

  /** Where the requests are kept. */
  private final InputRequestStore store;

  /** How long a finished request is kept. */
  private final Duration retention;

  /** Runs {@code job} on the leader at a fixed interval. */
  private final LeaderTimer timer;

  /** The time of a purge. */
  private final Clock clock;

  /**
   * Creates the job.
   *
   * @param store where the requests are kept
   * @param leader tells whether this server is the leader
   * @param properties the {@code yukta.input} settings
   */
  @Autowired
  public InputRetention(
      final InputRequestStore store,
      final LeaderElection leader,
      final YuktaProperties properties) {
    this(
        store,
        leader,
        properties.input().retention(),
        INTERVAL,
        Schedulers.parallel(),
        Clock.systemUTC());
  }

  /**
   * Creates the job with an explicit timer.
   *
   * @param store where the requests are kept
   * @param leader tells whether this server is the leader
   * @param retention how long a finished request is kept
   * @param interval time between two purges
   * @param scheduler runs the timer; a virtual-time scheduler in tests
   * @param clock the time of a purge
   */
  public InputRetention(
      final InputRequestStore store,
      final LeaderElection leader,
      final Duration retention,
      final Duration interval,
      final Scheduler scheduler,
      final Clock clock) {
    this.store = store;
    this.retention = retention;
    this.timer = new LeaderTimer(leader, interval, scheduler, this::purge);
    this.clock = clock;
  }

  /**
   * Purges once.
   *
   * @return completes when the requests are removed; a failure is logged, never an error
   */
  /* default */ Mono<Void> purge() {
    return Mono.defer(() -> store.purgeTerminalOlderThan(clock.instant().minus(retention)))
        .doOnNext(
            removed -> {
              if (removed > 0) {
                log.atInfo().addKeyValue("removed", removed).log("Old input requests removed");
              }
            })
        .onErrorResume(
            error -> {
              log.atWarn().setCause(error).log("Input request purge failed");
              return Mono.empty();
            })
        .then();
  }

  @Override
  public void start() {
    timer.start();
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
