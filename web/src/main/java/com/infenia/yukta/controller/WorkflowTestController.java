// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.model.api.ApiResponse;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.testing.TestReport;
import com.infenia.yukta.service.testing.WorkflowTestRunner;
import com.infenia.yukta.service.testing.WorkflowTestSpec;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Runs a saved workflow against stubs and reports which assertions held. */
@RestController
@RequestMapping("/api/sessions/{sessionId}/workflows/{workflowId}/test")
@RequiredArgsConstructor
@Tag(name = "Workflow tests", description = "Run a workflow with stubbed nodes and assertions")
public class WorkflowTestController {

  /** The saved workflows. */
  private final WorkflowDefinitionStore definitions;

  /** Runs the test. */
  private final WorkflowTestRunner runner;

  /**
   * Runs the test.
   *
   * @param sessionId the session
   * @param workflowId the workflow
   * @param spec the stubs and assertions
   * @return the report, with one entry per assertion
   */
  @PostMapping
  @Operation(
      summary = "Test a workflow with stubbed nodes",
      description =
          "Runs the saved workflow once. Triggers emit the input (or their stub), terminals do"
              + " nothing and processors with a stub emit it instead of running; the others run"
              + " for real, so stub every node with a side effect, or list the ones that may"
              + " run in allow. Payload assertions name a terminal node. A failing assertion is"
              + " a 200 with passed=false.")
  public Mono<ApiResponse<TestReport>> runTest(
      @PathVariable final String sessionId,
      @PathVariable final String workflowId,
      @RequestBody final WorkflowTestSpec spec) {
    return definitions
        .find(sessionId, workflowId)
        .switchIfEmpty(Mono.error(new NotFoundException("Workflow not found: " + workflowId)))
        .flatMap(definition -> runner.run(sessionId, definition, spec))
        .map(report -> ApiResponse.success(200, "Workflow tested", report));
  }
}
