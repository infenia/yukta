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
package com.infenia.yukta.model.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.model.control.ControlCommand.CommandType;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ControlCommandTest {

  @Test
  void fullConstructor_allParametersProvided_createsInstanceWithAllFields() {
    // Given
    CommandType expectedType = CommandType.RESTART_FROM_NODE;
    String expectedWorkflowId = "workflow-123";
    String expectedSessionId = "session-456";
    String expectedTargetNodeId = "node-789";
    Map<String, Object> expectedParams = Map.of("retryCount", 3, "timeout", 5000);

    // When
    ControlCommand command =
        new ControlCommand(
            expectedType,
            expectedWorkflowId,
            expectedSessionId,
            expectedTargetNodeId,
            expectedParams);

    // Then
    assertThat(command.type()).isEqualTo(expectedType);
    assertThat(command.workflowId()).isEqualTo(expectedWorkflowId);
    assertThat(command.sessionId()).isEqualTo(expectedSessionId);
    assertThat(command.targetNodeId()).isEqualTo(expectedTargetNodeId);
    assertThat(command.params()).isEqualTo(expectedParams);
  }

  @Test
  void fullConstructor_nullTargetNodeId_createsInstanceWithNullNode() {
    // Given
    CommandType expectedType = CommandType.STOP;
    String expectedWorkflowId = "workflow-123";
    String expectedSessionId = "session-456";
    Map<String, Object> expectedParams = Map.of("force", true);

    // When
    ControlCommand command =
        new ControlCommand(
            expectedType, expectedWorkflowId, expectedSessionId, null, expectedParams);

    // Then
    assertThat(command.type()).isEqualTo(expectedType);
    assertThat(command.workflowId()).isEqualTo(expectedWorkflowId);
    assertThat(command.sessionId()).isEqualTo(expectedSessionId);
    assertThat(command.targetNodeId()).isNull();
    assertThat(command.params()).isEqualTo(expectedParams);
  }

  @Test
  void fullConstructor_emptyParamsMap_copiesMapImmutably() {
    // Given
    CommandType expectedType = CommandType.RESTART;
    String expectedWorkflowId = "workflow-123";
    String expectedSessionId = "session-456";
    Map<String, Object> emptyParams = Map.of();

    // When
    ControlCommand command =
        new ControlCommand(expectedType, expectedWorkflowId, expectedSessionId, null, emptyParams);

    // Then
    assertThat(command.params()).isEmpty();
    assertThat(command.params()).isUnmodifiable();
  }

  @Test
  void fullConstructor_paramsMapWithData_makesImmutable() {
    // Given
    Map<String, Object> mutableParams = new HashMap<>();
    mutableParams.put("key1", "value1");
    mutableParams.put("key2", 42);

    // When
    ControlCommand command =
        new ControlCommand(CommandType.RESTART, "wf-1", "sess-1", null, mutableParams);

    // Then
    assertThat(command.params()).containsEntry("key1", "value1").containsEntry("key2", 42);
    assertThatThrownBy(() -> command.params().put("key3", "value3"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void convenienceConstructor_threeParameters_createsWithDefaultsNoParamsNullNode() {
    // Given
    CommandType expectedType = CommandType.STOP;
    String expectedWorkflowId = "workflow-123";
    String expectedSessionId = "session-456";

    // When
    ControlCommand command =
        new ControlCommand(expectedType, expectedWorkflowId, expectedSessionId);

    // Then
    assertThat(command.type()).isEqualTo(expectedType);
    assertThat(command.workflowId()).isEqualTo(expectedWorkflowId);
    assertThat(command.sessionId()).isEqualTo(expectedSessionId);
    assertThat(command.targetNodeId()).isNull();
    assertThat(command.params()).isEmpty();
  }

  @Test
  void convenienceConstructor_fourParameters_createsWithDefaultsNoParams() {
    // Given
    CommandType expectedType = CommandType.RESTART_FROM_NODE;
    String expectedWorkflowId = "workflow-abc";
    String expectedSessionId = "session-xyz";
    String expectedTargetNodeId = "node-target";

    // When
    ControlCommand command =
        new ControlCommand(
            expectedType, expectedWorkflowId, expectedSessionId, expectedTargetNodeId);

    // Then
    assertThat(command.type()).isEqualTo(expectedType);
    assertThat(command.workflowId()).isEqualTo(expectedWorkflowId);
    assertThat(command.sessionId()).isEqualTo(expectedSessionId);
    assertThat(command.targetNodeId()).isEqualTo(expectedTargetNodeId);
    assertThat(command.params()).isEmpty();
  }

  @Test
  void record_sameFieldValues_areEqual() {
    // Given
    CommandType type = CommandType.RESTART;
    String workflowId = "wf-123";
    String sessionId = "sess-123";
    String targetNodeId = "node-456";
    Map<String, Object> params = Map.of("key", "value");

    // When
    ControlCommand command1 = new ControlCommand(type, workflowId, sessionId, targetNodeId, params);
    ControlCommand command2 = new ControlCommand(type, workflowId, sessionId, targetNodeId, params);

    // Then
    assertThat(command1).isEqualTo(command2);
  }

  @Test
  void record_differentType_areNotEqual() {
    // Given
    ControlCommand command1 =
        new ControlCommand(CommandType.STOP, "wf-1", "sess-1", null, Map.of());
    ControlCommand command2 =
        new ControlCommand(CommandType.RESTART, "wf-1", "sess-1", null, Map.of());

    // Then
    assertThat(command1).isNotEqualTo(command2);
  }

  @Test
  void record_differentWorkflowId_areNotEqual() {
    // Given
    ControlCommand command1 =
        new ControlCommand(CommandType.STOP, "wf-1", "sess-1", null, Map.of());
    ControlCommand command2 =
        new ControlCommand(CommandType.STOP, "wf-2", "sess-1", null, Map.of());

    // Then
    assertThat(command1).isNotEqualTo(command2);
  }

  @Test
  void record_differentSessionId_areNotEqual() {
    // Given
    ControlCommand command1 =
        new ControlCommand(CommandType.STOP, "wf-1", "sess-1", null, Map.of());
    ControlCommand command2 =
        new ControlCommand(CommandType.STOP, "wf-1", "sess-2", null, Map.of());

    // Then
    assertThat(command1).isNotEqualTo(command2);
  }

  @Test
  void record_differentTargetNodeId_areNotEqual() {
    // Given
    ControlCommand command1 =
        new ControlCommand(CommandType.RESTART_FROM_NODE, "wf-1", "sess-1", "node-1", Map.of());
    ControlCommand command2 =
        new ControlCommand(CommandType.RESTART_FROM_NODE, "wf-1", "sess-1", "node-2", Map.of());

    // Then
    assertThat(command1).isNotEqualTo(command2);
  }

  @Test
  void record_differentParams_areNotEqual() {
    // Given
    ControlCommand command1 =
        new ControlCommand(CommandType.RESTART, "wf-1", "sess-1", null, Map.of("key", "value1"));
    ControlCommand command2 =
        new ControlCommand(CommandType.RESTART, "wf-1", "sess-1", null, Map.of("key", "value2"));

    // Then
    assertThat(command1).isNotEqualTo(command2);
  }

  @Test
  void record_sameValues_haveSameHashCode() {
    // Given
    CommandType type = CommandType.STOP;
    String workflowId = "wf-123";
    String sessionId = "sess-123";
    String targetNodeId = "node-456";
    Map<String, Object> params = Map.of("key", "value");

    // When
    ControlCommand command1 = new ControlCommand(type, workflowId, sessionId, targetNodeId, params);
    ControlCommand command2 = new ControlCommand(type, workflowId, sessionId, targetNodeId, params);

    // Then
    assertThat(command1.hashCode()).isEqualTo(command2.hashCode());
  }

  @Test
  void record_differentValues_likelyDifferentHashCode() {
    // Given
    ControlCommand command1 =
        new ControlCommand(CommandType.STOP, "wf-1", "sess-1", "node-1", Map.of("a", 1));
    ControlCommand command2 =
        new ControlCommand(CommandType.RESTART, "wf-2", "sess-2", "node-2", Map.of("b", 2));

    // Then
    assertThat(command1.hashCode()).isNotEqualTo(command2.hashCode());
  }

  @Test
  void record_accessor_typeReturnsCorrectValue() {
    // Given
    CommandType expectedType = CommandType.RESTART_FROM_NODE;

    // When
    ControlCommand command = new ControlCommand(expectedType, "wf-1", "sess-1");

    // Then
    assertThat(command.type()).isEqualTo(expectedType);
  }

  @Test
  void record_accessor_workflowIdReturnsCorrectValue() {
    // Given
    String expectedWorkflowId = "workflow-abc-123";

    // When
    ControlCommand command = new ControlCommand(CommandType.STOP, expectedWorkflowId, "sess-1");

    // Then
    assertThat(command.workflowId()).isEqualTo(expectedWorkflowId);
  }

  @Test
  void record_accessor_sessionIdReturnsCorrectValue() {
    // Given
    String expectedSessionId = "session-xyz-789";

    // When
    ControlCommand command = new ControlCommand(CommandType.RESTART, "wf-1", expectedSessionId);

    // Then
    assertThat(command.sessionId()).isEqualTo(expectedSessionId);
  }

  @Test
  void record_accessor_targetNodeIdReturnsCorrectValue() {
    // Given
    String expectedTargetNodeId = "target-node-456";

    // When
    ControlCommand command =
        new ControlCommand(CommandType.RESTART_FROM_NODE, "wf-1", "sess-1", expectedTargetNodeId);

    // Then
    assertThat(command.targetNodeId()).isEqualTo(expectedTargetNodeId);
  }

  @Test
  void record_accessor_paramsReturnsCorrectMap() {
    // Given
    Map<String, Object> expectedParams = Map.of("retry", 3, "delay", 1000);

    // When
    ControlCommand command =
        new ControlCommand(CommandType.RESTART, "wf-1", "sess-1", null, expectedParams);

    // Then
    assertThat(command.params()).isEqualTo(expectedParams);
  }

  @Test
  void toString_containsAllFieldValues() {
    // Given
    CommandType type = CommandType.STOP;
    String workflowId = "wf-123";
    String sessionId = "sess-123";

    // When
    ControlCommand command = new ControlCommand(type, workflowId, sessionId);
    String toStringOutput = command.toString();

    // Then
    assertThat(toStringOutput).contains("STOP").contains("wf-123").contains("sess-123");
  }

  @Test
  void record_nullTargetNodeId_andNullComparison_areNotEqual() {
    // Given
    ControlCommand command = new ControlCommand(CommandType.STOP, "wf-1", "sess-1");

    // Then
    assertThat(command).isNotEqualTo(null);
  }

  @Test
  void equals_withDifferentType_returnsFalse() {
    // Given
    ControlCommand command = new ControlCommand(CommandType.RESTART, "wf-1", "sess-1");
    String differentObject = "not a ControlCommand";

    // Then
    assertThat(command).isNotEqualTo(differentObject);
  }

  @Test
  void convenienceConstructorThreeParams_allCommandTypes_createsSuccessfully() {
    // Given
    CommandType[] allTypes = {CommandType.STOP, CommandType.RESTART, CommandType.RESTART_FROM_NODE};

    // When-Then
    for (CommandType type : allTypes) {
      ControlCommand command = new ControlCommand(type, "wf-1", "sess-1");
      assertThat(command.type()).isEqualTo(type);
      assertThat(command.workflowId()).isEqualTo("wf-1");
      assertThat(command.sessionId()).isEqualTo("sess-1");
    }
  }

  @Test
  void paramsImmutability_originalMapModified_recordParamsUnchanged() {
    // Given
    Map<String, Object> mutableParams = new HashMap<>();
    mutableParams.put("key1", "value1");
    mutableParams.put("key2", "value2");

    // When
    ControlCommand command =
        new ControlCommand(CommandType.RESTART, "wf-1", "sess-1", null, mutableParams);

    // Modify original map after creating the record
    mutableParams.put("key3", "value3");
    mutableParams.remove("key1");

    // Then - record params should be unchanged
    assertThat(command.params())
        .containsEntry("key1", "value1")
        .containsEntry("key2", "value2")
        .doesNotContainKey("key3")
        .hasSize(2);
  }
}
