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
class GetWorkflowCommandTest {
  @Mock private YuktaDaemonClient mockClient;
  @Mock private CliFormatter mockFormatter;
  private GetWorkflowCommand command;

  @BeforeEach
  void setUp() {
    command = new GetWorkflowCommand(mockClient, mockFormatter);
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
  void run_printsJsonByDefault() throws Exception {
    final var workflowData =
        Map.of(
            "workflowId",
            "workflow-1",
            "description",
            "Test workflow",
            "nodes",
            List.of("node1", "node2"),
            "edges",
            List.of());
    when(mockClient.getWorkflow("session-1", "workflow-1")).thenReturn(workflowData);
    final var sessionIdField = GetWorkflowCommand.class.getDeclaredField("sessionId");
    sessionIdField.setAccessible(true);
    sessionIdField.set(command, "session-1");
    final var workflowIdField = GetWorkflowCommand.class.getDeclaredField("workflowId");
    workflowIdField.setAccessible(true);
    workflowIdField.set(command, "workflow-1");
    final var outputFormatField = GetWorkflowCommand.class.getDeclaredField("outputFormat");
    outputFormatField.setAccessible(true);
    outputFormatField.set(command, "json");

    command.run();

    var captor = ArgumentCaptor.forClass(Map.class);
    verify(mockFormatter).printJson(captor.capture());
    assertThat(captor.getValue()).containsEntry("workflowId", "workflow-1");
  }

  @Test
  void run_printsTableWhenFormatIsTable() throws Exception {
    final var workflowData =
        Map.of(
            "workflowId",
            "workflow-1",
            "description",
            "Test workflow",
            "nodes",
            List.of("node1"),
            "edges",
            List.of());
    when(mockClient.getWorkflow("session-1", "workflow-1")).thenReturn(workflowData);
    final var sessionIdField = GetWorkflowCommand.class.getDeclaredField("sessionId");
    sessionIdField.setAccessible(true);
    sessionIdField.set(command, "session-1");
    final var workflowIdField = GetWorkflowCommand.class.getDeclaredField("workflowId");
    workflowIdField.setAccessible(true);
    workflowIdField.set(command, "workflow-1");
    final var outputFormatField = GetWorkflowCommand.class.getDeclaredField("outputFormat");
    outputFormatField.setAccessible(true);
    outputFormatField.set(command, "table");

    command.run();

    verify(mockFormatter).printTable(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void run_handlesMissingWorkflow() throws Exception {
    when(mockClient.getWorkflow("session-1", "missing-workflow")).thenReturn(Map.of());
    final var sessionIdField = GetWorkflowCommand.class.getDeclaredField("sessionId");
    sessionIdField.setAccessible(true);
    sessionIdField.set(command, "session-1");
    final var workflowIdField = GetWorkflowCommand.class.getDeclaredField("workflowId");
    workflowIdField.setAccessible(true);
    workflowIdField.set(command, "missing-workflow");

    command.run();

    // Should not throw, but handle gracefully
  }

  @Test
  void run_throwsRuntimeExceptionOnClientError() throws Exception {
    when(mockClient.getWorkflow("session-1", "workflow-1"))
        .thenThrow(new RuntimeException("Connection error"));
    final var sessionIdField = GetWorkflowCommand.class.getDeclaredField("sessionId");
    sessionIdField.setAccessible(true);
    sessionIdField.set(command, "session-1");
    final var workflowIdField = GetWorkflowCommand.class.getDeclaredField("workflowId");
    workflowIdField.setAccessible(true);
    workflowIdField.set(command, "workflow-1");

    assertThatThrownBy(command::run).isInstanceOf(RuntimeException.class);
  }
}
