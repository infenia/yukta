// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.processor;

import com.infenia.yukta.plugin.control.ExecutionControlCommand.EscalateInputCommand;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.input.InputState;
import com.infenia.yukta.service.ConflictException;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.input.InputBroker;
import com.infenia.yukta.service.input.InputCommandGuard;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Processor for {@link EscalateInputCommand}: hands a request up when the sender is eligible for
 * it. Anyone eligible may hand a request up, so the risk rule does not apply; the audience, tier
 * and scope rules do. A request that has already ended cannot be handed up.
 */
@Component
public class EscalateInputCommandProcessor {

  /** Checks leadership, the request, its run and the sender. */
  private final InputCommandGuard guard;

  /** Hands the request up. */
  private final InputBroker broker;

  /**
   * Creates the processor.
   *
   * @param guard checks leadership, the request, its run and the sender
   * @param broker hands the request up
   */
  @SuppressFBWarnings("EI_EXPOSE_REP2") // shared Spring beans, held on purpose
  public EscalateInputCommandProcessor(final InputCommandGuard guard, final InputBroker broker) {
    this.guard = guard;
    this.broker = broker;
  }

  /** Applies the command. */
  public Mono<Void> process(final EscalateInputCommand escalate) {
    return guard
        .clear(escalate.executionId(), escalate.requestId(), true)
        .flatMap(
            cleared ->
                broker.escalate(
                    escalate.requestId(),
                    escalate.reason(),
                    escalate.advice(),
                    cleared.responder()))
        .switchIfEmpty(
            Mono.error(
                () -> new NotFoundException("Input request not found: " + escalate.requestId())))
        .flatMap(EscalateInputCommandProcessor::requireEscalated)
        .then();
  }

  private static Mono<InputRequest> requireEscalated(final InputRequest request) {
    return request.state() == InputState.ESCALATED
        ? Mono.just(request)
        : Mono.error(
            new ConflictException(
                "Input request "
                    + request.id()
                    + " is "
                    + request.state()
                    + ", so it cannot be handed up"));
  }
}
