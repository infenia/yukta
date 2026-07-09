// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.compiler;

import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;

/**
 * Fluent builder for managing periodic heartbeat and statistics emissions to the control bus.
 *
 * <p>HeartbeatBuilder manages subscriptions for periodic heartbeat and statistics emissions on a
 * per-node basis. It creates Flux.interval() subscriptions for each node and maintains a list of
 * Disposable objects for lifecycle management.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * HeartbeatBuilder builder = new HeartbeatBuilder(
 *     controlBusGateway,
 *     Duration.ofMillis(500),
 *     Schedulers.boundedElastic());
 *
 * List<Disposable> disposables = builder
 *     .forNodes(List.of("node-1", "node-2"))
 *     .withHeartbeatInterval(Duration.ofMillis(500))
 *     .withStatisticsInterval(Duration.ofSeconds(1))
 *     .build();
 *
 * // ... later ...
 * disposables.forEach(Disposable::dispose);
 * }</pre>
 */
@Slf4j
public class HeartbeatBuilder {

  /** The default interval for heartbeat emissions. */
  private final Duration defaultInterval;

  /** The scheduler for executing periodic tasks. */
  private final Scheduler scheduler;

  /** The list of nodes for heartbeat emissions. */
  @Nullable private List<String> nodes;

  /** The heartbeat interval duration. */
  @Nullable private Duration hbInterval;

  /** The statistics emission interval duration. */
  @Nullable private Duration statsInterval;

  /**
   * Creates a new HeartbeatBuilder instance.
   *
   * @param defaultInterval the default interval for heartbeat emissions
   * @param scheduler the scheduler for Flux.interval() operations
   */
  public HeartbeatBuilder(final Duration defaultInterval, final Scheduler scheduler) {
    this.defaultInterval = defaultInterval;
    this.scheduler = scheduler;
  }

  /**
   * Sets the list of nodes for which to emit heartbeats and statistics.
   *
   * @param wfId the workflow identifier (unused, kept for API compatibility)
   * @param nodeList the list of node identifiers
   * @return this builder for fluent chaining
   */
  public HeartbeatBuilder forNodes(@NotBlank final String wfId, final List<String> nodeList) {
    if (nodeList != null) {
      this.nodes = new ArrayList<>(nodeList);
    }
    return this;
  }

  /**
   * Sets the interval for heartbeat emissions.
   *
   * @param interval the heartbeat interval
   * @return this builder for fluent chaining
   */
  public HeartbeatBuilder withHeartbeatInterval(final Duration interval) {
    this.hbInterval = interval;
    return this;
  }

  /**
   * Sets the interval for statistics emissions.
   *
   * @param interval the statistics interval
   * @return this builder for fluent chaining
   */
  public HeartbeatBuilder withStatisticsInterval(final Duration interval) {
    this.statsInterval = interval;
    return this;
  }

  /**
   * Builds and returns a list of Disposable objects for the configured heartbeat and statistics
   * subscriptions.
   *
   * <p>For each node, two subscriptions are created: one for heartbeats and one for statistics. The
   * statistics interval defaults to 2x the heartbeat interval if not explicitly set.
   *
   * @return a list of Disposable objects for lifecycle management
   */
  public List<Disposable> build() {
    final List<Disposable> disposables = new ArrayList<>();

    // If no nodes are configured or present, skip creating subscriptions
    if (nodes != null && !nodes.isEmpty()) {
      // Use provided heartbeat interval or default
      final Duration heartbeat = hbInterval != null ? hbInterval : defaultInterval;

      // Use provided statistics interval or default to 2x heartbeat interval
      final Duration statistics = statsInterval != null ? statsInterval : heartbeat.multipliedBy(2);

      // Create subscriptions for each node
      for (final String nodeId : nodes) {
        addHeartbeatSubscription(disposables, nodeId, heartbeat);
        addStatisticsSubscription(disposables, nodeId, statistics);
      }
    }

    return disposables;
  }

  /**
   * Adds a heartbeat subscription for the specified node.
   *
   * @param disposables the list to add the disposable to
   * @param nodeId the node identifier
   * @param interval the heartbeat interval
   */
  private void addHeartbeatSubscription(
      final List<Disposable> disposables, final String nodeId, final Duration interval) {
    final Disposable hbDisposable =
        Flux.interval(interval, scheduler)
            .doOnNext(tick -> log.debug("Emitting heartbeat for node: {}, tick: {}", nodeId, tick))
            .flatMap(_ -> reactor.core.publisher.Mono.empty())
            .subscribe();

    disposables.add(hbDisposable);
  }

  /**
   * Adds a statistics subscription for the specified node.
   *
   * @param disposables the list to add the disposable to
   * @param nodeId the node identifier
   * @param interval the statistics interval
   */
  private void addStatisticsSubscription(
      final List<Disposable> disposables, final String nodeId, final Duration interval) {
    final Disposable statsDisposable =
        Flux.interval(interval, scheduler)
            .doOnNext(tick -> log.debug("Emitting statistics for node: {}, tick: {}", nodeId, tick))
            .flatMap(_ -> reactor.core.publisher.Mono.empty())
            .subscribe();

    disposables.add(statsDisposable);
  }
}
