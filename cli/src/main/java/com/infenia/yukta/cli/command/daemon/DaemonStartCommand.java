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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

/** CLI command to start the Yukta daemon. */
@Component
@RequiredArgsConstructor
@Command(name = "start", description = "Start the Yukta daemon if not already running")
public class DaemonStartCommand implements Runnable {

  /** Manager for daemon lifecycle. */
  private final DaemonManager daemonManager;

  /** Formatter for output display. */
  private final CliFormatter formatter;

  /** Executes the command to start the daemon and display its URL. */
  @Override
  public void run() {
    try {
      String url = daemonManager.ensureRunning();
      formatter.printTable(java.util.List.of("Daemon is running at: " + url));
    } catch (Exception e) {
      System.err.println("Failed to start daemon: " + e.getMessage());
      throw new RuntimeException(e);
    }
  }
}
