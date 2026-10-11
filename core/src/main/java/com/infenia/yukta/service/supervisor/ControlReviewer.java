// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import com.infenia.yukta.plugin.control.ControlRequest;
import com.infenia.yukta.plugin.control.Decision;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import java.util.Comparator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Asks every {@link ExecutionSupervisor} whether a control command may run. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ControlReviewer {

  /** The registered supervisors; looked up per review so late registrations count. */
  private final ObjectProvider<ExecutionSupervisor> supervisors;

  /**
   * Reviews a command in supervisor order, stopping at the first refusal.
   *
   * @param request the command and who sent it
   * @return completes if every supervisor allows it; fails with {@link ControlRejectedException}
   *     otherwise. A supervisor that fails to answer counts as a refusal.
   */
  public Mono<Void> review(final ControlRequest request) {
    return Flux.fromStream(
            () -> supervisors.stream().sorted(Comparator.comparingInt(ExecutionSupervisor::order)))
        .concatMap(
            supervisor ->
                Mono.defer(() -> supervisor.review(request))
                    .onErrorResume(
                        error -> {
                          log.atWarn()
                              .setCause(error)
                              .addKeyValue("supervisor", supervisor.id())
                              .log("Supervisor review failed; refusing the command");
                          return Mono.just(Decision.deny("review failed"));
                        })
                    .filter(decision -> !decision.allowed())
                    .map(decision -> supervisor.id() + ": " + decision.reason()))
        .next()
        .flatMap(reason -> Mono.<Void>error(new ControlRejectedException(reason)));
  }
}
