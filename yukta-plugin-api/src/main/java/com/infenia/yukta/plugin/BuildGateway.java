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
package com.infenia.yukta.plugin;

import java.io.File;
import java.util.List;
import reactor.core.publisher.Mono;

/** Gateway for executing external build tool commands. */
public interface BuildGateway extends MessagingGateway {

  /**
   * Execute a single build task in an external process.
   *
   * @param projectDir the directory where the build should run
   * @param command the command and arguments to execute
   * @param taskName the name of the task being executed
   * @param timeout the maximum time to allow for execution (seconds)
   * @return a Mono containing the process output logs
   */
  Mono<String> executeTask(File projectDir, List<String> command, String taskName, long timeout);
}
