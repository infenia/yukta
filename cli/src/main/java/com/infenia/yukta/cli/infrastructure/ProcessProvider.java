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
package com.infenia.yukta.cli.infrastructure;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

/** Adapter interface for process management operations. */
public interface ProcessProvider {
  /**
   * Starts a new process with the given command, working directory, and I/O redirects.
   *
   * @param command the command with arguments
   * @param workDir the working directory
   * @param stdout the file to redirect stdout to
   * @param stderr the file to redirect stderr to
   * @return the started Process
   */
  Process startProcess(List<String> command, File workDir, File stdout, File stderr)
      throws IOException;

  /**
   * Finds a process by PID.
   *
   * @param pid the process ID
   * @return the ProcessHandle, or empty if not found
   */
  Optional<ProcessHandle> findProcess(long pid);

  /**
   * Gets the current process ID.
   *
   * @return the PID of the current JVM process
   */
  long currentProcessPid();

  /**
   * Gets the command used to start the current process.
   *
   * @return the command, or empty if not available
   */
  Optional<String> currentProcessCommand();
}
