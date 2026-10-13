// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.gateway;

import com.infenia.yukta.plugin.control.ExecutionControlCommand.DisableStepModeCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.EnableStepModeCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.PauseNodeCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.ResumeNodeCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.SetBreakpointCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.SignalNodeCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.SkipNodeCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.StepNodeCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.StopNodeCommand;
import com.infenia.yukta.service.control.store.ExecutionControlRegistry;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/** Commands for one node of a live run. */
@Slf4j
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.TooManyMethods"})
final class NodeCommands extends ControlDispatch {

  /* default */ NodeCommands(
      final ControlBusGateway gateway, final ExecutionControlRegistry executionControlRegistry) {
    super(gateway, executionControlRegistry);
  }

  /* default */ Mono<Void> pauseNode(final String executionId, final String nodeId) {
    return Mono.fromSupplier(() -> requireNodeControl(executionId, nodeId))
        .flatMap(
            control ->
                executeCommand(
                    buildCommand(
                        new PauseNodeCommand(executionId, nodeId), CONTROL_COMMAND_PRIORITY)))
        .doOnSubscribe(
            _ ->
                log.atInfo()
                    .addKeyValue("executionId", executionId)
                    .addKeyValue("nodeId", nodeId)
                    .log("Pausing node"))
        .doOnSuccess(
            _ -> log.atDebug().addKeyValue("nodeId", nodeId).log("Node pause command executed"))
        .doOnError(
            err ->
                log.atError()
                    .setCause(err)
                    .addKeyValue("executionId", executionId)
                    .addKeyValue("nodeId", nodeId)
                    .log("Failed to pause node"));
  }

  /* default */ Mono<Void> resumeNode(final String executionId, final String nodeId) {
    return Mono.fromSupplier(() -> requireNodeControl(executionId, nodeId))
        .flatMap(
            control ->
                executeCommand(
                    buildCommand(
                        new ResumeNodeCommand(executionId, nodeId), CONTROL_COMMAND_PRIORITY)))
        .doOnSubscribe(
            _ ->
                log.atInfo()
                    .addKeyValue("executionId", executionId)
                    .addKeyValue("nodeId", nodeId)
                    .log("Resuming node"))
        .doOnSuccess(
            _ -> log.atDebug().addKeyValue("nodeId", nodeId).log("Node resume command executed"))
        .doOnError(
            err ->
                log.atError()
                    .setCause(err)
                    .addKeyValue("executionId", executionId)
                    .addKeyValue("nodeId", nodeId)
                    .log("Failed to resume node"));
  }

  /* default */ Mono<Void> stopNode(
      final String executionId, final String nodeId, final boolean immediate, final String reason) {
    return Mono.fromSupplier(() -> requireNodeControl(executionId, nodeId))
        .flatMap(
            control ->
                executeCommand(
                    buildCommand(
                        new StopNodeCommand(executionId, nodeId, immediate, reason),
                        CONTROL_COMMAND_PRIORITY + 10)))
        .doOnSubscribe(
            _ ->
                log.atInfo()
                    .addKeyValue("executionId", executionId)
                    .addKeyValue("nodeId", nodeId)
                    .addKeyValue("immediate", immediate)
                    .addKeyValue("reason", reason)
                    .log("Stopping node"))
        .doOnSuccess(
            _ -> log.atDebug().addKeyValue("nodeId", nodeId).log("Node stop command executed"))
        .doOnError(
            err ->
                log.atError()
                    .setCause(err)
                    .addKeyValue("executionId", executionId)
                    .addKeyValue("nodeId", nodeId)
                    .log("Failed to stop node"));
  }

  /* default */ Mono<Void> addBreakpoint(
      final String executionId, final String nodeId, final String field, final String equalsValue) {
    return breakpoint(executionId, nodeId, field, equalsValue);
  }

  /* default */ Mono<Void> signalNode(
      final String executionId,
      final String nodeId,
      final String name,
      final String decision,
      final String data) {
    return Mono.fromSupplier(() -> requireNodeControl(executionId, nodeId))
        .flatMap(
            control ->
                executeCommand(
                    buildCommand(
                        new SignalNodeCommand(executionId, nodeId, name, decision, data),
                        CONTROL_COMMAND_PRIORITY)));
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
                        new SetBreakpointCommand(executionId, nodeId, field, equalsValue),
                        CONTROL_COMMAND_PRIORITY)));
  }

  /* default */ Mono<Void> skipNode(
      final String executionId, final String nodeId, final boolean skip) {
    return Mono.fromSupplier(() -> requireNodeControl(executionId, nodeId))
        .flatMap(
            control ->
                executeCommand(
                    buildCommand(
                        new SkipNodeCommand(executionId, nodeId, skip), CONTROL_COMMAND_PRIORITY)))
        .doOnSubscribe(
            _ ->
                log.atInfo()
                    .addKeyValue("executionId", executionId)
                    .addKeyValue("nodeId", nodeId)
                    .addKeyValue("skip", skip)
                    .log("Toggling node skip"))
        .doOnSuccess(
            _ ->
                log.atDebug()
                    .addKeyValue("nodeId", nodeId)
                    .addKeyValue("skip", skip)
                    .log("Node skip command executed"))
        .doOnError(
            err ->
                log.atError()
                    .setCause(err)
                    .addKeyValue("executionId", executionId)
                    .addKeyValue("nodeId", nodeId)
                    .log("Failed to skip node"));
  }

  /* default */ Mono<Void> enableStepMode(final String executionId, final String nodeId) {
    return Mono.fromSupplier(() -> requireNodeControl(executionId, nodeId))
        .flatMap(
            control ->
                executeCommand(
                    buildCommand(
                        new EnableStepModeCommand(executionId, nodeId), CONTROL_COMMAND_PRIORITY)))
        .doOnSubscribe(
            _ ->
                log.atInfo()
                    .addKeyValue("executionId", executionId)
                    .addKeyValue("nodeId", nodeId)
                    .log("Enabling step mode"))
        .doOnSuccess(_ -> log.atDebug().addKeyValue("nodeId", nodeId).log("Step mode enabled"))
        .doOnError(
            err ->
                log.atError()
                    .setCause(err)
                    .addKeyValue("executionId", executionId)
                    .addKeyValue("nodeId", nodeId)
                    .log("Failed to enable step mode"));
  }

  /* default */ Mono<Void> disableStepMode(final String executionId, final String nodeId) {
    return Mono.fromSupplier(() -> requireNodeControl(executionId, nodeId))
        .flatMap(
            control ->
                executeCommand(
                    buildCommand(
                        new DisableStepModeCommand(executionId, nodeId), CONTROL_COMMAND_PRIORITY)))
        .doOnSubscribe(
            _ ->
                log.atInfo()
                    .addKeyValue("executionId", executionId)
                    .addKeyValue("nodeId", nodeId)
                    .log("Disabling step mode"))
        .doOnSuccess(_ -> log.atDebug().addKeyValue("nodeId", nodeId).log("Step mode disabled"))
        .doOnError(
            err ->
                log.atError()
                    .setCause(err)
                    .addKeyValue("executionId", executionId)
                    .addKeyValue("nodeId", nodeId)
                    .log("Failed to disable step mode"));
  }

  /* default */ Mono<Void> stepNode(final String executionId, final String nodeId) {
    return Mono.fromSupplier(() -> requireNodeControl(executionId, nodeId))
        .flatMap(
            control ->
                executeCommand(
                    buildCommand(
                        new StepNodeCommand(executionId, nodeId), CONTROL_COMMAND_PRIORITY)))
        .doOnSubscribe(
            _ ->
                log.atInfo()
                    .addKeyValue("executionId", executionId)
                    .addKeyValue("nodeId", nodeId)
                    .log("Stepping through node"))
        .doOnSuccess(
            _ -> log.atDebug().addKeyValue("nodeId", nodeId).log("Node step command executed"))
        .doOnError(
            err ->
                log.atError()
                    .setCause(err)
                    .addKeyValue("executionId", executionId)
                    .addKeyValue("nodeId", nodeId)
                    .log("Failed to step node"));
  }
}
