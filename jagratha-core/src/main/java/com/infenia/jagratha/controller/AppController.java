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
package com.infenia.jagratha.controller;

import com.infenia.jagratha.mapper.AppConfigMapper;
import com.infenia.jagratha.model.ApiResponse;
import com.infenia.jagratha.model.AppConfigData;
import com.infenia.jagratha.model.ConfigRequest;
import com.infenia.jagratha.model.TriggerResponse;
import com.infenia.jagratha.model.WorkflowExecution;
import com.infenia.jagratha.model.WorkflowExecutionSummary;
import com.infenia.jagratha.model.WorkflowProgress;
import com.infenia.jagratha.model.WorkflowTriggerRequest;
import com.infenia.jagratha.service.LogRetrievalService;
import com.infenia.jagratha.service.SessionService;
import com.infenia.jagratha.service.TaskTrackerService;
import com.infenia.jagratha.service.WorkflowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * REST controller for app operations. Provides endpoints for file management and task execution.
 */
@RestController
@RequestMapping("/api/session/{sessionId}")
@RequiredArgsConstructor
@Tag(
    name = "Jagratha API",
    description = "Endpoints for file management, task execution, and configuration")
@SuppressWarnings("PMD.ExcessiveImports")
public class AppController {

  private final WorkflowService workflowService;
  private final SessionService sessionService;
  private final LogRetrievalService retrievalService;
  private final TaskTrackerService trackerService;
  private final AppConfigMapper configMapper;

  private static final String HTTP_200 = "200";
  private static final String SESSION_ID_PARAM = "Session ID";

  /**
   * Trigger a workflow execution for a session.
   *
   * @param sessionId the session identifier
   * @param request the trigger request containing workflowId and payload
   * @return response entity with acknowledgment and execution ID
   */
  @PostMapping("/workflow/trigger")
  @Operation(
      summary = "Trigger a workflow",
      description = "Triggers the execution of a specific DAG workflow for a session")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = HTTP_200,
      description = "Workflow trigger accepted")
  public Mono<ResponseEntity<ApiResponse<TriggerResponse>>> triggerWorkflow(
      @Parameter(description = SESSION_ID_PARAM) @PathVariable final String sessionId,
      @Valid @RequestBody final WorkflowTriggerRequest request) {
    final WorkflowExecution execution =
        workflowService.runWorkflow(sessionId, request.workflowId(), request.payload());
    return Mono.just(
        ResponseEntity.ok()
            .body(
                ApiResponse.success(
                    200,
                    "Workflow trigger accepted",
                    new TriggerResponse(execution.executionId()))));
  }

  /**
   * Get the status of a specific workflow execution.
   *
   * @param sessionId the session identifier
   * @param executionId the execution identifier
   * @return response entity with workflow progress
   */
  @GetMapping("/workflow/status/{executionId}")
  @Operation(
      summary = "Get workflow execution status",
      description = "Retrieves the current status and progress of a specific workflow execution")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = HTTP_200,
      description = "Workflow status retrieved successfully")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "404",
      description = "Execution not found")
  public Mono<ResponseEntity<ApiResponse<WorkflowProgress>>> getWorkflowStatus(
      @Parameter(description = SESSION_ID_PARAM) @PathVariable final String sessionId,
      @Parameter(description = "Execution ID") @PathVariable final String executionId) {
    return Mono.fromCallable(() -> trackerService.getProgress(sessionId, executionId))
        .map(
            progress -> {
              if (progress != null) {
                return ResponseEntity.ok(
                    ApiResponse.success(200, "Workflow status retrieved successfully", progress));
              } else {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(
                        ApiResponse.error(
                            404, "Not Found", "Execution not found", null, List.of()));
              }
            });
  }

  /**
   * Stream status updates for a workflow execution via SSE.
   *
   * @param sessionId the session identifier
   * @param executionId the execution identifier
   * @return a flux of status update events
   */
  @GetMapping(
      value = "/workflow/status/{executionId}/stream",
      produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  @Operation(
      summary = "Stream workflow execution status",
      description = "Streams the status and progress of a specific workflow execution via SSE")
  public Flux<ServerSentEvent<WorkflowProgress>> streamWorkflowStatus(
      @Parameter(description = SESSION_ID_PARAM) @PathVariable final String sessionId,
      @Parameter(description = "Execution ID") @PathVariable final String executionId) {
    return trackerService
        .getStatusStream(executionId)
        .map(progress -> ServerSentEvent.<WorkflowProgress>builder().data(progress).build());
  }

  /**
   * Get history of workflow executions for a session.
   *
   * @param sessionId the session identifier
   * @return response entity with list of execution summaries
   */
  @GetMapping("/workflow/history")
  @Operation(
      summary = "Get workflow history",
      description = "Retrieves the history of all workflow executions for a session")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = HTTP_200,
      description = "Workflow history retrieved successfully")
  public Mono<ApiResponse<List<WorkflowExecutionSummary>>> getWorkflowHistory(
      @Parameter(description = SESSION_ID_PARAM) @PathVariable final String sessionId) {
    return Mono.fromCallable(() -> trackerService.getHistory(sessionId))
        .map(
            history ->
                ApiResponse.success(200, "Workflow history retrieved successfully", history));
  }

  /**
   * List logs for a session.
   *
   * @param sessionId the session identifier
   * @return list of log filenames
   */
  @GetMapping("/logs")
  @Operation(
      summary = "List logs",
      description = "Lists all log files available for a given session")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = HTTP_200,
      description = "List of log filenames")
  public Mono<ApiResponse<List<String>>> listLogs(
      @Parameter(description = SESSION_ID_PARAM) @PathVariable final String sessionId) {
    return retrievalService
        .listLogs(sessionId)
        .map(logs -> ApiResponse.success(200, "List of log filenames", logs));
  }

  /**
   * Get content of a specific log file.
   *
   * @param sessionId the session identifier
   * @param filename the log filename
   * @return log content
   */
  @GetMapping("/logs/{filename}")
  @Operation(
      summary = "Get log content",
      description = "Retrieves the content of a specific log file for a session")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = HTTP_200,
      description = "Log content retrieved successfully")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "404",
      description = "Log file not found")
  public Mono<ResponseEntity<ApiResponse<String>>> getLogContent(
      @Parameter(description = SESSION_ID_PARAM) @PathVariable final String sessionId,
      @Parameter(description = "Log filename") @PathVariable final String filename) {
    return retrievalService
        .getLogContent(sessionId, filename)
        .map(
            content ->
                ResponseEntity.ok(
                    ApiResponse.success(200, "Log content retrieved successfully", content)))
        .onErrorResume(
            e ->
                Mono.just(
                    ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(
                            ApiResponse.error(
                                404, "Not Found", "Log file not found", null, List.of()))));
  }

  /**
   * Get raw content of a specific log file.
   *
   * @param sessionId the session identifier
   * @param filename the log filename
   * @return raw log content
   */
  @GetMapping("/logs/{filename}/raw")
  @Operation(
      summary = "Get raw log content",
      description = "Retrieves the raw content of a specific log file for a session")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = HTTP_200,
      description = "Raw log content retrieved successfully")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "404",
      description = "Log file not found")
  public Mono<ResponseEntity<String>> getRawLogContent(
      @Parameter(description = SESSION_ID_PARAM) @PathVariable final String sessionId,
      @Parameter(description = "Log filename") @PathVariable final String filename) {
    return retrievalService
        .getLogContent(sessionId, filename)
        .map(ResponseEntity::ok)
        .onErrorResume(e -> Mono.just(ResponseEntity.notFound().build()));
  }

  /**
   * Update configuration at runtime.
   *
   * @param sessionId the session identifier
   * @param request the config request containing new configuration values
   * @return response entity with success message
   */
  @PostMapping("/config")
  @Operation(
      summary = "Update configuration",
      description = "Updates the application configuration at runtime for a session")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = HTTP_200,
      description = "Configuration updated successfully")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "400",
      description = "Invalid configuration data")
  public Mono<ResponseEntity<ApiResponse<Void>>> updateConfig(
      @Parameter(description = SESSION_ID_PARAM) @PathVariable final String sessionId,
      @Valid @RequestBody final ConfigRequest request) {
    final AppConfigData configData = configMapper.toData(request, sessionId);
    return sessionService
        .applyConfigOverrides(configData)
        .thenReturn(
            ResponseEntity.ok(
                ApiResponse.success(200, "Configuration updated successfully", null)));
  }
}
