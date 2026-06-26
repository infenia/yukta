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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
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
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@MockitoSettings(strictness = Strictness.STRICT_STUBS)
class SendCommandCommandTest {
  @Mock private YuktaDaemonClient mockClient;
  @Mock private ObjectMapper mockMapper;
  @Mock private CliFormatter mockFormatter;
  private SendCommandCommand command;

  @BeforeEach
  void setUp() {
    command = new SendCommandCommand(mockClient, mockMapper, mockFormatter);
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
  void run_sendsCommandAndPrintsTableByDefault() throws Exception {
    final var workflowId = "workflow-1";
    final var nodeId = "node-1";
    final var jsonPayload = "{\"key\":\"value\"}";
    final Map<String, Object> response = new java.util.HashMap<>();
    response.put("status", "success");
    when(mockMapper.readValue(eq(jsonPayload), any(TypeReference.class)))
        .thenReturn(Map.of("key", "value"));
    when(mockClient.sendCommand(eq(workflowId), eq(nodeId), anyMap())).thenReturn(response);
    setPrivateField("workflowId", workflowId);
    setPrivateField("nodeId", nodeId);
    setPrivateField("jsonPayload", jsonPayload);

    command.run();

    verify(mockFormatter).printTable(List.of(response.toString()));
  }

  @Test
  void run_sendsCommandAndPrintsJsonWhenFormatIsJson() throws Exception {
    final var workflowId = "workflow-1";
    final var nodeId = "node-1";
    final var jsonPayload = "{\"key\":\"value\"}";
    final Map<String, Object> response = new java.util.HashMap<>();
    response.put("status", "success");
    when(mockMapper.readValue(eq(jsonPayload), any(TypeReference.class)))
        .thenReturn(Map.of("key", "value"));
    when(mockClient.sendCommand(eq(workflowId), eq(nodeId), anyMap())).thenReturn(response);
    setPrivateField("workflowId", workflowId);
    setPrivateField("nodeId", nodeId);
    setPrivateField("jsonPayload", jsonPayload);
    setPrivateField("outputFormat", "json");

    command.run();

    verify(mockFormatter).printJson(response);
  }

  @Test
  void run_throwsRuntimeExceptionOnError() throws Exception {
    final var workflowId = "workflow-1";
    final var nodeId = "node-1";
    final var jsonPayload = "{\"invalid\"}";
    when(mockMapper.readValue(eq(jsonPayload), any(TypeReference.class)))
        .thenThrow(new IllegalArgumentException("Invalid JSON"));
    setPrivateField("workflowId", workflowId);
    setPrivateField("nodeId", nodeId);
    setPrivateField("jsonPayload", jsonPayload);

    assertThatThrownBy(command::run).isInstanceOf(RuntimeException.class);
  }

  private void setPrivateField(String fieldName, Object value) {
    try {
      final var field = SendCommandCommand.class.getDeclaredField(fieldName);
      field.setAccessible(true);
      field.set(command, value);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new RuntimeException(e);
    }
  }
}
