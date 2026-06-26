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
package com.infenia.yukta.cli.command;

import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;

/** CLI command for managing the Yukta daemon. */
@Component
@Command(
    name = "daemon",
    mixinStandardHelpOptions = true,
    description = "Manage the Yukta background daemon")
public class DaemonCommand implements Runnable {

  /** Throws an exception requesting a subcommand. */
  @Override
  public void run() {
    throw new picocli.CommandLine.ExecutionException(
        new picocli.CommandLine("daemon"), "Missing subcommand");
  }
}
