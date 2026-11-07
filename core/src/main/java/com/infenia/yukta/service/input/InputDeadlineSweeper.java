// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.input;

import com.infenia.yukta.config.YuktaProperties;
import com.infenia.yukta.service.leader.LeaderElection;
import com.infenia.yukta.service.leader.LeaderTimer;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Ends the input requests that are past their deadline. It looks every {@code
 * yukta.input.sweep-interval} and only while this server is the leader, because the run that waits
 * lives where the leader is. The deadline is an absolute time stored with the request, so a late or
 * skipped tick only delays the end, it never moves it.
 */
@Slf4j
@Component
public class InputDeadlineSweeper implements SmartLifecycle {

  /** Ends the requests. */
  private final InputBroker broker;

  /** Runs {@code job} on the leader at a fixed interval. */
  private final LeaderTimer timer;

  /**
   * Creates the sweeper.
   *
   * @param broker ends the requests
   * @param leader tells whether this server is the leader
   * @param properties the {@code yukta.input} settings
   */
  @Autowired
  public InputDeadlineSweeper(
      final InputBroker broker, final LeaderElection leader, final YuktaProperties properties) {
    this(broker, leader, properties.input().sweepInterval(), Schedulers.parallel());
  }

  /**
   * Creates the sweeper with an explicit timer.
   *
   * @param broker ends the requests
   * @param leader tells whether this server is the leader
   * @param interval time between two looks
   * @param scheduler runs the timer; a virtual-time scheduler in tests
   */
  @SuppressFBWarnings("EI_EXPOSE_REP2") // the shared broker is held on purpose
  public InputDeadlineSweeper(
      final InputBroker broker,
      final LeaderElection leader,
      final Duration interval,
      final Scheduler scheduler) {
    this.broker = broker;
    this.timer = new LeaderTimer(leader, interval, scheduler, this::sweep);
  }

  /* default */ Mono<Void> sweep() {
    // one look at a time: a slow look is not overtaken by the next tick
    return broker
        .sweep()
        .onErrorResume(
            error -> {
              log.atWarn().setCause(error).log("Input deadline sweep failed");
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
