// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.controller;

import com.infenia.yukta.dto.request.ConfigRequest;
import com.infenia.yukta.dto.response.SessionDetails;
import com.infenia.yukta.dto.response.SessionListItem;
import com.infenia.yukta.dto.response.WorkflowSummary;
import com.infenia.yukta.mapper.SessionMapper;
import com.infenia.yukta.model.api.ApiResponse;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.session.SessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Session and workflow-definition discovery and configuration. Errors are not caught here: a
 * missing session or workflow becomes a {@link NotFoundException} and the global handler picks the
 * status. List endpoints are paged with {@code ?limit=&cursor=}.
 */
@Validated
@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
@Slf4j
@Tag(
    name = "Session API",
    description = "Endpoints for session and workflow discovery, configuration management")
public class SessionConfigController {

  /** Session ID parameter description. */
  private static final String SESSION_ID = "The unique identifier of the session";

  /** Workflow ID parameter description. */
  private static final String WORKFLOW_ID = "The unique identifier of the workflow";

  /** The session service. */
  private final SessionService sessionService;

  /** The session mapper. */
  private final SessionMapper sessionMapper;

  /**
   * Get one session.
   *
   * @param sessionId the session
   * @return its details
   */
  @GetMapping("/{sessionId}")
  @Operation(
      summary = "Get session details",
      description = "Retrieves details of a specific session including workflow IDs")
  public Mono<ApiResponse<SessionDetails>> getSessionDetails(
      @Parameter(description = SESSION_ID) @PathVariable final String sessionId) {
    return sessionService
        .getSessionConfig(sessionId)
        .switchIfEmpty(sessionNotFound(sessionId))
        .map(
            config ->
                ApiResponse.success(
                    200,
                    "Session details retrieved",
                    sessionMapper.sessionConfigResponseToSessionDetails(config)));
  }

  /**
   * List session IDs.
   *
   * @param limit page size (default 500, at most 1000)
   * @param cursor the last session ID of the previous page
   * @return one page of session IDs in alphabetical order
   */
  @GetMapping
  @Operation(
      summary = "List all sessions",
      description =
          "Lists session identifiers alphabetically. A further page is announced in the"
              + " X-Next-Cursor header.")
  public Mono<ResponseEntity<ApiResponse<List<String>>>> listSessions(
      @RequestParam(required = false) final Integer limit,
      @RequestParam(required = false) final String cursor) {
    return sessionService
        .getSessionIds()
        .sort()
        .collectList()
        .map(
            ids ->
                Pagination.page(ids, limit, cursor, id -> id, "Sessions retrieved successfully"));
  }

  /**
   * List session summaries.
   *
   * @param limit page size (default 500, at most 1000)
   * @param cursor the last session ID of the previous page
   * @return one page of summaries; only the sessions on the page are read from storage
   */
  @GetMapping("/summaries")
  @Operation(
      summary = "List session summaries",
      description =
          "Lean per-session rows for table display, alphabetical by session ID. Only the"
              + " requested page is loaded. A further page is announced in the X-Next-Cursor"
              + " header.")
  public Mono<ResponseEntity<ApiResponse<List<SessionListItem>>>> listSessionSummaries(
      @RequestParam(required = false) final Integer limit,
      @RequestParam(required = false) final String cursor) {
    return sessionService
        .getSessionIds()
        .sort()
        .collectList()
        .flatMap(
            ids -> {
              final Pagination.Slice<String> page = Pagination.slice(ids, limit, cursor, id -> id);
              return Flux.fromIterable(page.items())
                  .concatMap(
                      id ->
                          sessionService
                              .getSessionConfig(id)
                              .onErrorResume(
                                  e -> {
                                    log.atWarn().setCause(e).log("Skipping session {}", id);
                                    return Mono.empty();
                                  }))
                  .map(sessionMapper::sessionConfigResponseToSessionListItem)
                  .collectList()
                  .map(
                      items ->
                          Pagination.respond(items, page.next(), "Session summaries retrieved"));
            });
  }

  /**
   * Get one workflow definition.
   *
   * @param sessionId the session
   * @param workflowId the workflow
   * @return the definition
   */
  @GetMapping("/{sessionId}/workflows/{workflowId}")
  @Operation(summary = "Get workflow", description = "Retrieves the definition of a workflow")
  public Mono<ApiResponse<WorkflowDefinition>> getWorkflow(
      @Parameter(description = SESSION_ID) @PathVariable final String sessionId,
      @Parameter(description = WORKFLOW_ID) @PathVariable final String workflowId) {
    return sessionService
        .getSessionWorkflow(sessionId, workflowId)
        .switchIfEmpty(
            Mono.error(
                () ->
                    new NotFoundException(
                        "Workflow not found: '"
                            + workflowId
                            + "' in session: '"
                            + sessionId
                            + "'")))
        .map(def -> ApiResponse.success(200, "Workflow retrieved", def));
  }

  /**
   * List a session's workflows with their latest execution status.
   *
   * @param sessionId the session
   * @return one summary per workflow
   */
  @GetMapping("/{sessionId}/workflows")
  @Operation(
      summary = "Get workflow summaries for a session",
      description =
          "All workflows of a session with node/edge counts and the latest execution status")
  public Mono<ApiResponse<List<WorkflowSummary>>> getSessionWorkflows(
      @Parameter(description = SESSION_ID) @PathVariable final String sessionId) {
    return sessionService
        .getSessionConfig(sessionId)
        .switchIfEmpty(sessionNotFound(sessionId))
        .flatMap(
            config ->
                sessionService
                    .getLatestExecutionStatusByWorkflow(sessionId)
                    .map(
                        statuses ->
                            config.workflows().values().stream()
                                .map(
                                    def ->
                                        new WorkflowSummary(
                                            def.workflowId(),
                                            def.description(),
                                            def.nodes().size(),
                                            def.edges().size(),
                                            statuses.containsKey(def.workflowId())
                                                ? statuses.get(def.workflowId()).status()
                                                : null))
                                .toList()))
        .map(summaries -> ApiResponse.success(200, "Workflow summaries retrieved", summaries));
  }

  /**
   * Create or update a session and its workflows.
   *
   * @param request the session configuration
   * @return an empty success response
   */
  @PostMapping
  @Operation(
      summary = "Apply session configuration",
      description =
          "Initializes a new session or updates an existing session's configuration at runtime,"
              + " including its workflow definitions")
  public Mono<ApiResponse<Void>> applyConfig(@Valid @RequestBody final ConfigRequest request) {
    return sessionService
        .applyConfig(sessionMapper.configRequestToSessionConfigData(request))
        .thenReturn(ApiResponse.success(200, "Configuration applied successfully", null));
  }

  private static <T> Mono<T> sessionNotFound(final String sessionId) {
    return Mono.error(() -> new NotFoundException("Session not found: " + sessionId));
  }
}
