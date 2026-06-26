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

import com.infenia.yukta.cli.YuktaDaemonClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/** CLI command to stream execution logs in real-time. */
@Component
@RequiredArgsConstructor
@Command(name = "logs-stream", description = "Stream execution logs in real-time")
public class LogsStreamCommand implements Runnable {

  /** Client for daemon communication. */
  private final YuktaDaemonClient daemonClient;

  /** The execution ID to stream logs for. */
  @Parameters(index = "0", description = "Execution ID")
  private String executionId;

  /** Executes the command to stream and display execution logs. */
  @Override
  public void run() {
    daemonClient.streamLogs(executionId, System.out::println);
  }
}
