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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefaultProcessProviderTest {

  private DefaultProcessProvider provider;

  @TempDir private File tempDir;

  @BeforeEach
  void setUp() {
    provider = new DefaultProcessProvider();
  }

  // startProcess() tests

  @Test
  void startProcess_echoCommand_startsProcessSuccessfully() throws Exception {
    File stdout = new File(tempDir, "stdout.txt");
    File stderr = new File(tempDir, "stderr.txt");
    List<String> command = List.of("echo", "hello");

    Process process = provider.startProcess(command, tempDir, stdout, stderr);

    assertThat(process).isNotNull();
    int exitCode = process.waitFor();
    assertThat(exitCode).isZero();
  }

  @Test
  void startProcess_redirectsOutputToFile_createsOutputFile() throws Exception {
    File stdout = new File(tempDir, "output.txt");
    File stderr = new File(tempDir, "error.txt");
    List<String> command = List.of("echo", "test output");

    Process process = provider.startProcess(command, tempDir, stdout, stderr);
    process.waitFor();

    assertThat(stdout).exists();
  }

  @Test
  void startProcess_withWorkingDirectory_setsProcessDirectory() throws Exception {
    File stdout = new File(tempDir, "stdout.txt");
    File stderr = new File(tempDir, "stderr.txt");
    List<String> command = List.of("pwd");

    Process process = provider.startProcess(command, tempDir, stdout, stderr);
    int exitCode = process.waitFor();

    assertThat(exitCode).isZero();
  }

  @Test
  void startProcess_invalidCommand_throwsException() {
    File stdout = new File(tempDir, "stdout.txt");
    File stderr = new File(tempDir, "stderr.txt");
    List<String> command = List.of("nonexistent_command_xyz123");

    assertThatThrownBy(() -> provider.startProcess(command, tempDir, stdout, stderr))
        .isInstanceOf(Exception.class);
  }

  @Test
  void startProcess_emptyCommand_throwsException() {
    File stdout = new File(tempDir, "stdout.txt");
    File stderr = new File(tempDir, "stderr.txt");
    List<String> command = List.of();

    assertThatThrownBy(() -> provider.startProcess(command, tempDir, stdout, stderr))
        .isInstanceOf(Exception.class);
  }

  // findProcess() tests

  @Test
  void findProcess_currentProcessPid_returnsProcessHandle() {
    long currentPid = ProcessHandle.current().pid();

    Optional<ProcessHandle> result = provider.findProcess(currentPid);

    assertThat(result).isNotEmpty();
  }

  @Test
  void findProcess_invalidPid_returnsEmpty() {
    long invalidPid = 999999999L;

    Optional<ProcessHandle> result = provider.findProcess(invalidPid);

    assertThat(result).isEmpty();
  }

  @Test
  void findProcess_zeroPid_returnsEmpty() {
    Optional<ProcessHandle> result = provider.findProcess(0);

    assertThat(result).isEmpty();
  }

  // currentProcessPid() tests

  @Test
  void currentProcessPid_retrievePid_returnsPidOfCurrentProcess() {
    long pid = provider.currentProcessPid();

    assertThat(pid).isPositive();
  }

  @Test
  void currentProcessPid_pidIsValid_matchesProcessHandleCurrentPid() {
    long providerPid = provider.currentProcessPid();
    long expectedPid = ProcessHandle.current().pid();

    assertThat(providerPid).isEqualTo(expectedPid);
  }

  @Test
  void currentProcessPid_pidIsGreaterThanZero_verifyValidRange() {
    long pid = provider.currentProcessPid();

    assertThat(pid).isGreaterThan(0);
  }

  // currentProcessCommand() tests

  @Test
  void currentProcessCommand_commandAvailable_returnsCommand() {
    Optional<String> command = provider.currentProcessCommand();

    assertThat(command).isNotEmpty();
  }

  @Test
  void currentProcessCommand_returnsOptional() {
    Optional<String> command = provider.currentProcessCommand();

    assertThat(command).isInstanceOf(Optional.class);
  }

  @Test
  void currentProcessCommand_commandContainsJavaKeyword() {
    Optional<String> command = provider.currentProcessCommand();

    assertThat(command).isPresent();
    assertThat(command.get().toLowerCase()).contains("java");
  }
}
