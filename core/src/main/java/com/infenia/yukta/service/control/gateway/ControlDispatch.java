// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.gateway;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.store.ExecutionControlStore;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/** What the command-sending parts of the gateway share. */
@Slf4j
class ControlDispatch {

  /** Source identifier for control bus operations. */
  private static final String CONTROL_BUS_SOURCE = "CONTROL_BUS";

  /** Priority level for control commands. */
  /* default */ static final int CONTROL_COMMAND_PRIORITY = 100;

  /** The gateway that delivers commands. */
  private final ControlBusGateway gateway;

  /** Finds live runs. */
  private final ExecutionControlStore executionControlStore;

  /* default */ ControlDispatch(
      final ControlBusGateway gateway, final ExecutionControlStore executionControlStore) {
    this.gateway = gateway;
    this.executionControlStore = executionControlStore;
  }

  /* default */ static <T extends ExecutionControlCommand> Message<T> buildCommand(
      final T command, final int priority) {
    return DefaultMessage.create(null, command)
        .withSourceNodeId(CONTROL_BUS_SOURCE)
        .withPriority(priority)
        .withControl(true);
  }

  /* default */ <T extends ExecutionControlCommand> Mono<Void> executeCommand(
      final Message<T> command) {
    return gateway.executeCommand(command);
  }

  /* default */ ExecutionControl findExecutionOrThrow(final String executionId) {
    return executionControlStore
        .findByExecutionId(executionId)
        .orElseThrow(() -> new NotFoundException("Execution not found: " + executionId));
  }

  /* default */ boolean isLive(final String executionId) {
    return executionControlStore.findByExecutionId(executionId).isPresent();
  }

  /* default */ ExecutionControl requireNodeControl(final String executionId, final String nodeId) {
    final ExecutionControl control = findExecutionOrThrow(executionId);
    if (!control.nodePauseValves().containsKey(nodeId)) {
      throw new NotFoundException("Node not found: " + nodeId);
    }
    return control;
  }
}
