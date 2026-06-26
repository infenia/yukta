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

/** CLI command to retrieve session details by session ID. */
@Component
@Command(name = "get-session", description = "Get session details by session ID")
public class GetSessionCommand implements Runnable {

  /** The daemon client for interacting with Yukta daemon. */
  private final YuktaDaemonClient daemonClient;

  /** The CLI formatter for output formatting. */
  private final CliFormatter formatter;

  /**
   * Constructs a new GetSessionCommand with dependencies.
   *
   * @param daemonClient the daemon client
   * @param formatter the CLI formatter
   */
  public GetSessionCommand(YuktaDaemonClient daemonClient, CliFormatter formatter) {
    this.daemonClient = daemonClient;
    this.formatter = formatter;
  }

  /** The session ID to retrieve details for. */
  @Parameters(index = "0", description = "Session ID to retrieve details for")
  private String sessionId;

  /** The output format: table (default) or json. */
  @Option(
      names = {"-o", "--output"},
      description = "Output format: table (default), json",
      defaultValue = "table")
  private String outputFormat;

  /** Executes the command to retrieve and display session details. */
  @Override
  public void run() {
    try {
      final Map<String, Object> result = daemonClient.getSessionDetails(sessionId);

      if (result.isEmpty()) {
        System.err.println("Session not found: " + sessionId);
        return;
      }

      if ("json".equals(outputFormat)) {
        formatter.printJson(result);
      } else {
        final List<String> rows =
            List.of(
                "Session ID: " + result.get("sessionId"),
                "Workflows: " + result.getOrDefault("workflowIds", List.of()));
        formatter.printTable(rows);
      }
    } catch (Exception e) {
      System.err.println("Error retrieving session: " + e.getMessage());
      throw new RuntimeException(e);
    }
  }
}
