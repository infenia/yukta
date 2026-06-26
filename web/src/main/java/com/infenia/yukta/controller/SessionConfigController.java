/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.infenia.yukta.controller;

import com.infenia.yukta.dto.request.ConfigRequest;
import com.infenia.yukta.dto.response.SessionDetails;
import com.infenia.yukta.dto.response.SessionList;
import com.infenia.yukta.mapper.SessionMapper;
import com.infenia.yukta.model.api.ApiResponse;
import com.infenia.yukta.model.session.SessionConfigData;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.service.session.SessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Controller for session management and configuration. */
@Validated
@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
@Slf4j
@Tag(
    name = "Session API",
    description = "Endpoints for session and workflow discovery, configuration management")
public class SessionConfigController {
  /** Content type constant for JSON responses. */
  private static final String APPLICATION_JSON = "application/json";

  /** HTTP 200 response code constant for Swagger documentation. */
  private static final String HTTP_200 = "200";

  /** HTTP 500 response code constant for Swagger documentation. */
  private static final String HTTP_500 = "500";

  /** Internal server error message constant for Swagger documentation. */
  private static final String INTERNAL_SERVER_ERROR = "Internal server error";

  /** The service for managing sessions. */
  private final SessionService sessionService;

  /** The mapper for converting session-related DTOs. */
  private final SessionMapper sessionMapper;

  /**
   * Get details of a specific session.
   *
   * @param sessionId the session identifier
   * @param exchange implicit Spring parameter used to extract request path for error responses
   * @return session details
   */
  @GetMapping("/{sessionId}")
  @Operation(
      summary = "Get session details",
      description =
          "Retrieves details of a specific session including workflow IDs. Response is non-blocking"
              + " and returned asynchronously via Mono.")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = HTTP_200,
      description = "Session details retrieved successfully",
      content = @Content(mediaType = APPLICATION_JSON))
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "404",
      description = "Session not found",
      content = @Content(mediaType = APPLICATION_JSON))
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = HTTP_500,
      description = INTERNAL_SERVER_ERROR,
      content = @Content(mediaType = APPLICATION_JSON))
  @SuppressWarnings("unchecked")
  public Mono<ResponseEntity<ApiResponse<SessionDetails>>> getSessionDetails(
      @Parameter(description = "The unique identifier of the session") @PathVariable
          final String sessionId,
      final ServerWebExchange exchange) {
    log.atInfo().log("getSessionDetails: sessionId={}", sessionId);
    return sessionService
        .getSessionConfig(sessionId)
        .doOnNext(
            config ->
                log.atInfo().log(
                    "getSessionDetails service call succeeded: sessionId={}, workflowCount={}",
                    sessionId,
                    ((Map<String, Object>) config.getOrDefault("workflows", Map.of())).size()))
        .map(
            config -> {
              // Cast is safe: config comes from SessionConfigData which guarantees workflows is
              // a Map<String, Object> per SessionConfigData structure. getOrDefault fallback
              // ensures type safety.
              final Map<String, Object> workflows =
                  (Map<String, Object>) config.getOrDefault("workflows", Map.of());
              final List<String> workflowIds = List.copyOf(workflows.keySet());
              return ResponseEntity.ok(
                  ApiResponse.success(
                      HttpStatus.OK.value(),
                      "Session details retrieved",
                      new SessionDetails(sessionId, workflowIds)));
            })
        .doOnSuccess(
            _ ->
                log.atInfo().log(
                    "getSessionDetails response sent successfully: sessionId={}", sessionId))
        .switchIfEmpty(
            Mono.fromSupplier(
                () -> {
                  log.atWarn().log("getSessionDetails session not found: sessionId={}", sessionId);
                  @SuppressWarnings("PMD.LawOfDemeter")
                  final var request = exchange.getRequest();
                  final String path = request.getPath().value();
                  final List<ApiResponse.FieldError> errors =
                      List.of(
                          new ApiResponse.FieldError(
                              "sessionId", "Session not found: '" + sessionId + "'"));
                  return ResponseEntity.status(HttpStatus.NOT_FOUND)
                      .body(
                          ApiResponse.error(
                              HttpStatus.NOT_FOUND.value(),
                              "Not Found",
                              "Session not found",
                              path,
                              errors));
                }))
        .doOnError(
            error ->
                log.atError()
                    .log(
                        "getSessionDetails error occurred: sessionId={}, error={}",
                        sessionId,
                        error.getMessage()));
  }

  /**
   * List all available sessions.
   *
   * @return list of all session identifiers
   */
  @GetMapping
  @Operation(
      summary = "List all sessions",
      description =
          "Retrieves a list of all available session identifiers in the system. Response is"
              + " non-blocking and returned asynchronously via Mono.")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = HTTP_200,
      description = "Sessions retrieved successfully",
      content = @Content(mediaType = APPLICATION_JSON))
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = HTTP_500,
      description = INTERNAL_SERVER_ERROR,
      content = @Content(mediaType = APPLICATION_JSON))
  public Mono<ResponseEntity<ApiResponse<SessionList>>> listSessions() {
    log.atInfo().log("listSessions: retrieving all session IDs");
    return sessionService
        .getSessionIds()
        .collectList()
        .map(
            sessionIds -> {
              log.atDebug().log("listSessions: found {} sessions", sessionIds.size());
              return new SessionList(sessionIds);
            })
        .map(
            sessionList ->
                ResponseEntity.ok(
                    ApiResponse.success(
                        HttpStatus.OK.value(), "Sessions retrieved successfully", sessionList)))
        .doOnSuccess(_ -> log.atInfo().log("listSessions: response sent successfully"))
        .onErrorResume(
            error -> {
              log.atError().log("listSessions: error occurred: {}", error.getMessage());
              final String path = "/api/sessions";
              final List<ApiResponse.FieldError> errors =
                  List.of(
                      new ApiResponse.FieldError(
                          "listSessions", "Failed to retrieve sessions: " + error.getMessage()));
              return Mono.just(
                  ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                      .body(
                          ApiResponse.error(
                              HttpStatus.INTERNAL_SERVER_ERROR.value(),
                              "Internal Server Error",
                              "Failed to retrieve sessions",
                              path,
                              errors)));
            });
  }

  /**
   * Get workflow definition.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @param exchange implicit Spring parameter used to extract request path for error responses
   * @return workflow definition
   */
  @GetMapping("/{sessionId}/workflows/{workflowId}")
  @Operation(
      summary = "Get workflow",
      description =
          "Retrieves the definition of a workflow. Response is non-blocking and returned"
              + " asynchronously via Mono.")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = HTTP_200,
      description = "Workflow retrieved successfully",
      content = @Content(mediaType = APPLICATION_JSON))
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "404",
      description = "Workflow not found in the specified session",
      content = @Content(mediaType = APPLICATION_JSON))
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = HTTP_500,
      description = INTERNAL_SERVER_ERROR,
      content = @Content(mediaType = APPLICATION_JSON))
  public Mono<ResponseEntity<ApiResponse<WorkflowDefinition>>> getWorkflow(
      @Parameter(description = "The unique identifier of the session") @PathVariable
          final String sessionId,
      @Parameter(description = "The unique identifier of the workflow") @PathVariable
          final String workflowId,
      final ServerWebExchange exchange) {
    log.atInfo().log("getWorkflow: sessionId={}, workflowId={}", sessionId, workflowId);
    return sessionService
        .getSessionWorkflow(sessionId, workflowId)
        .doOnNext(
            _ ->
                log.atInfo().log(
                    "getWorkflow service call succeeded: sessionId={}, workflowId={}",
                    sessionId,
                    workflowId))
        .map(
            def ->
                ResponseEntity.ok(
                    ApiResponse.success(HttpStatus.OK.value(), "Workflow retrieved", def)))
        .doOnSuccess(
            _ ->
                log.atInfo().log(
                    "getWorkflow response sent successfully: sessionId={}, workflowId={}",
                    sessionId,
                    workflowId))
        .switchIfEmpty(
            Mono.fromSupplier(
                () -> {
                  log.atWarn()
                      .log(
                          "getWorkflow workflow not found: sessionId={}, workflowId={}",
                          sessionId,
                          workflowId);
                  @SuppressWarnings("PMD.LawOfDemeter")
                  final var request = exchange.getRequest();
                  final String path = request.getPath().value();
                  final List<ApiResponse.FieldError> errors =
                      List.of(
                          new ApiResponse.FieldError(
                              "workflowId",
                              "Workflow not found: '"
                                  + workflowId
                                  + "' in session: '"
                                  + sessionId
                                  + "'"));
                  return ResponseEntity.status(HttpStatus.NOT_FOUND)
                      .body(
                          ApiResponse.error(
                              HttpStatus.NOT_FOUND.value(),
                              "Not Found",
                              "Workflow not found",
                              path,
                              errors));
                }))
        .doOnError(
            error ->
                log.atError()
                    .log(
                        "getWorkflow error occurred: sessionId={}, workflowId={}, error={}",
                        sessionId,
                        workflowId,
                        error.getMessage()));
  }

  /**
   * Initialize a new session or update an existing one with the provided configuration.
   *
   * <p>This endpoint allows callers to dynamically configure project paths, workflows, and session
   * metadata. If the session ID already exists, it overrides the current configuration; otherwise,
   * it creates a new session context.
   *
   * @param request the config request containing session identifiers and configuration values
   * @return response entity with success message indicating the configuration has been applied
   */
  @PostMapping
  @Operation(
      summary = "Apply session configuration",
      description =
          "Initializes a new session or updates an existing session's configuration at runtime."
              + " Configures project paths, workflow definitions, and session metadata. Response is"
              + " non-blocking and returned asynchronously via Mono.")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = HTTP_200,
      description = "Session configuration applied successfully",
      content = @Content(mediaType = APPLICATION_JSON))
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "400",
      description = "Invalid configuration data provided in the request",
      content = @Content(mediaType = APPLICATION_JSON))
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = HTTP_500,
      description = INTERNAL_SERVER_ERROR,
      content = @Content(mediaType = APPLICATION_JSON))
  public Mono<ResponseEntity<ApiResponse<Void>>> applyConfig(
      @Valid
          @io.swagger.v3.oas.annotations.parameters.RequestBody(
              description = "Configuration request containing session identifiers and settings",
              required = true,
              content = @Content(mediaType = APPLICATION_JSON))
          @RequestBody
          final ConfigRequest request) {
    log.atInfo().log("applyConfig: sessionId={}", request.sessionId());
    final SessionConfigData configData = sessionMapper.configRequestToSessionConfigData(request);
    return sessionService
        .applyConfig(configData)
        .doOnSuccess(
            _ ->
                log.atInfo().log(
                    "applyConfig service call succeeded: sessionId={}", request.sessionId()))
        .then(
            Mono.defer(
                () -> {
                  final ApiResponse<Void> response =
                      ApiResponse.success(
                          HttpStatus.OK.value(), "Configuration applied successfully", null);
                  return Mono.just(ResponseEntity.ok(response));
                }))
        .doOnSuccess(
            _ ->
                log.atInfo().log(
                    "applyConfig response sent successfully: sessionId={}", request.sessionId()))
        .doOnError(
            error ->
                log.atError()
                    .log(
                        "applyConfig error occurred: sessionId={}, error={}",
                        request.sessionId(),
                        error.getMessage()));
  }
}
