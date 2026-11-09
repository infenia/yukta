// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.model.api.ApiResponse;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.review.ControlReviewer;
import com.infenia.yukta.service.supervisor.OwnershipMap;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
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
  private final ControlReviewer reviewer;

  /** Finds the session and workflow of a run. */
  private final ControlBusGateway controlBus;

  private final OwnershipMap ownershipMap;

  /**
   * Who owns each part of a workflow.
   *
   * @param session the session
   * @param workflow the workflow
   * @return the owner of the run level and of each node
   */
  @GetMapping("/ownership")
  @Operation(
      summary = "Who owns each part of a workflow",
      description =
          "The supervisor that carries the run level and each node of a workflow, or"
              + " 'default' for a unit that nobody owns, and whether the default supervisor is on.")
  public Mono<ApiResponse<OwnershipMap.Ownership>> ownership(
      @RequestParam final String session, @RequestParam final String workflow) {
    return ownershipMap
        .ownersOf(session, workflow)
        .map(found -> ApiResponse.success(200, "Ownership retrieved", found));
  }

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
  @SuppressWarnings("PMD.UseObjectForClearerAPI")
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
          final Map<String, ControlReviewer.Capability> answer =
              reviewer.capabilities(sessionId, workflowId, node);
          return ApiResponse.success(200, "Supervision retrieved", answer);
        });
  }
}
