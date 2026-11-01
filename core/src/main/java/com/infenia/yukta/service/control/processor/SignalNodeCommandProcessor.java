// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.processor;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.control.ControlSignalProcessor;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.SignalNodeCommand;
import com.infenia.yukta.plugin.control.WorkflowDirective;
import com.infenia.yukta.service.ConflictException;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.store.ActivePluginRegistry;
import com.infenia.yukta.service.control.store.ExecutionControlStore;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Processor for signal commands: hands the signal to the plugin of the node, which takes it only
 * while it waits for it (for example {@code WAIT_FOR_SIGNAL}). A signal nobody waits for is
 * refused, so the sender learns that it did not arrive.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SignalNodeCommandProcessor implements ControlSignalProcessor {

  /** Answer of a plugin that took the signal. */
  private static final String ACCEPTED = "ACCEPTED";

  /** The wait has not begun yet; the signal is kept for it. */
  private static final String QUEUED = "QUEUED";

  /** The execution control registry, to find the run and its workflow. */
  private final ExecutionControlStore registry;

  /** Finds the plugin that serves a node. */
  private final ActivePluginRegistry plugins;

  /** Answers an input request that stands for the signal, if there is one. */
  @Nullable private ObjectProvider<InputSignalBridge> inputBridge;

  @Override
  public boolean canProcess(final ExecutionControlCommand command) {
    return command instanceof SignalNodeCommand;
  }

  /**
   * Lets the broker take a signal that answers an input request.
   *
   * @param provider the bridge, if the application has input requests
   */
  @Autowired(required = false)
  public void setInputBridge(final ObjectProvider<InputSignalBridge> provider) {
    this.inputBridge = provider;
  }

  @Override
  public Mono<WorkflowDirective> process(final ExecutionControlCommand command) {
    final SignalNodeCommand signal = (SignalNodeCommand) command;
    final ObjectProvider<InputSignalBridge> provider = inputBridge;
    final InputSignalBridge bridge = provider == null ? null : provider.getIfAvailable();
    if (bridge == null) {
      return deliver(signal);
    }
    return bridge
        .waitingFor(signal)
        .map(Optional::of)
        .defaultIfEmpty(Optional.empty())
        .flatMap(
            waiting ->
                waiting.isPresent() ? bridge.answer(signal, waiting.get()) : deliver(signal));
  }

  /** The older path: the plugin of the node takes the signal while it waits for it. */
  private Mono<WorkflowDirective> deliver(final SignalNodeCommand signal) {
    return Mono.defer(
        () -> {
          final ExecutionControl control =
              registry
                  .findByExecutionId(signal.executionId())
                  .orElseThrow(
                      () -> new NotFoundException("Execution not found: " + signal.executionId()));
          final var plugin =
              plugins
                  .lookup(control.workflowId(), signal.executionId(), signal.nodeId())
                  .orElseThrow(() -> new NotFoundException("Node not found: " + signal.nodeId()));
          final Map<String, Object> body = new ConcurrentHashMap<>();
          body.put("executionId", signal.executionId());
          body.put("nodeId", signal.nodeId());
          body.put("name", signal.name());
          body.put("decision", signal.decision());
          body.put("data", Objects.toString(signal.data(), ""));
          final Message<?> message = DefaultMessage.create(null, body).withControl(true);
          return plugin
              .onControlSignal(message)
              .filter(
                  answer ->
                      ACCEPTED.equals(String.valueOf(answer.payload()))
                          || QUEUED.equals(String.valueOf(answer.payload())))
              .switchIfEmpty(
                  Mono.error(
                      () ->
                          new ConflictException(
                              "Node "
                                  + signal.nodeId()
                                  + " is not waiting for signal '"
                                  + signal.name()
                                  + "'")))
              .doOnNext(
                  _ ->
                      log.atDebug()
                          .addKeyValue("executionId", signal.executionId())
                          .addKeyValue("nodeId", signal.nodeId())
                          .addKeyValue("signal", signal.name())
                          .log("Signal delivered"))
              .then(Mono.<WorkflowDirective>empty());
        });
  }
}
