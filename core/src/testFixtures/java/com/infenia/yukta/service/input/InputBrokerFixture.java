// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.input;

import com.infenia.yukta.config.YuktaProperties;
import com.infenia.yukta.service.input.route.RouteResolver;
import com.infenia.yukta.service.log.ExecutionLogStore;
import com.infenia.yukta.service.redact.InputRedactor;
import com.infenia.yukta.service.review.ExecutionEventFeed;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Map;

/**
 * Short forms of {@link InputBroker} for tests: the shapes its extra constructors used to offer.
 */
public final class InputBrokerFixture {

  private InputBrokerFixture() {}

  /** A InputBroker with the default for what the parameters leave out. */
  public static InputBroker create(
      final InputRequestStore store,
      final ExecutionEventFeed feed,
      final ExecutionLogStore executionLog,
      final MeterRegistry meters,
      final InputRedactor redactor,
      final YuktaProperties.Input limits,
      final Clock clock) {
    return new InputBroker(
        store,
        feed,
        executionLog,
        meters,
        redactor,
        limits,
        clock,
        new RouteResolver(Map.of(), null),
        null);
  }
}
