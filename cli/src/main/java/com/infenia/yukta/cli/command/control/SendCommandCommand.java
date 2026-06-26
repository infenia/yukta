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
import java.util.Map;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** CLI command to send a command to a node. */
@Component
@Command(name = "send-command", description = "Send a command to a node")
public class SendCommandCommand implements Runnable {

  /** Client for daemon communication. */
  private final YuktaDaemonClient daemonClient;

  /** JSON object mapper for payload parsing. */
  private final ObjectMapper objectMapper;

  /** Formatter for output display. */
  private final CliFormatter formatter;

  /**
   * Constructs a new SendCommandCommand with dependencies.
   *
   * @param daemonClient the daemon client
   * @param objectMapper the JSON object mapper
   * @param formatter the CLI formatter
   */
  public SendCommandCommand(
      YuktaDaemonClient daemonClient, ObjectMapper objectMapper, CliFormatter formatter) {
    this.daemonClient = daemonClient;
    this.objectMapper = objectMapper;
    this.formatter = formatter;
  }

  /** The workflow ID containing the target node. */
  @Parameters(index = "0", description = "Workflow ID")
  private String workflowId;

  /** The node ID to send the command to. */
  @Parameters(index = "1", description = "Node ID")
  private String nodeId;

  /** The command payload as JSON string. */
  @Parameters(index = "2", description = "JSON payload")
  private String jsonPayload;

  /** Output format for results (table or json). */
  @Option(
      names = {"-o", "--output"},
      description = "Output format: table (default), json",
      defaultValue = "table")
  private String outputFormat;

  /** Executes the command to send a command to a node and display the response. */
  @Override
  public void run() {
    try {
      final Map<String, Object> payload =
          objectMapper.readValue(jsonPayload, new TypeReference<Map<String, Object>>() {});
      final Map<String, Object> response = daemonClient.sendCommand(workflowId, nodeId, payload);
      if ("json".equals(outputFormat)) {
        formatter.printJson(response);
      } else {
        formatter.printTable(java.util.List.of(response.toString()));
      }
    } catch (Exception e) {
      System.err.println("Error sending command: " + e.getMessage());
      throw new RuntimeException(e);
    }
  }
}
