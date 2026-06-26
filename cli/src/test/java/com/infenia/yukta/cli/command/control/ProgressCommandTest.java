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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.cli.CliFormatter;
import com.infenia.yukta.cli.YuktaDaemonClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@MockitoSettings(strictness = Strictness.STRICT_STUBS)
class ProgressCommandTest {
  @Mock private YuktaDaemonClient mockClient;
  @Mock private CliFormatter mockFormatter;
  private ProgressCommand command;

  @BeforeEach
  void setUp() {
    command = new ProgressCommand(mockClient, mockFormatter);
  }

  @Test
  void constructor_createsInstance() {
    assertThat(command).isNotNull();
  }

  @Test
  void isRunnable() {
    assertThat(command).isInstanceOf(Runnable.class);
  }

  @Test
  void run_printsTableByDefault() {
    final var executionId = "exec-123";
    final Map<String, Object> progress = Map.of("status", "RUNNING", "progress", 50);
    when(mockClient.getProgress(executionId)).thenReturn(progress);
    try {
      final var field = ProgressCommand.class.getDeclaredField("executionId");
      field.setAccessible(true);
      field.set(command, executionId);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new RuntimeException(e);
    }

    command.run();

    verify(mockFormatter).printTable(List.of(progress.toString()));
  }

  @Test
  void run_printsJsonWhenFormatIsJson() throws Exception {
    final var executionId = "exec-123";
    final Map<String, Object> progress = Map.of("status", "RUNNING", "progress", 50);
    when(mockClient.getProgress(executionId)).thenReturn(progress);
    try {
      final var idField = ProgressCommand.class.getDeclaredField("executionId");
      idField.setAccessible(true);
      idField.set(command, executionId);
      final var formatField = ProgressCommand.class.getDeclaredField("outputFormat");
      formatField.setAccessible(true);
      formatField.set(command, "json");
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new RuntimeException(e);
    }

    command.run();

    verify(mockFormatter).printJson(progress);
  }

  @Test
  void run_throwsRuntimeExceptionOnFormatterError() {
    final var executionId = "exec-123";
    final Map<String, Object> progress = Map.of("status", "RUNNING");
    when(mockClient.getProgress(executionId)).thenReturn(progress);
    doThrow(new IllegalArgumentException("Invalid format"))
        .when(mockFormatter)
        .printTable(List.of(progress.toString()));
    try {
      final var field = ProgressCommand.class.getDeclaredField("executionId");
      field.setAccessible(true);
      field.set(command, executionId);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new RuntimeException(e);
    }

    assertThatThrownBy(command::run).isInstanceOf(RuntimeException.class);
  }
}
