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

import com.infenia.jagratha.model.ApiResponse;
import com.infenia.jagratha.model.SessionDetails;
import com.infenia.jagratha.model.SessionSummary;
import com.infenia.jagratha.model.WorkflowDefinition;
import com.infenia.jagratha.model.WorkflowExecutionSummary;
import com.infenia.jagratha.service.SessionService;
import com.infenia.jagratha.service.TaskTrackerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** Controller for session management. */
@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
@Tag(name = "Session API", description = "Endpoints for session and workflow discovery")
public class SessionController {

  private final SessionService sessionService;
  private final TaskTrackerService trackerService;

  /**
   * List all active sessions.
   *
   * @return list of session summaries
   */
  @GetMapping
  @Operation(summary = "List sessions", description = "Lists all active sessions with summaries")
  @SuppressWarnings("unchecked")
  public Mono<ApiResponse<List<SessionSummary>>> listSessions() {
    return sessionService
        .getActiveSessions()
        .flatMap(
            id ->
                sessionService
                    .getSessionConfig(id)
                    .map(
                        config -> {
                          final List<WorkflowExecutionSummary> history =
                              trackerService.getHistory(id);
                          final LocalDateTime lastActive =
                              history.isEmpty() ? null : history.get(0).startTime();
                          return new SessionSummary(
                              id,
                              (String) config.getOrDefault("initiator", ""),
                              (String) config.getOrDefault("initiatedTime", ""),
                              lastActive,
                              (String) config.getOrDefault("description", ""),
                              (Map<String, String>) config.getOrDefault("tags", Map.of()));
                        }))
        .collectList()
        .map(sessions -> ApiResponse.success(200, "Sessions retrieved successfully", sessions));
  }

  /**
   * Get details of a specific session.
   *
   * @param sessionId the session identifier
   * @return session details
   */
  @GetMapping("/{sessionId}")
  @Operation(
      summary = "Get session details",
      description = "Retrieves details of a specific session including workflow IDs")
  @SuppressWarnings("unchecked")
  public Mono<ResponseEntity<ApiResponse<SessionDetails>>> getSessionDetails(
      @PathVariable final String sessionId) {
    return sessionService
        .getSessionConfig(sessionId)
        .map(
            config -> {
              final Map<String, Object> workflows =
                  (Map<String, Object>) config.getOrDefault("workflows", Map.of());
              final List<String> workflowIds = List.copyOf(workflows.keySet());
              return ResponseEntity.ok(
                  ApiResponse.success(
                      200,
                      "Session details retrieved",
                      new SessionDetails(sessionId, workflowIds)));
            })
        .defaultIfEmpty(ResponseEntity.notFound().build());
  }

  /**
   * Get workflow definition.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @return workflow definition
   */
  @GetMapping("/{sessionId}/workflows/{workflowId}")
  @Operation(summary = "Get workflow", description = "Retrieves the definition of a workflow")
  public Mono<ResponseEntity<ApiResponse<WorkflowDefinition>>> getWorkflow(
      @PathVariable final String sessionId, @PathVariable final String workflowId) {
    return sessionService
        .getSessionWorkflow(sessionId, workflowId)
        .map(def -> ResponseEntity.ok(ApiResponse.success(200, "Workflow retrieved", def)))
        .defaultIfEmpty(ResponseEntity.notFound().build());
  }
}
