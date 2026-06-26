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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.cli.CliFormatter;
import com.infenia.yukta.cli.YuktaDaemonClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@MockitoSettings(strictness = Strictness.STRICT_STUBS)
class GetSessionCommandTest {
  @Mock private YuktaDaemonClient mockClient;
  @Mock private CliFormatter mockFormatter;
  private GetSessionCommand command;

  @BeforeEach
  void setUp() {
    command = new GetSessionCommand(mockClient, mockFormatter);
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
  void run_printsTableByDefault() throws Exception {
    final var sessionData =
        Map.of("sessionId", "session-1", "workflowIds", List.of("workflow-1", "workflow-2"));
    when(mockClient.getSessionDetails("session-1")).thenReturn(sessionData);
    final var field = GetSessionCommand.class.getDeclaredField("sessionId");
    field.setAccessible(true);
    field.set(command, "session-1");

    command.run();

    verify(mockFormatter).printTable(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void run_printsJsonWhenFormatIsJson() throws Exception {
    final var sessionData =
        Map.of("sessionId", "session-1", "workflowIds", List.of("workflow-1", "workflow-2"));
    when(mockClient.getSessionDetails("session-1")).thenReturn(sessionData);
    final var sessionIdField = GetSessionCommand.class.getDeclaredField("sessionId");
    sessionIdField.setAccessible(true);
    sessionIdField.set(command, "session-1");
    final var outputFormatField = GetSessionCommand.class.getDeclaredField("outputFormat");
    outputFormatField.setAccessible(true);
    outputFormatField.set(command, "json");

    command.run();

    var captor = ArgumentCaptor.forClass(Map.class);
    verify(mockFormatter).printJson(captor.capture());
    assertThat(captor.getValue()).containsEntry("sessionId", "session-1");
  }

  @Test
  void run_handlesMissingSession() throws Exception {
    when(mockClient.getSessionDetails("missing-session")).thenReturn(Map.of());
    final var field = GetSessionCommand.class.getDeclaredField("sessionId");
    field.setAccessible(true);
    field.set(command, "missing-session");

    command.run();

    // Should not throw, but handle gracefully
  }

  @Test
  void run_throwsRuntimeExceptionOnClientError() throws Exception {
    when(mockClient.getSessionDetails("session-1"))
        .thenThrow(new RuntimeException("Connection error"));
    final var field = GetSessionCommand.class.getDeclaredField("sessionId");
    field.setAccessible(true);
    field.set(command, "session-1");

    assertThatThrownBy(command::run).isInstanceOf(RuntimeException.class);
  }
}
