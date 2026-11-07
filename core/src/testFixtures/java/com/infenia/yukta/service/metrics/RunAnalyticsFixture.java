// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.metrics;

import com.infenia.yukta.service.log.ExecutionLogStore;
import com.infenia.yukta.service.supervisor.ExecutionEventFeed;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Short forms of {@link RunAnalytics} for tests: the shapes its extra constructors used to offer.
 */
public final class RunAnalyticsFixture {

  private RunAnalyticsFixture() {}

  /** A RunAnalytics with the default for what the parameters leave out. */
  public static RunAnalytics create(final ExecutionEventFeed feed) {
    return new RunAnalytics(feed, Clock.systemUTC(), null);
  }

  /** A RunAnalytics with the default for what the parameters leave out. */
  public static RunAnalytics create(
      final ExecutionEventFeed feed, final ObjectProvider<ExecutionLogStore> logStore) {
    return new RunAnalytics(feed, logStore);
  }

  /** A RunAnalytics with the default for what the parameters leave out. */
  public static RunAnalytics create(final ExecutionEventFeed feed, final Clock clock) {
    return new RunAnalytics(feed, clock, null);
  }
}
