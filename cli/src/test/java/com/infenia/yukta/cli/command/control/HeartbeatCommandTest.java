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
class HeartbeatCommandTest {
  @Mock private YuktaDaemonClient mockClient;
  @Mock private CliFormatter mockFormatter;
  private HeartbeatCommand command;

  @BeforeEach
  void setUp() {
    command = new HeartbeatCommand(mockClient, mockFormatter);
  }

  @Test
  void constructor_createsInstance() {
    assertThat(command).isNotNull();
  }

  @Test
  void constructor_acceptsDependencies() {
    HeartbeatCommand testCommand = new HeartbeatCommand(mockClient, mockFormatter);
    assertThat(testCommand).isNotNull();
  }

  @Test
  void isRunnable() {
    assertThat(command).isInstanceOf(Runnable.class);
  }

  @Test
  void run_printsTableByDefault() {
    final var workflowId = "workflow-1";
    final var nodeId = "node-1";
    final Map<String, Object> heartbeat = Map.of("timestamp", 1234567890L, "status", "active");
    when(mockClient.getLastHeartbeat(workflowId, nodeId)).thenReturn(heartbeat);
    try {
      setPrivateField(command, "workflowId", workflowId);
      setPrivateField(command, "nodeId", nodeId);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new RuntimeException(e);
    }

    command.run();

    verify(mockFormatter).printTable(List.of(heartbeat.toString()));
  }

  @Test
  void run_printsJsonWhenFormatIsJson() throws Exception {
    final var workflowId = "workflow-1";
    final var nodeId = "node-1";
    final Map<String, Object> heartbeat = Map.of("timestamp", 1234567890L, "status", "active");
    when(mockClient.getLastHeartbeat(workflowId, nodeId)).thenReturn(heartbeat);
    try {
      setPrivateField(command, "workflowId", workflowId);
      setPrivateField(command, "nodeId", nodeId);
      setPrivateField(command, "outputFormat", "json");
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new RuntimeException(e);
    }

    command.run();

    verify(mockFormatter).printJson(heartbeat);
  }

  @Test
  void run_throwsRuntimeExceptionOnFormatterError() {
    final var workflowId = "workflow-1";
    final var nodeId = "node-1";
    final Map<String, Object> heartbeat = Map.of("timestamp", 1234567890L, "status", "active");
    when(mockClient.getLastHeartbeat(workflowId, nodeId)).thenReturn(heartbeat);
    doThrow(new IllegalArgumentException("Invalid format"))
        .when(mockFormatter)
        .printTable(List.of(heartbeat.toString()));
    try {
      setPrivateField(command, "workflowId", workflowId);
      setPrivateField(command, "nodeId", nodeId);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new RuntimeException(e);
    }

    assertThatThrownBy(command::run).isInstanceOf(RuntimeException.class);
  }

  private void setPrivateField(Object target, String fieldName, Object value)
      throws NoSuchFieldException, IllegalAccessException {
    final var field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    field.set(target, value);
  }
}
