// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.model.api.ApiResponse;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.supervisor.ControlReviewer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Who carries each command for a node, a workflow's run level or the server, and whether people may
 * send it, so a client can disable a control and say why before anyone clicks it. The role of the
 * key still applies on top (see {@code /api/me}).
 */
@RestController
@RequestMapping("/api/supervision")
@RequiredArgsConstructor
@Tag(name = "Supervision API", description = "Who may do what to a node, a run or the server")
public class SupervisionController {

  /** The commands of a node, by the name a client shows. */
  private static final Map<String, Supplier<ExecutionControlCommand>> NODE_COMMANDS =
      Map.of(
          "pauseNode", () -> new ExecutionControlCommand.PauseNodeCommand("e", "n"),
          "resumeNode", () -> new ExecutionControlCommand.ResumeNodeCommand("e", "n"),
          "stopNode", () -> new ExecutionControlCommand.StopNodeCommand("e", "n", false, null),
          "skipNode", () -> new ExecutionControlCommand.SkipNodeCommand("e", "n", true),
          "stepNode", () -> new ExecutionControlCommand.StepNodeCommand("e", "n"));

  /** The commands of a run, by the name a client shows. */
  private static final Map<String, Supplier<ExecutionControlCommand>> RUN_COMMANDS =
      Map.of(
          "start", () -> new ExecutionControlCommand.StartWorkflowCommand("s", "w", Map.of(), null),
          "pause", () -> new ExecutionControlCommand.PauseWorkflowCommand("e"),
          "resume", () -> new ExecutionControlCommand.ResumeWorkflowCommand("e"),
          "stop", () -> new ExecutionControlCommand.StopWorkflowCommand("e", null),
          "restart", () -> new ExecutionControlCommand.RestartCommand("e", "e2"),
          "priority", () -> new ExecutionControlCommand.SetPriorityCommand("e", 0),
          "recover", () -> new ExecutionControlCommand.RecoverCommand("e"));

  /** The commands of the server, by the name a client shows. */
  private static final Map<String, Supplier<ExecutionControlCommand>> SERVER_COMMANDS =
      Map.of(
          "setLimits", () -> new ExecutionControlCommand.SetLimitsCommand(0, 0),
          "drain", () -> new ExecutionControlCommand.DrainCommand(true));

  /** Decides who carries a command. */
  private final ControlReviewer reviewer;

  /** Finds the session and workflow of a run. */
  private final ControlBusGateway controlBus;

  /**
   * Who carries each command of a target.
   *
   * @param session the session; with {@code workflow} it names a workflow
   * @param workflow the workflow
   * @param execution a run, as a shorthand for its session and workflow
   * @param node a node of the workflow: its commands are answered instead of the run's
   * @return the commands with {@code allowed}, {@code via} and {@code reason}; with no target the
   *     server's commands
   */
  @GetMapping
  @Operation(summary = "Who may send which command to a node, a run or the server")
  public Mono<ApiResponse<Map<String, ControlReviewer.Capability>>> supervision(
      @RequestParam(required = false) final @Nullable String session,
      @RequestParam(required = false) final @Nullable String workflow,
      @RequestParam(required = false) final @Nullable String execution,
      @RequestParam(required = false) final @Nullable String node) {
    return Mono.fromSupplier(
        () -> {
          String sessionId = session;
          String workflowId = workflow;
          if (execution != null) {
            final WorkflowProgress progress = controlBus.getCurrentProgress(execution);
            if (progress != null) {
              sessionId = progress.sessionId();
              workflowId = progress.workflowId();
            }
          }
          final boolean server = sessionId == null && workflowId == null;
          final Map<String, Supplier<ExecutionControlCommand>> commands =
              server ? SERVER_COMMANDS : node != null ? NODE_COMMANDS : RUN_COMMANDS;
          final Map<String, ControlReviewer.Capability> answer = new LinkedHashMap<>();
          for (final Map.Entry<String, Supplier<ExecutionControlCommand>> entry :
              new java.util.TreeMap<>(commands).entrySet()) {
            answer.put(
                entry.getKey(),
                reviewer.capability(sessionId, workflowId, node, entry.getValue().get()));
          }
          return ApiResponse.success(200, "Supervision retrieved", answer);
        });
  }
}
