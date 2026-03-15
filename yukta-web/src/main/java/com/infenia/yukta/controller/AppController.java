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

import com.infenia.yukta.model.api.ApiResponse;
import com.infenia.yukta.model.api.TriggerResponse;
import com.infenia.yukta.model.api.WorkflowTriggerRequest;
import com.infenia.yukta.model.monitoring.WorkflowExecutionSummary;
import com.infenia.yukta.model.monitoring.WorkflowProgress;
import com.infenia.yukta.service.LogRetrievalService;
import com.infenia.yukta.service.TaskTrackerService;
import com.infenia.yukta.service.WorkflowService;
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
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(
    name = "Yukta API",
    description = "Endpoints for file management, task execution, and configuration")
public class AppController {

  private final WorkflowService workflowService;
  private final LogRetrievalService retrievalService;
  private final TaskTrackerService trackerService;

  private static final String HTTP_200 = "200";
  private static final String SESSION_ID_PARAM = "Session ID";

  /**
   * Trigger a workflow execution for a session.
   *
   * @param request the trigger request containing sessionId and workflowId
   * @return response entity with acknowledgment and execution ID
   */
  @PostMapping("/workflow/trigger")
  @Operation(
      summary = "Trigger a workflow",
      description = "Triggers the execution of a specific DAG workflow for a session")
  @io.swagger.v3.oas.annotations.responses.ApiResponse(
      responseCode = "202",
      description = "Workflow trigger accepted")
  public Mono<ResponseEntity<ApiResponse<TriggerResponse>>> triggerWorkflow(
      @Valid @RequestBody final WorkflowTriggerRequest request) {
    return Mono.fromCallable(
            () ->
                workflowService.runWorkflow(
                    request.sessionId(), request.workflowId(), request.payload()))
        .map(
            execution ->
                ResponseEntity.accepted()
                    .body(
                        ApiResponse.success(
                            202,
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
  @GetMapping("/workflow/{sessionId}/status/{executionId}")
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
        .flatMap(progress -> Mono.justOrEmpty(progress))
        .map(
            progress ->
                ResponseEntity.ok(
                    ApiResponse.success(200, "Workflow status retrieved successfully", progress)))
        .defaultIfEmpty(
            ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(404, "Not Found", "Execution not found", null, List.of())));
  }

  /**
   * Stream status updates for a workflow execution via SSE.
   *
   * @param sessionId the session identifier
   * @param executionId the execution identifier
   * @return a flux of status update events
   */
  @GetMapping(
      value = "/workflow/{sessionId}/status/{executionId}/stream",
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
  @GetMapping("/workflow/{sessionId}/history")
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
  @GetMapping("/logs/{sessionId}")
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
  @GetMapping("/logs/{sessionId}/{filename}")
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
  @GetMapping("/logs/{sessionId}/{filename}/raw")
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
}
