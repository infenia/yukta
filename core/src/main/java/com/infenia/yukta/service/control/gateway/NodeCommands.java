// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.gateway;

import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.AnswerInputCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.DisableStepModeCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.EnableStepModeCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.EscalateInputCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.PauseNodeCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.ResumeNodeCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.SetBreakpointCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.SkipNodeCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.StepNodeCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.StopNodeCommand;
import com.infenia.yukta.service.runtime.store.ExecutionControlStore;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/** Commands for one node of a live run. */
@Slf4j
@SuppressWarnings("PMD.TooManyMethods")
final class NodeCommands extends ControlDispatch {

  /* default */ NodeCommands(
      final ControlBusGateway gateway, final ExecutionControlStore executionControlStore) {
    super(gateway, executionControlStore);
  }

  /* default */ Mono<Void> pauseNode(final String executionId, final String nodeId) {
    return nodeCommand(new PauseNodeCommand(executionId, nodeId), "pause");
  }

  /* default */ Mono<Void> resumeNode(final String executionId, final String nodeId) {
    return nodeCommand(new ResumeNodeCommand(executionId, nodeId), "resume");
  }

  /* default */ Mono<Void> stopNode(
      final String executionId, final String nodeId, final boolean immediate, final String reason) {
    return nodeCommand(new StopNodeCommand(executionId, nodeId, immediate, reason), "stop");
  }

  /* default */ Mono<Void> addBreakpoint(
      final String executionId, final String nodeId, final String field, final String equalsValue) {
    return breakpoint(executionId, nodeId, field, equalsValue);
  }

  /* default */ Mono<Void> answerInput(final AnswerInputCommand command) {
    return Mono.fromSupplier(() -> findExecutionOrThrow(command.executionId()))
        .flatMap(_ -> executeCommand(buildCommand(command)));
  }

  /* default */ Mono<Void> escalateInput(final EscalateInputCommand command) {
    return Mono.fromSupplier(() -> findExecutionOrThrow(command.executionId()))
        .flatMap(_ -> executeCommand(buildCommand(command)));
  }

  /* default */ Mono<Void> clearBreakpoint(final String executionId, final String nodeId) {
    return breakpoint(executionId, nodeId, null, null);
  }

  private Mono<Void> breakpoint(
      final String executionId, final String nodeId, final String field, final String equalsValue) {
    return Mono.fromSupplier(() -> requireNodeControl(executionId, nodeId))
        .flatMap(
            control ->
                executeCommand(
                    buildCommand(
                        new SetBreakpointCommand(executionId, nodeId, field, equalsValue))));
  }

  /* default */ Mono<Void> skipNode(
      final String executionId, final String nodeId, final boolean skip) {
    return nodeCommand(new SkipNodeCommand(executionId, nodeId, skip), "skip");
  }

  /* default */ Mono<Void> enableStepMode(final String executionId, final String nodeId) {
    return nodeCommand(new EnableStepModeCommand(executionId, nodeId), "enable step mode on");
  }

  /* default */ Mono<Void> disableStepMode(final String executionId, final String nodeId) {
    return nodeCommand(new DisableStepModeCommand(executionId, nodeId), "disable step mode on");
  }

  /* default */ Mono<Void> stepNode(final String executionId, final String nodeId) {
    return nodeCommand(new StepNodeCommand(executionId, nodeId), "step");
  }

  /** Checks the node, runs the command and logs it the same way for every node command. */
  private Mono<Void> nodeCommand(final ExecutionControlCommand command, final String verb) {
    final String executionId = command.executionId();
    final String nodeId = command.nodeId();
    return Mono.fromSupplier(() -> requireNodeControl(executionId, nodeId))
        .flatMap(control -> executeCommand(buildCommand(command)))
        .doOnSubscribe(
            _ ->
                log.atInfo()
                    .addKeyValue("executionId", executionId)
                    .addKeyValue("nodeId", nodeId)
                    .addKeyValue("command", command)
                    .log("Going to {} node", verb))
        .doOnSuccess(
            _ -> log.atDebug().addKeyValue("nodeId", nodeId).log("Node {} command executed", verb))
        .doOnError(
            err ->
                log.atError()
                    .setCause(err)
                    .addKeyValue("executionId", executionId)
                    .addKeyValue("nodeId", nodeId)
                    .log("Failed to {} node", verb));
  }
}
