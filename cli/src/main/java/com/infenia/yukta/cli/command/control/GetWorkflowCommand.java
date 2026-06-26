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
package com.infenia.yukta.cli.command.control;

import com.infenia.yukta.cli.CliFormatter;
import com.infenia.yukta.cli.YuktaDaemonClient;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** CLI command to retrieve workflow definition details. */
@Component
@Command(
    name = "get-workflow",
    description = "Get workflow definition for a specific session and workflow ID")
public class GetWorkflowCommand implements Runnable {

  /** The daemon client for interacting with Yukta daemon. */
  private final YuktaDaemonClient daemonClient;

  /** The CLI formatter for output formatting. */
  private final CliFormatter formatter;

  /**
   * Constructs a new GetWorkflowCommand with dependencies.
   *
   * @param daemonClient the daemon client
   * @param formatter the CLI formatter
   */
  public GetWorkflowCommand(YuktaDaemonClient daemonClient, CliFormatter formatter) {
    this.daemonClient = daemonClient;
    this.formatter = formatter;
  }

  /** The session ID. */
  @Parameters(index = "0", description = "Session ID")
  private String sessionId;

  /** The workflow ID to retrieve. */
  @Parameters(index = "1", description = "Workflow ID to retrieve")
  private String workflowId;

  /** The output format: table (default) or json. */
  @Option(
      names = {"-o", "--output"},
      description = "Output format: table (default), json",
      defaultValue = "json")
  private String outputFormat;

  /** Executes the command to retrieve and display workflow details. */
  @Override
  public void run() {
    try {
      final Map<String, Object> result = daemonClient.getWorkflow(sessionId, workflowId);

      if (result.isEmpty()) {
        System.err.println("Workflow not found: " + workflowId + " in session " + sessionId);
        return;
      }

      if ("json".equals(outputFormat)) {
        formatter.printJson(result);
      } else {
        final List<String> rows =
            List.of(
                "Workflow ID: " + result.get("workflowId"),
                "Description: " + result.getOrDefault("description", "N/A"),
                "Nodes: " + result.getOrDefault("nodes", List.of()),
                "Edges: " + result.getOrDefault("edges", List.of()));
        formatter.printTable(rows);
      }
    } catch (Exception e) {
      System.err.println("Error retrieving workflow: " + e.getMessage());
      throw new RuntimeException(e);
    }
  }
}
