// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.processor;

import com.infenia.yukta.plugin.control.ExecutionControlCommand.AnswerInputCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.SignalNodeCommand;
import com.infenia.yukta.plugin.control.WorkflowDirective;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.service.input.InputBroker;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Map;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Lets the signal command answer an open input request, so a client that still sends a signal
 * reaches a node that waits on a request and the answer passes the same authorisation (a HIGH risk
 * approval stays with humans). A request is named by its signal when its context carries the
 * signal's name under {@value InputBroker#SIGNAL_CONTEXT_KEY}; where there is none, the signal
 * takes the older path.
 */
@Component
public class InputSignalBridge {

  /** Finds the request. */
  private final InputBroker broker;

  /** Answers it. */
  private final AnswerInputCommandProcessor answers;

  /**
   * Creates the bridge.
   *
   * @param broker finds the request
   * @param answers answers it
   */
  @SuppressFBWarnings("EI_EXPOSE_REP2") // shared Spring beans, held on purpose
  public InputSignalBridge(final InputBroker broker, final AnswerInputCommandProcessor answers) {
    this.broker = broker;
    this.answers = answers;
  }

  /**
   * The request that waits for a signal.
   *
   * @param signal the signal
   * @return the open request of the node that was created for the signal's name, or empty
   */
  public Mono<InputRequest> waitingFor(final SignalNodeCommand signal) {
    return broker
        .listOpen(null, signal.executionId())
        .filter(request -> request.nodeId().equals(signal.nodeId()))
        .filter(request -> isNamed(request, signal.name()))
        .next();
  }

  /**
   * Answers the request with the signal's decision, as the sender of the signal.
   *
   * @param signal the signal
   * @param request the request that waits for it
   * @return completes when the answer is taken; errors as an answer does
   */
  public Mono<WorkflowDirective> answer(
      final SignalNodeCommand signal, final InputRequest request) {
    return answers.process(
        new AnswerInputCommand(
            signal.executionId(),
            request.id(),
            null,
            signal.decision(),
            signal.data(),
            Map.of(),
            null,
            null));
  }

  private static boolean isNamed(final InputRequest request, final String name) {
    return name.equals(request.spec().context().get(InputBroker.SIGNAL_CONTEXT_KEY));
  }
}
