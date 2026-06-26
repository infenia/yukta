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
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@MockitoSettings(strictness = Strictness.STRICT_STUBS)
class DaemonStopCommandTest {

  @Mock private DaemonManager mockDaemonManager;
  @Mock private CliFormatter mockFormatter;
  private DaemonStopCommand command;

  @BeforeEach
  void setUp() {
    command = new DaemonStopCommand(mockDaemonManager, mockFormatter);
  }

  @Test
  void constructor_createsInstance() {
    assertThat(command).isNotNull();
  }

  @Test
  void run_whenDaemonStopped_printsDaemonStoppedMessage() throws Exception {
    when(mockDaemonManager.stopDaemon()).thenReturn(true);

    command.run();

    verify(mockDaemonManager).stopDaemon();
    verify(mockFormatter).printTable(List.of("Daemon stopped"));
  }

  @Test
  void run_whenDaemonNotRunning_printsDaemonNotRunningMessage() throws Exception {
    when(mockDaemonManager.stopDaemon()).thenReturn(false);

    command.run();

    verify(mockFormatter).printTable(List.of("Daemon was not running"));
  }

  @Test
  void isRunnable() {
    assertThat(command).isInstanceOf(Runnable.class);
  }

  @Test
  void run_whenStopDaemonThrowsException_printsErrorAndThrows() {
    // Given
    final var exception = new RuntimeException("Failed to stop daemon");
    when(mockDaemonManager.stopDaemon()).thenThrow(exception);

    // When-Then
    assertThatThrownBy(command::run).isInstanceOf(RuntimeException.class).hasCause(exception);
  }

  @Test
  void run_whenStopDaemonThrowsExceptionWithNullMessage_stillThrows() {
    // Given
    final var exception = new IllegalStateException();
    when(mockDaemonManager.stopDaemon()).thenThrow(exception);

    // When-Then
    assertThatThrownBy(command::run).isInstanceOf(RuntimeException.class).hasCause(exception);
  }

  @Test
  void run_whenDaemonStopped_verifyMessageContent() {
    // Given
    when(mockDaemonManager.stopDaemon()).thenReturn(true);

    // When
    command.run();

    // Then
    ArgumentCaptor<List<String>> listCaptor = ArgumentCaptor.forClass(List.class);
    verify(mockFormatter).printTable(listCaptor.capture());
    List<String> capturedList = listCaptor.getValue();
    assertThat(capturedList).hasSize(1).containsExactly("Daemon stopped");
  }

  @Test
  void run_whenDaemonNotRunning_verifyMessageContent() {
    // Given
    when(mockDaemonManager.stopDaemon()).thenReturn(false);

    // When
    command.run();

    // Then
    ArgumentCaptor<List<String>> listCaptor = ArgumentCaptor.forClass(List.class);
    verify(mockFormatter).printTable(listCaptor.capture());
    List<String> capturedList = listCaptor.getValue();
    assertThat(capturedList).hasSize(1).containsExactly("Daemon was not running");
  }
}
