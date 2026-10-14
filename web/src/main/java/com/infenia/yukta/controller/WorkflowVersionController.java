// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.model.api.ApiResponse;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.workflow.store.DefinitionRevision;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import com.infenia.yukta.service.workflow.store.WorkflowDiff;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Saved versions of a workflow definition, and going back to one of them. */
@RestController
@RequestMapping("/api/sessions/{sessionId}/workflows/{workflowId}")
@RequiredArgsConstructor
@Tag(name = "Workflow Versions API", description = "List the saved versions and roll back")
public class WorkflowVersionController {

  /** Session ID parameter description. */
  private static final String SESSION_ID = "The unique identifier of the session";

  /** Workflow ID parameter description. */
  private static final String WORKFLOW_ID = "The unique identifier of the workflow";

  /** Keeps the saved versions. */
  private final WorkflowDefinitionStore definitions;

  /** Prepares the workflow again after a rollback. */
  private final ControlBusGateway controlBus;

  /**
   * List the saved versions of a workflow.
   *
   * @param sessionId the session
   * @param workflowId the workflow
   * @return the versions, newest first (empty if the store keeps none)
   */
  @GetMapping("/versions")
  @Operation(
      summary = "List the saved versions of a workflow",
      description = "Newest first; the newest 20 saves are kept")
  public Mono<ApiResponse<List<DefinitionRevision>>> versions(
      @Parameter(description = SESSION_ID) @PathVariable final String sessionId,
      @Parameter(description = WORKFLOW_ID) @PathVariable final String workflowId) {
    return definitions
        .revisions(sessionId, workflowId)
        .collectList()
        .map(list -> ApiResponse.success(200, "Workflow versions retrieved", list));
  }

  /**
   * Compare two saved versions, or a saved version with the current definition.
   *
   * @param sessionId the session
   * @param workflowId the workflow
   * @param from the older version, as listed
   * @param to the newer version, as listed; the current definition when omitted
   * @return the nodes and edges added, removed or changed
   */
  @GetMapping("/versions/diff")
  @Operation(
      summary = "Compare two versions of a workflow",
      description = "Nodes added, removed or changed, and edges added or removed")
  public Mono<ApiResponse<WorkflowDiff>> diff(
      @Parameter(description = SESSION_ID) @PathVariable final String sessionId,
      @Parameter(description = WORKFLOW_ID) @PathVariable final String workflowId,
      @Parameter(description = "Older version id from the list") @RequestParam final String from,
      @Parameter(description = "Newer version id; the current definition if omitted")
          @RequestParam(required = false)
          final String to) {
    final Mono<WorkflowDefinition> newer =
        to == null || to.isBlank()
            ? definitions.find(sessionId, workflowId)
            : definitions.findRevision(sessionId, workflowId, to);
    return Mono.zip(definitions.findRevision(sessionId, workflowId, from), newer)
        .switchIfEmpty(
            Mono.error(
                () -> new NotFoundException("Version of workflow " + workflowId + " not found")))
        .map(
            pair ->
                ApiResponse.success(
                    200,
                    "Workflow versions compared",
                    WorkflowDiff.between(pair.getT1(), pair.getT2())));
  }

  /**
   * Make an earlier version the current one. The version becomes a new save, so it can be undone
   * the same way; runs that already started keep the version they use.
   *
   * @param sessionId the session
   * @param workflowId the workflow
   * @param version the version, as listed
   * @return the workflow id and the version it now has as its current definition
   */
  @PostMapping("/rollback")
  @Operation(summary = "Roll a workflow back to a saved version")
  public Mono<ApiResponse<Map<String, String>>> rollback(
      @Parameter(description = SESSION_ID) @PathVariable final String sessionId,
      @Parameter(description = WORKFLOW_ID) @PathVariable final String workflowId,
      @Parameter(description = "Version id from the list") @RequestParam final String version) {
    return definitions
        .findRevision(sessionId, workflowId, version)
        .switchIfEmpty(
            Mono.error(
                () ->
                    new NotFoundException(
                        "Version " + version + " of workflow " + workflowId + " not found")))
        .flatMap(
            definition ->
                definitions
                    .save(sessionId, definition)
                    .then(controlBus.compileAndCacheWorkflow(sessionId, definition))
                    .thenReturn(
                        ApiResponse.success(
                            200,
                            "Workflow rolled back to version " + version,
                            Map.of("workflowId", workflowId, "version", version))));
  }
}
