// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.processor;

import com.infenia.yukta.plugin.control.ExecutionControlCommand.SignalNodeCommand;
import com.infenia.yukta.service.ConflictException;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.control.store.ExecutionControlStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Processor for signal commands, kept until the signal API is removed: it answers the input request
 * that a node holds for the signal (for example {@code WAIT_FOR_SIGNAL}) through {@link
 * InputSignalBridge}, with the same authorisation as an answer. A signal nobody waits for is
 * refused, so the sender learns that it did not arrive.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SignalNodeCommandProcessor {

  /** The execution control registry, to find the run and its workflow. */
  private final ExecutionControlStore registry;

  /** Answers an input request that stands for the signal, if there is one. */
  @Nullable private ObjectProvider<InputSignalBridge> inputBridge;

  /**
   * Lets the broker take a signal that answers an input request.
   *
   * @param provider the bridge, if the application has input requests
   */
  @Autowired(required = false)
  public void setInputBridge(final ObjectProvider<InputSignalBridge> provider) {
    this.inputBridge = provider;
  }

  /**
   * Applies the command: an open input request of the node takes it. Waits are input requests now,
   * so a node that has none open is not waiting.
   */
  public Mono<Void> process(final SignalNodeCommand signal) {
    return Mono.defer(
        () -> {
          if (registry.findByExecutionId(signal.executionId()).isEmpty()) {
            throw new NotFoundException("Execution not found: " + signal.executionId());
          }
          final ObjectProvider<InputSignalBridge> provider = inputBridge;
          final InputSignalBridge bridge = provider == null ? null : provider.getIfAvailable();
          return bridge == null
              ? Mono.<Void>error(() -> notWaiting(signal))
              : bridge
                  .waitingFor(signal)
                  .switchIfEmpty(Mono.error(() -> notWaiting(signal)))
                  .flatMap(request -> bridge.answer(signal, request));
        });
  }

  private static ConflictException notWaiting(final SignalNodeCommand signal) {
    return new ConflictException(
        "Node "
            + signal.nodeId()
            + " is not waiting for signal '"
            + signal.name()
            + "'; answer an input request with the answer_input command");
  }
}
