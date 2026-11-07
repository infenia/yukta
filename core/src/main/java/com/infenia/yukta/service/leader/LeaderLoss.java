// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.leader;

import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.gateway.DefaultControlBusGateway;
import com.infenia.yukta.service.control.store.ExecutionControlStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * Stops the live runs when this server loses the leader lease. The new leader aborts and may resume
 * them, so carrying on here would run their side effects twice. New starts are already refused,
 * because the scheduler admits runs only for the leader.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LeaderLoss implements SmartInitializingSingleton {

  /** Tells when the lease is lost. */
  private final LeaderElection election;

  /** The live runs. */
  private final ExecutionControlStore registry;

  /** Stops a run. */
  private final DefaultControlBusGateway gateway;

  @Override
  public void afterSingletonsInstantiated() {
    election.onLoss(this::stopAll);
  }

  // One failed kill must not spare the other runs.
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  /* default */ void stopAll() {
    // No supervisor review and no command queue: nothing may keep a run going once the lease is
    // gone, also a restarted or resumed run that the starter does not track.
    for (final ExecutionControl control : registry.findAll()) {
      log.atWarn()
          .addKeyValue("executionId", control.executionId())
          .log("Leader lease lost, stopping the run");
      try {
        gateway.killLocally(control.executionId(), "leader lease lost");
      } catch (RuntimeException e) {
        log.atError()
            .setCause(e)
            .addKeyValue("executionId", control.executionId())
            .log("Kill failed");
      }
    }
    gateway.cancelRunningExecutions();
  }
}
