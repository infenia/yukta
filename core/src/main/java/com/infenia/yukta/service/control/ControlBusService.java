// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.core.Plugin;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.runtime.store.ActivePluginRegistry;
import jakarta.validation.constraints.NotBlank;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Service for managing the system's Control Bus.
 *
 * <p>Applies execution control commands in order and sends a command to a live node.
 */
@Slf4j
@Service
public class ControlBusService {

  /** Registry of plugin instances actively servicing workflow nodes. */
  private final ActivePluginRegistry activePluginRegistry;

  /** Applies execution control commands in order and reports when they were applied. */
  @SuppressWarnings("PMD.AvoidUsingVolatile")
  private volatile Function<ExecutionControlCommand, Mono<Void>> commandExecutor;

  /**
   * Constructor for ControlBusService.
   *
   * @param activePluginRegistry the registry of plugin instances actively servicing nodes
   */
  public ControlBusService(final ActivePluginRegistry activePluginRegistry) {
    this.activePluginRegistry = activePluginRegistry;
  }

  /**
   * Registers the component that applies execution control commands.
   *
   * <p>Once set, {@link #emit} completes for a command only after it has been applied (or fails
   * with the reason it was rejected), and commands for one execution are applied in order.
   *
   * @param executor applies one command
   */
  public void setCommandExecutor(final Function<ExecutionControlCommand, Mono<Void>> executor) {
    this.commandExecutor = executor;
  }

  /**
   * Emit a control command to the bus.
   *
   * <p>The command is applied in order and this completes only once it has been applied (or fails
   * with the reason it was rejected).
   *
   * @param signal the control command message
   * @return a Mono that completes when the command has been applied
   */
  public Mono<Void> emit(final Message<?> signal) {
    final Function<ExecutionControlCommand, Mono<Void>> executor = commandExecutor;
    if (executor != null && signal.payload() instanceof ExecutionControlCommand command) {
      return executor.apply(command);
    }
    final Object payload = signal.payload();
    return Mono.error(
        new IllegalArgumentException(
            "Not an execution control command: "
                + (payload == null ? "null" : payload.getClass().getSimpleName())));
  }

  /**
   * Send a command to a specific node in a workflow and wait for response.
   *
   * @param workflowId the workflow identifier
   * @param executionId the run the node belongs to
   * @param nodeId the target node identifier
   * @param command the command message
   * @return a Mono of the response message
   */
  public Mono<Message<?>> sendCommand(
      @NotBlank final String workflowId,
      @NotBlank final String executionId,
      @NotBlank final String nodeId,
      final Message<?> command) {
    final Plugin plugin = activePluginRegistry.lookup(workflowId, executionId, nodeId).orElse(null);
    if (plugin == null) {
      log.atWarn()
          .addArgument(nodeId)
          .addArgument(workflowId)
          .log("Command target node not found: {} in workflow: {}");
      return Mono.error(new NotFoundException("Node not found: " + workflowId + "/" + nodeId));
    }
    final Object payload = command.payload();
    final String commandType = payload != null ? payload.getClass().getSimpleName() : "null";
    log.atDebug()
        .addArgument(commandType)
        .addArgument(nodeId)
        .addArgument(workflowId)
        .log("Sending control command of type {} to node: {} in workflow: {}");
    return plugin
        .onControlSignal(command)
        .doOnSuccess(
            response ->
                log.atDebug()
                    .addArgument(nodeId)
                    .addArgument(workflowId)
                    .log("Received response from node: {} in workflow: {}"))
        .doOnError(
            err ->
                log.atError()
                    .addArgument(nodeId)
                    .addArgument(workflowId)
                    .setCause(err)
                    .log("Error sending command to node: {} in workflow: {}"));
  }
}
