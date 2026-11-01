// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.leader;

import com.infenia.yukta.plugin.control.StopMode;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.control.store.ExecutionControlRegistry;
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
  private final ExecutionControlRegistry registry;

  /** Stops a run. */
  private final ControlBusGateway gateway;

  @Override
  public void afterSingletonsInstantiated() {
    election.onLoss(this::stopAll);
  }

  /* default */ void stopAll() {
    registry
        .findAll()
        .forEach(
            control -> {
              log.atWarn()
                  .addKeyValue("executionId", control.executionId())
                  .log("Leader lease lost, stopping the run");
              gateway
                  .stopExecution(control.executionId(), "leader lease lost", StopMode.KILL)
                  .onErrorResume(_ -> reactor.core.publisher.Mono.empty())
                  .subscribe();
            });
  }
}
