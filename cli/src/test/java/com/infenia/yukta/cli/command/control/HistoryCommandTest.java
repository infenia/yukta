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
class HistoryCommandTest {
  @Mock private YuktaDaemonClient mockClient;
  @Mock private CliFormatter mockFormatter;
  private HistoryCommand command;

  @BeforeEach
  void setUp() {
    command = new HistoryCommand(mockClient, mockFormatter);
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
    final var sessionId = "session-123";
    final var history = List.<Map<String, Object>>of(Map.of("key", (Object) "value"));
    when(mockClient.getHistory(sessionId)).thenReturn(history);
    try {
      final var field = HistoryCommand.class.getDeclaredField("sessionId");
      field.setAccessible(true);
      field.set(command, sessionId);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new RuntimeException(e);
    }

    command.run();

    verify(mockFormatter).printTable(List.of(Map.of("key", "value").toString()));
  }

  @Test
  void run_printsJsonWhenFormatIsJson() throws Exception {
    final var sessionId = "session-456";
    final var history = List.<Map<String, Object>>of(Map.of("key", (Object) "value"));
    when(mockClient.getHistory(sessionId)).thenReturn(history);
    try {
      final var sessionIdField = HistoryCommand.class.getDeclaredField("sessionId");
      sessionIdField.setAccessible(true);
      sessionIdField.set(command, sessionId);
      final var formatField = HistoryCommand.class.getDeclaredField("outputFormat");
      formatField.setAccessible(true);
      formatField.set(command, "json");
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new RuntimeException(e);
    }

    command.run();

    verify(mockFormatter).printJson(history);
  }

  @Test
  void run_throwsRuntimeExceptionOnFormatterError() {
    final var sessionId = "session-789";
    final var history = List.<Map<String, Object>>of(Map.of("key", (Object) "value"));
    when(mockClient.getHistory(sessionId)).thenReturn(history);
    try {
      final var field = HistoryCommand.class.getDeclaredField("sessionId");
      field.setAccessible(true);
      field.set(command, sessionId);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new RuntimeException(e);
    }
    doThrow(new IllegalArgumentException("Invalid format"))
        .when(mockFormatter)
        .printTable(List.of(Map.of("key", "value").toString()));

    assertThatThrownBy(command::run).isInstanceOf(RuntimeException.class);
  }
}
