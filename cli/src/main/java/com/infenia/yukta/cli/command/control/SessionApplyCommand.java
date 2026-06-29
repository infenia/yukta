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
import com.infenia.yukta.dto.request.ConfigRequest;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import tools.jackson.databind.ObjectMapper;

/** CLI command to apply session configuration from a JSON file or string. */
@Component
@Command(
    name = "session-apply",
    description = "Apply session configuration from JSON file or string")
public class SessionApplyCommand implements Runnable {

  /** Client for daemon communication. */
  private final YuktaDaemonClient daemonClient;

  /** JSON object mapper for configuration parsing. */
  private final ObjectMapper objectMapper;

  /** Formatter for output display. */
  private final CliFormatter formatter;

  /**
   * Constructs a new SessionApplyCommand with dependencies.
   *
   * @param daemonClient the daemon client
   * @param objectMapper the JSON object mapper
   * @param formatter the CLI formatter
   */
  public SessionApplyCommand(
      final YuktaDaemonClient daemonClient,
      final ObjectMapper objectMapper,
      final CliFormatter formatter) {
    this.daemonClient = daemonClient;
    this.objectMapper = objectMapper;
    this.formatter = formatter;
  }

  /** Path to JSON config file or inline JSON string. */
  @Parameters(
      index = "0",
      description = "Path to JSON config file OR inline JSON string representing ConfigRequest")
  private String configInput;

  /** Output format for results (table or json). */
  @Option(
      names = {"-o", "--output"},
      description = "Output format: table (default), json",
      defaultValue = "table")
  private String outputFormat;

  /** Executes the command to apply session configuration. */
  @Override
  public void run() {
    try {
      final String jsonContent = readConfigInput(configInput);
      final ConfigRequest request = objectMapper.readValue(jsonContent, ConfigRequest.class);
      daemonClient.applySession(request);

      final String message = "Session applied: " + request.sessionId();
      if ("json".equals(outputFormat)) {
        formatter.printJson(java.util.Map.of("status", "success", "message", message));
      } else {
        formatter.printTable(List.of(message));
      }
    } catch (final Exception e) {
      System.err.println("Error applying session: " + e.getMessage());
      throw new RuntimeException(e);
    }
  }

  /**
   * Reads config input from a file or treats it as inline JSON.
   *
   * @param input file path or JSON string
   * @return the JSON content
   * @throws Exception if reading the file fails
   */
  private String readConfigInput(final String input) throws Exception {
    try {
      final Path path = Path.of(input);
      if (Files.exists(path) && Files.isRegularFile(path)) {
        return Files.readString(path, Charset.defaultCharset());
      }
    } catch (final Exception ignored) {
      // Not a valid file path, treat as inline JSON
    }
    return input;
  }
}
