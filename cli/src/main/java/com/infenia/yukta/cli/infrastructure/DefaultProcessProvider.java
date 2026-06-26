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
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Default implementation of process provider using Java ProcessBuilder. */
@Component
public class DefaultProcessProvider implements ProcessProvider {

  /**
   * Starts a new process with the given command and redirects.
   *
   * @param command the command with arguments
   * @param workDir the working directory
   * @param stdout the file to redirect stdout to
   * @param stderr the file to redirect stderr to
   * @return the started Process
   * @throws Exception if process creation fails
   */
  @Override
  public Process startProcess(List<String> command, File workDir, File stdout, File stderr)
      throws Exception {
    ProcessBuilder pb = new ProcessBuilder(command);
    pb.directory(workDir);
    pb.redirectOutput(ProcessBuilder.Redirect.to(stdout));
    pb.redirectError(ProcessBuilder.Redirect.to(stderr));
    return pb.start();
  }

  /**
   * Finds a process by its process ID.
   *
   * @param pid the process ID
   * @return the ProcessHandle if found
   */
  @Override
  public Optional<ProcessHandle> findProcess(long pid) {
    return ProcessHandle.of(pid);
  }

  /**
   * Gets the process ID of the current JVM.
   *
   * @return the current process PID
   */
  @Override
  public long currentProcessPid() {
    return ProcessHandle.current().pid();
  }

  /**
   * Gets the command used to start the current process.
   *
   * @return the process command if available
   */
  @Override
  public Optional<String> currentProcessCommand() {
    return ProcessHandle.current().info().command();
  }
}
