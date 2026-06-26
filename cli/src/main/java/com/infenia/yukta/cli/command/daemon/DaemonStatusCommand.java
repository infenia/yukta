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
package com.infenia.yukta.cli.command.daemon;

import com.infenia.yukta.cli.CliFormatter;
import com.infenia.yukta.cli.DaemonManager;
import com.infenia.yukta.cli.DaemonStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

/** CLI command to display the current daemon status. */
@Component
@RequiredArgsConstructor
@Command(name = "status", description = "Show daemon status")
public class DaemonStatusCommand implements Runnable {

  /** Manager for daemon lifecycle. */
  private final DaemonManager daemonManager;

  /** Formatter for output display. */
  private final CliFormatter formatter;

  /** Executes the command to retrieve and display daemon status. */
  @Override
  public void run() {
    try {
      DaemonStatus status = daemonManager.status();
      formatter.printJson(
          java.util.Map.of(
              "running", status.running(),
              "pid", status.pid(),
              "url", status.url() != null ? status.url() : "N/A"));
    } catch (Exception e) {
      System.err.println(
          "Error checking daemon status: "
              + (e.getMessage() != null ? e.getMessage() : e.toString()));
      throw new RuntimeException(e);
    }
  }
}
