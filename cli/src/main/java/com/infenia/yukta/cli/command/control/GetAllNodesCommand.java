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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** CLI command to retrieve all active nodes across workflows. */
@Component
@RequiredArgsConstructor
@Command(name = "get-all", description = "Get all active nodes across workflows")
public class GetAllNodesCommand implements Runnable {

  /** Client for daemon communication. */
  private final YuktaDaemonClient daemonClient;

  /** Formatter for output display. */
  private final CliFormatter formatter;

  /** Output format for results (table or json). */
  @Option(
      names = {"-o", "--output"},
      description = "Output format: table (default), json",
      defaultValue = "table")
  private String outputFormat;

  /** Executes the command to retrieve and display all active nodes. */
  @Override
  public void run() {
    final List<String> nodes = daemonClient.getAllActiveNodes();
    try {
      if ("json".equals(outputFormat)) {
        formatter.printJson(nodes);
      } else {
        formatter.printTable(nodes);
      }
    } catch (Exception e) {
      System.err.println("Error formatting output: " + e.getMessage());
      throw new RuntimeException(e);
    }
  }
}
