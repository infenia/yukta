// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.dto.request.TestRunRequest;
import com.infenia.yukta.dto.response.TestRunResponse;
import com.infenia.yukta.mapper.AppConfigMapper;
import com.infenia.yukta.model.api.ApiResponse;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.plugin.type.TerminalPlugin;
import com.infenia.yukta.service.control.SupervisedControl;
import com.infenia.yukta.service.orchestrator.validator.ValidationProblem;
import com.infenia.yukta.service.orchestrator.validator.WorkflowValidator;
import com.infenia.yukta.service.plugin.PluginRegistry;
import com.infenia.yukta.service.testrun.DraftRuns;
import com.infenia.yukta.validation.SessionId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Runs a draft workflow, or part of it, without saving it. */
@Validated
@RestController
@RequestMapping("/api/sessions/{sessionId}/test-runs")
@RequiredArgsConstructor
@Tag(name = "Session API", description = "Run a draft workflow without saving it")
public class DraftRunController {

  /** Maps the draft to the domain model. */
  private final AppConfigMapper appConfigMapper;

  /** Tells terminals from other plugins. */
  private final PluginRegistry pluginRegistry;

  /** The same validator execution uses. */
  private final WorkflowValidator workflowValidator;

  /** Starts the run. */
  private final SupervisedControl control;

  /**
   * Start a test run.
   *
   * @param sessionId the session whose project path, tags and credentials the nodes use
   * @param request the draft and what to run of it
   * @return the run, readable through the execution endpoints
   */
  @PostMapping
  @ResponseStatus(HttpStatus.ACCEPTED)
  @Operation(
      summary = "Test-run a draft workflow",
      description =
          "Runs a draft, a node and the nodes before it, or one node alone, without saving it."
              + " The run is invisible to history, search, recovery, supervisors and analytics;"
              + " read its progress and node outputs through the execution endpoints. Answers 400"
              + " with the problems when the part to run does not validate and 429 when the"
              + " execution limit is reached.")
  public Mono<ApiResponse<TestRunResponse>> testRun(
      @PathVariable @SessionId final String sessionId,
      @Valid @RequestBody final TestRunRequest request) {
    final String workflowId = DraftRuns.newWorkflowId();
    final WorkflowDefinition draft = appConfigMapper.toWorkflowDefinition(request.workflow());
    final WorkflowDefinition cut =
        DraftRuns.cut(
            draft,
            workflowId,
            request.untilNodeId(),
            request.stepOnly(),
            type -> pluginRegistry.get(type) instanceof TerminalPlugin);
    return workflowValidator
        .validateAll(cut)
        .flatMap(problems -> {
          if (!problems.isEmpty()) {
            return Mono.error(
                new IllegalArgumentException(
                    problems.stream()
                        .map(ValidationProblem::message)
                        .collect(Collectors.joining("; "))));
          }
          return control.startDraft(sessionId, cut, request.payload());
        })
        .map(executionId ->
            ApiResponse.success(
                202, "Test run accepted", new TestRunResponse(executionId, workflowId)));
  }
}
