// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.architecture.fixture;

import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import reactor.core.publisher.Mono;

/** Pauses a run straight on the gateway; the self-test expects the rule to object. */
public final class BadControlCaller {
  private BadControlCaller() {}

  /**
   * Skips the one entry.
   *
   * @param gateway the gateway
   * @return the pause
   */
  public static Mono<Void> pause(final ControlBusGateway gateway) {
    return gateway.pauseWorkflow("e1");
  }
}
