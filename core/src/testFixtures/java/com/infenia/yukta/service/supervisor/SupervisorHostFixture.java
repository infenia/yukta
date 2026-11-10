// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.service.control.SupervisedControl;
import com.infenia.yukta.service.control.directive.DirectiveDispatcher;
import com.infenia.yukta.service.log.ExecutionLogStore;
import com.infenia.yukta.service.review.ExecutionEventFeed;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Short forms of {@link SupervisorHost} for tests: the shapes its extra constructors used to offer.
 */
public final class SupervisorHostFixture {

  private SupervisorHostFixture() {}

  /** A SupervisorHost with the default for what the parameters leave out. */
  public static SupervisorHost create(
      final ObjectProvider<ExecutionSupervisor> supervisors,
      final ExecutionEventFeed feed,
      final DirectiveDispatcher dispatcher,
      final ExecutionLogStore executionLog,
      final MeterRegistry meters,
      final boolean dryRun,
      final int maxCommandsPerSecond,
      final int bufferSize) {
    return new SupervisorHost(
        supervisors,
        feed,
        dispatcher,
        new SupervisedControl(null, dispatcher, null, null),
        executionLog,
        meters,
        dryRun,
        maxCommandsPerSecond,
        bufferSize,
        new TurnAroundGuard(
            new com.infenia.yukta.config.YuktaProperties.TurnAround(
                java.time.Duration.ofSeconds(60), 4, java.time.Duration.ofMinutes(5))));
  }
}
