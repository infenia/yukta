// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.agent;

import com.infenia.yukta.config.YuktaProperties;
import com.infenia.yukta.service.leader.LeaderElection;
import com.infenia.yukta.service.leader.LeaderTimer;
import java.time.Clock;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Removes ended conversations, with their events, that ended more than {@code
 * yukta.agents.retention} ago. It runs every hour and only while this server is the leader, so one
 * server purges a shared store. The in-memory store keeps its own bound; the purge is harmless
 * there.
 */
@Slf4j
@Component
@ConditionalOnProperty(
    prefix = "yukta.agents",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class AgentRetention implements SmartLifecycle {

  /** Time between two purges. */
  private static final Duration INTERVAL = Duration.ofHours(1);

  /** Where the conversations are kept. */
  private final AgentConversationStore store;

  /** How long an ended conversation is kept. */
  private final Duration retention;

  /** Runs the purge on the leader at a fixed interval. */
  private final LeaderTimer timer;

  /** The time of a purge. */
  private final Clock clock;

  /**
   * Creates the job.
   *
   * @param store where the conversations are kept
   * @param leader tells whether this server is the leader
   * @param properties the {@code yukta.agents} settings
   */
  @Autowired
  public AgentRetention(
      final AgentConversationStore store,
      final LeaderElection leader,
      final YuktaProperties properties) {
    this(
        store,
        leader,
        properties.agents().retention(),
        INTERVAL,
        Schedulers.parallel(),
        Clock.systemUTC());
  }

  /**
   * Creates the job with an explicit timer.
   *
   * @param store where the conversations are kept
   * @param leader tells whether this server is the leader
   * @param retention how long an ended conversation is kept
   * @param interval time between two purges
   * @param scheduler runs the timer; a virtual-time scheduler in tests
   * @param clock the time of a purge
   */
  public AgentRetention(
      final AgentConversationStore store,
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
   * @return completes when the conversations are removed; a failure is logged, never an error
   */
  /* default */ Mono<Void> purge() {
    return Mono.defer(() -> store.purge(clock.instant().minus(retention)))
        .doOnNext(removed -> {
          if (removed > 0) {
            log.atInfo().addKeyValue("removed", removed).log("Old agent conversations removed");
          }
        })
        .onErrorResume(error -> {
          log.atWarn().setCause(error).log("Agent conversation purge failed");
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
