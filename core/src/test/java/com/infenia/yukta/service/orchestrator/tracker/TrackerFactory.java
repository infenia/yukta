// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.tracker;

import com.infenia.yukta.config.YuktaProperties;
import com.infenia.yukta.service.log.ExecutionLogStore;
import com.infenia.yukta.service.log.InMemoryExecutionLogStore;
import com.infenia.yukta.service.streaming.StatusHistoryCache;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.function.LongSupplier;

/** Builds trackers for tests, so they need no constructor of their own. */
public final class TrackerFactory {

  private TrackerFactory() {}

  /** A tracker with an in-memory log, and no observations or listeners. */
  public static DefaultTaskTrackerService tracker(
      final Duration cleanupTtl, final StatusHistoryCache cache) {
    return tracker(cleanupTtl, cache, new InMemoryExecutionLogStore(), new SimpleMeterRegistry());
  }

  /** A tracker without observations or listeners. */
  public static DefaultTaskTrackerService tracker(
      final Duration cleanupTtl,
      final StatusHistoryCache cache,
      final ExecutionLogStore log,
      final MeterRegistry meters) {
    return tracker(cleanupTtl, cache, log, meters, ObservationRegistry.NOOP);
  }

  /** A tracker without listeners. */
  public static DefaultTaskTrackerService tracker(
      final Duration cleanupTtl,
      final StatusHistoryCache cache,
      final ExecutionLogStore log,
      final MeterRegistry meters,
      final ObservationRegistry observations) {
    return tracker(
        cleanupTtl,
        cache,
        log,
        meters,
        observations,
        new com.infenia.yukta.service.supervisor.ExecutionEventFeed());
  }

  /** A tracker with every part given. */
  public static DefaultTaskTrackerService tracker(
      final Duration cleanupTtl,
      final StatusHistoryCache cache,
      final ExecutionLogStore log,
      final MeterRegistry meters,
      final ObservationRegistry observations,
      final com.infenia.yukta.service.supervisor.ExecutionEventFeed feed) {
    return new DefaultTaskTrackerService(
        new YuktaProperties.Tracker(null, 0, cleanupTtl),
        cache,
        log,
        meters,
        observations,
        feed,
        System::nanoTime);
  }

  /** A tracker with the given session settings and a clock to move. */
  public static DefaultTaskTrackerService tracker(
      final YuktaProperties.Tracker settings,
      final StatusHistoryCache cache,
      final LongSupplier clock) {
    return new DefaultTaskTrackerService(
        settings,
        cache,
        new InMemoryExecutionLogStore(),
        new SimpleMeterRegistry(),
        ObservationRegistry.NOOP,
        new com.infenia.yukta.service.supervisor.ExecutionEventFeed(),
        clock);
  }
}
