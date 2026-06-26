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
import com.infenia.yukta.cli.DaemonStatus;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@MockitoSettings(strictness = Strictness.STRICT_STUBS)
class DaemonStatusCommandTest {

  @Mock private DaemonManager mockDaemonManager;
  @Mock private CliFormatter mockFormatter;
  private DaemonStatusCommand command;

  @BeforeEach
  void setUp() {
    command = new DaemonStatusCommand(mockDaemonManager, mockFormatter);
  }

  @Test
  void constructor_createsInstance() {
    assertThat(command).isNotNull();
  }

  @Test
  void run_whenDaemonRunning_printsRunningStatus() throws Exception {
    final var status = new DaemonStatus(true, 1234L, "http://127.0.0.1:8080");
    when(mockDaemonManager.status()).thenReturn(status);

    command.run();

    verify(mockDaemonManager).status();
    verify(mockFormatter)
        .printJson(Map.of("running", true, "pid", 1234L, "url", "http://127.0.0.1:8080"));
  }

  @Test
  void run_whenDaemonNotRunning_printsNotRunningStatus() throws Exception {
    final var status = new DaemonStatus(false, -1L, null);
    when(mockDaemonManager.status()).thenReturn(status);

    command.run();

    verify(mockFormatter).printJson(Map.of("running", false, "pid", -1L, "url", "N/A"));
  }

  @Test
  void isRunnable() {
    assertThat(command).isInstanceOf(Runnable.class);
  }

  @Test
  void run_whenStatusThrowsException_printsErrorAndThrows() {
    // Given
    final var exception = new RuntimeException("Daemon connection failed");
    when(mockDaemonManager.status()).thenThrow(exception);

    // When-Then
    assertThatThrownBy(command::run).isInstanceOf(RuntimeException.class).hasCause(exception);
  }

  @Test
  void run_whenStatusThrowsExceptionWithNullMessage_printsExceptionString() {
    // Given
    final var exception = new NullPointerException();
    when(mockDaemonManager.status()).thenThrow(exception);

    // When-Then
    assertThatThrownBy(command::run).isInstanceOf(RuntimeException.class).hasCause(exception);
  }

  @Test
  void run_whenDaemonRunningWithNullUrl_printsNotAvailable() throws Exception {
    // Given
    final var status = new DaemonStatus(true, 5678L, null);
    when(mockDaemonManager.status()).thenReturn(status);

    // When
    command.run();

    // Then
    ArgumentCaptor<Map<String, Object>> mapCaptor = ArgumentCaptor.forClass(Map.class);
    verify(mockFormatter).printJson(mapCaptor.capture());
    Map<String, Object> capturedMap = mapCaptor.getValue();
    assertThat(capturedMap).containsEntry("running", true).containsEntry("pid", 5678L);
    assertThat(capturedMap).containsEntry("url", "N/A");
  }
}
