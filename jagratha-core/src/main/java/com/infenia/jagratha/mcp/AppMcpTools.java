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
package com.infenia.jagratha.mcp;

import com.infenia.jagratha.service.LogRetrievalService;
import com.infenia.jagratha.service.WorkflowService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * MCP (Model Context Protocol) tools for application. Provides tools for interacting with the
 * external project managed by application.
 */
@Component
@RequiredArgsConstructor
public class AppMcpTools {

  private final WorkflowService workflowService;
  private final LogRetrievalService logService;

  private static final String MCP_SESSION_ID = "mcp-session";

  /**
   * Get current status of the external project managed by application.
   *
   * @return status message indicating application is ready
   */
  @Tool(description = "Get current status of the external project managed by Jagratha")
  public String getProjectStatus() {
    return "Jagratha is managing the project and ready to run quality checks.";
  }

  /**
   * Trigger quality checks on the external project. Runs spotless, checkstyle, and tests.
   *
   * @return Mono containing the status and output of the quality checks
   */
  @Tool(
      description = "Trigger quality checks (spotless, checkstyle, tests) on the external project")
  public Mono<String> triggerQualityChecks() {
    return workflowService
        .runWorkflow(MCP_SESSION_ID, "quality-check", java.util.Map.of())
        .result()
        .map(response -> "Status: " + response.status() + "\n\nOutput:\n" + response.output());
  }

  /**
   * List quality check log files for the external project.
   *
   * @return Mono containing a list of log filenames
   */
  @Tool(description = "List quality check log files for the external project")
  public Mono<List<String>> listQualityCheckLogs() {
    return logService.listLogs(MCP_SESSION_ID);
  }

  /**
   * Get the content of a specific quality check log file.
   *
   * @param filename the log filename to retrieve
   * @return Mono containing the log content
   */
  @Tool(description = "Get the content of a specific quality check log file")
  public Mono<String> getQualityCheckLogContent(final String filename) {
    return logService.getLogContent(MCP_SESSION_ID, filename);
  }
}
