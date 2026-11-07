// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control;

import java.time.Duration;

/**
 * Short forms of {@link ExecutionScheduler} for tests: the shapes its extra constructors used to
 * offer.
 */
public final class ExecutionSchedulerFixture {

  private ExecutionSchedulerFixture() {}

  /** A ExecutionScheduler with the default for what the parameters leave out. */
  public static ExecutionScheduler create(
      final int maxConcurrent,
      final int maxPerWorkflow,
      final int queueCapacity,
      final ExecutionScheduler.OnLimit onLimit,
      final Duration shutdownTimeout,
      final Duration queueTimeout) {
    return new ExecutionScheduler(
        maxConcurrent, maxPerWorkflow, queueCapacity, onLimit, shutdownTimeout, queueTimeout, 0, 0);
  }

  /** A ExecutionScheduler with the default for what the parameters leave out. */
  public static ExecutionScheduler create(
      final int maxConcurrent,
      final int maxPerWorkflow,
      final int queueCapacity,
      final ExecutionScheduler.OnLimit onLimit,
      final Duration shutdownTimeout) {
    return create(
        maxConcurrent, maxPerWorkflow, queueCapacity, onLimit, shutdownTimeout, Duration.ZERO);
  }
}
