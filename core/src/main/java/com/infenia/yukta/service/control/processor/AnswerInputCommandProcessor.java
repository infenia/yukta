// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.processor;

import com.infenia.yukta.plugin.control.ControlSignalProcessor;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.AnswerInputCommand;
import com.infenia.yukta.plugin.control.WorkflowDirective;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.input.InputResponse;
import com.infenia.yukta.plugin.input.InputState;
import com.infenia.yukta.service.ConflictException;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.input.AnswerShape;
import com.infenia.yukta.service.input.InputBroker;
import com.infenia.yukta.service.input.InputCommandGuard;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Processor for {@link AnswerInputCommand}: checks that the sender may answer this request and that
 * the answer has the shape the request asks for, then lets the broker take it. A refused answer
 * leaves the request open. The dispatcher has already ordered the command after the earlier ones
 * for the run, let the supervisors review it and will audit it.
 *
 * <p>An answer that loses a race against an earlier answer is not an error: the request is
 * answered. An answer to a request that has expired or been cancelled, or whose version moved, is a
 * conflict.
 */
@Component
public class AnswerInputCommandProcessor implements ControlSignalProcessor {

  /** Checks leadership, the request, its run and the sender. */
  private final InputCommandGuard guard;

  /** Takes the answer. */
  private final InputBroker broker;

  /** The time of the answer. */
  private final Clock clock;

  /**
   * Creates the processor.
   *
   * @param guard checks leadership, the request, its run and the sender
   * @param broker takes the answer
   */
  @Autowired
  @SuppressFBWarnings("EI_EXPOSE_REP2") // shared Spring beans, held on purpose
  public AnswerInputCommandProcessor(final InputCommandGuard guard, final InputBroker broker) {
    this(guard, broker, Clock.systemUTC());
  }

  /**
   * Creates the processor with a clock of its own, for tests.
   *
   * @param guard checks leadership, the request, its run and the sender
   * @param broker takes the answer
   * @param clock the time of the answer
   */
  @SuppressFBWarnings("EI_EXPOSE_REP2") // shared Spring beans, held on purpose
  public AnswerInputCommandProcessor(
      final InputCommandGuard guard, final InputBroker broker, final Clock clock) {
    this.guard = guard;
    this.broker = broker;
    this.clock = clock;
  }

  @Override
  public boolean canProcess(final ExecutionControlCommand command) {
    return command instanceof AnswerInputCommand;
  }

  @Override
  public Mono<WorkflowDirective> process(final ExecutionControlCommand command) {
    final AnswerInputCommand answer = (AnswerInputCommand) command;
    return guard
        .clear(answer.executionId(), answer.requestId(), false)
        .flatMap(
            cleared -> {
              AnswerShape.check(cleared.request(), answer);
              final InputResponse response =
                  new InputResponse(
                      answer.requestId(),
                      answer.decision(),
                      answer.text(),
                      answer.data(),
                      cleared.responder(),
                      answer.rationale(),
                      answer.confidence(),
                      clock.instant());
              return broker.answer(answer.requestId(), answer.expectedVersion(), response);
            })
        .switchIfEmpty(
            Mono.error(
                () -> new NotFoundException("Input request not found: " + answer.requestId())))
        .flatMap(AnswerInputCommandProcessor::requireAnswered)
        .then(Mono.<WorkflowDirective>empty());
  }

  private static Mono<InputRequest> requireAnswered(final InputRequest request) {
    return request.state() == InputState.ANSWERED
        ? Mono.just(request)
        : Mono.error(
            new ConflictException(
                "Input request "
                    + request.id()
                    + " is "
                    + request.state()
                    + " (version "
                    + request.version()
                    + "), so the answer was not taken"));
  }
}
