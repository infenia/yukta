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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.cli.CliFormatter;
import com.infenia.yukta.cli.DaemonManager;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@MockitoSettings(strictness = Strictness.STRICT_STUBS)
class DaemonStartCommandTest {

  @Mock private DaemonManager mockDaemonManager;
  @Mock private CliFormatter mockFormatter;
  private DaemonStartCommand command;

  @BeforeEach
  void setUp() {
    command = new DaemonStartCommand(mockDaemonManager, mockFormatter);
  }

  @Test
  void constructor_createsInstance() {
    assertThat(command).isNotNull();
  }

  @Test
  void run_whenDaemonStartsSuccessfully_printsSuccess() throws Exception {
    when(mockDaemonManager.ensureRunning()).thenReturn("http://127.0.0.1:8080");

    command.run();

    verify(mockDaemonManager).ensureRunning();
    verify(mockFormatter).printTable(List.of("Daemon is running at: http://127.0.0.1:8080"));
  }

  @Test
  void run_whenDaemonStartsWithCustomPort_printsCorrectUrl() throws Exception {
    when(mockDaemonManager.ensureRunning()).thenReturn("http://127.0.0.1:9090");

    command.run();

    verify(mockFormatter).printTable(List.of("Daemon is running at: http://127.0.0.1:9090"));
  }

  @Test
  void run_whenExceptionThrown_printsErrorAndThrowsRuntime() throws Exception {
    when(mockDaemonManager.ensureRunning()).thenThrow(new RuntimeException("Failed to start"));

    ByteArrayOutputStream errContent = new ByteArrayOutputStream();
    System.setErr(new PrintStream(errContent));

    assertThatThrownBy(command::run).isInstanceOf(RuntimeException.class);

    String error = errContent.toString();
    assertThat(error).contains("Failed to start daemon");
    System.setErr(System.err);
  }

  @Test
  void isRunnable() {
    assertThat(command).isInstanceOf(Runnable.class);
  }
}
