// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.worker;

import com.infenia.yukta.config.YuktaProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;

/**
 * Short forms of {@link WorkerRegistry} for tests: the shapes its extra constructors used to offer.
 */
public final class WorkerRegistryFixture {

  private WorkerRegistryFixture() {}

  /** A WorkerRegistry with the default for what the parameters leave out. */
  public static WorkerRegistry of(final YuktaProperties properties, final MeterRegistry meters) {
    return new WorkerRegistry(properties, meters);
  }

  /** A WorkerRegistry with the default for what the parameters leave out. */
  public static WorkerRegistry of(final Duration lease, final MeterRegistry meters) {
    return new WorkerRegistry(lease, meters, Clock.systemUTC());
  }
}
