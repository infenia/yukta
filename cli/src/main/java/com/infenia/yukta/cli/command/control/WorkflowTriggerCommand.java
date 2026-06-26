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

/** CLI command to trigger a workflow execution. */
@Component
@Command(name = "trigger", description = "Trigger a workflow execution")
public class WorkflowTriggerCommand implements Runnable {

  /** Client for daemon communication. */
  private final YuktaDaemonClient daemonClient;

  /** Formatter for output display. */
  private final CliFormatter formatter;

  /**
   * Constructs a new WorkflowTriggerCommand with dependencies.
   *
   * @param daemonClient the daemon client
   * @param formatter the CLI formatter
   */
  public WorkflowTriggerCommand(YuktaDaemonClient daemonClient, CliFormatter formatter) {
    this.daemonClient = daemonClient;
    this.formatter = formatter;
  }

  /** The session ID to trigger the workflow in. */
  @Parameters(index = "0", description = "Session ID")
  private String sessionId;

  /** The workflow ID to trigger. */
  @Parameters(index = "1", description = "Workflow ID")
  private String workflowId;

  /** Output format for results (table or json). */
  @Option(
      names = {"-o", "--output"},
      description = "Output format: table (default), json",
      defaultValue = "table")
  private String outputFormat;

  /** Executes the command to trigger a workflow and display the execution ID. */
  @Override
  public void run() {
    try {
      final String executionId = daemonClient.triggerWorkflow(sessionId, workflowId);

      if ("json".equals(outputFormat)) {
        formatter.printJson(Map.of("executionId", executionId));
      } else {
        formatter.printTable(List.of("Execution ID: " + executionId));
      }
    } catch (Exception e) {
      System.err.println("Error triggering workflow: " + e.getMessage());
      throw new RuntimeException(e);
    }
  }
}
