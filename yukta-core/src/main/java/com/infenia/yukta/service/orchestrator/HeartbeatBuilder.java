/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.infenia.yukta.service.orchestrator;

import com.infenia.yukta.plugin.gateway.ControlBusGateway;
import com.infenia.yukta.plugin.message.DefaultMessage;
import com.infenia.yukta.plugin.message.control.ControlHeartbeat;
import com.infenia.yukta.plugin.message.control.ControlStatistics;
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

  private final ControlBusGateway controlBusGateway;
  private final Duration defaultInterval;
  private final Scheduler scheduler;

  @Nullable private List<String> nodes;

  @Nullable private Duration hbInterval;

  @Nullable private Duration statsInterval;

  /**
   * Creates a new HeartbeatBuilder instance.
   *
   * @param controlBusGateway the control bus gateway for emitting heartbeats and statistics
   * @param defaultInterval the default interval for heartbeat emissions
   * @param scheduler the scheduler for Flux.interval() operations
   */
  public HeartbeatBuilder(
      final ControlBusGateway controlBusGateway,
      final Duration defaultInterval,
      final Scheduler scheduler) {
    this.controlBusGateway = controlBusGateway;
    this.defaultInterval = defaultInterval;
    this.scheduler = scheduler;
  }

  /**
   * Sets the list of nodes for which to emit heartbeats and statistics.
   *
   * @param nodeList the list of node identifiers
   * @return this builder for fluent chaining
   */
  public HeartbeatBuilder forNodes(final List<String> nodeList) {
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
            .flatMap(
                tick -> {
                  final long uptime = System.currentTimeMillis();
                  final ControlHeartbeat heartbeat = new ControlHeartbeat(nodeId, uptime);
                  return controlBusGateway.emit(
                      DefaultMessage.create(null, heartbeat)
                          .withSourceNodeId(nodeId)
                          .withControl(true));
                })
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
            .flatMap(
                tick -> {
                  final ControlStatistics stats =
                      new ControlStatistics(nodeId, 0.0, 0.0, java.util.Map.of());
                  return controlBusGateway.emit(
                      DefaultMessage.create(null, stats)
                          .withSourceNodeId(nodeId)
                          .withControl(true));
                })
            .subscribe();

    disposables.add(statsDisposable);
  }
}
