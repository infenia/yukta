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
import com.infenia.jagratha.model.AppConfigData;
import com.infenia.jagratha.model.ConfigRequest;
import com.infenia.jagratha.model.FileRequest;
import com.infenia.jagratha.model.TaskRequest;
import com.infenia.jagratha.model.TaskResponse;
import com.infenia.jagratha.service.FileLogService;
import com.infenia.jagratha.service.LogRetrievalService;
import com.infenia.jagratha.service.SessionService;
import com.infenia.jagratha.service.WorkflowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * REST controller for app operations. Provides endpoints for file management and task execution.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(
    name = "Jagratha API",
    description = "Endpoints for file management, task execution, and configuration")
public class AppController {

  private final FileLogService fileLogService;
  private final WorkflowService workflowService;
  private final SessionService sessionService;
  private final LogRetrievalService retrievalService;
  private final AppConfigMapper configMapper;

  private static final String SUCCESS_STATUS = "SUCCESS";
  private static final String HTTP_200 = "200";

  /**
   * Log a file path for a session.
   *
   * @param request the file request containing path and sessionId
   * @return response entity with success or error message
   */
  @PostMapping("/files")
  @Operation(
      summary = "Log a file path",
      description = "Logs a file path associated with a session for quality checks")
  @ApiResponse(responseCode = HTTP_200, description = "File path logged successfully")
  @ApiResponse(responseCode = "400", description = "Invalid request data")
  public Mono<ResponseEntity<String>> saveFile(@Valid @RequestBody final FileRequest request) {
    return fileLogService
        .saveFile(request.path(), request.sessionId())
        .thenReturn(ResponseEntity.ok("File path logged successfully"));
  }

  /**
   * Run quality checks on the external project and return the results.
   *
   * @param request the task request containing sessionId
   * @return response entity with task status and output
   */
  @PostMapping("/tasks/complete")
  @Operation(
      summary = "Complete a task",
      description = "Triggers quality checks (spotless, checkstyle, tests) on the external project")
  @ApiResponse(responseCode = HTTP_200, description = "Quality checks completed successfully")
  @ApiResponse(responseCode = "500", description = "Quality checks failed")
  public Mono<ResponseEntity<TaskResponse>> completeTask(
      @Valid @RequestBody final TaskRequest request) {
    return workflowService
        .runQualityChecks(request.sessionId())
        .map(
            response -> {
              if (SUCCESS_STATUS.equals(response.status())) {
                return ResponseEntity.ok(response);
              } else {
                return ResponseEntity.status(500).body(response);
              }
            });
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
  @ApiResponse(responseCode = HTTP_200, description = "List of log filenames")
  public Mono<List<String>> listLogs(
      @Parameter(description = "Session ID") @PathVariable final String sessionId) {
    return retrievalService.listLogs(sessionId);
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
  @ApiResponse(responseCode = HTTP_200, description = "Log content retrieved successfully")
  @ApiResponse(responseCode = "404", description = "Log file not found")
  public Mono<ResponseEntity<String>> getLogContent(
      @Parameter(description = "Session ID") @PathVariable final String sessionId,
      @Parameter(description = "Log filename") @PathVariable final String filename) {
    return retrievalService
        .getLogContent(sessionId, filename)
        .map(ResponseEntity::ok)
        .onErrorResume(e -> Mono.just(ResponseEntity.notFound().build()));
  }

  /**
   * Update configuration at runtime.
   *
   * @param request the config request containing new configuration values
   * @return response entity with success message
   */
  @PostMapping("/config")
  @Operation(
      summary = "Update configuration",
      description = "Updates the application configuration at runtime for a session")
  @ApiResponse(responseCode = HTTP_200, description = "Configuration updated successfully")
  @ApiResponse(responseCode = "400", description = "Invalid configuration data")
  public Mono<ResponseEntity<String>> updateConfig(
      @Valid @RequestBody final ConfigRequest request) {
    final AppConfigData configData = configMapper.toData(request);
    return sessionService
        .applyConfigOverrides(configData)
        .thenReturn(ResponseEntity.ok("Configuration updated successfully"));
  }
}
