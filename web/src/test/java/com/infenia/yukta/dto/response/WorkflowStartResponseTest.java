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
package com.infenia.yukta.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WorkflowStartResponseTest {

  @Test
  void constructor_validInput_createsRecord() {
    // Given
    String executionId = "550e8400-e29b-41d4-a716-446655440000";

    // When
    WorkflowStartResponse response = new WorkflowStartResponse(executionId);

    // Then
    assertThat(response.executionId()).isEqualTo(executionId);
  }

  @Test
  void equals_sameValues_returnsTrue() {
    // Given
    WorkflowStartResponse response1 =
        new WorkflowStartResponse("550e8400-e29b-41d4-a716-446655440000");
    WorkflowStartResponse response2 =
        new WorkflowStartResponse("550e8400-e29b-41d4-a716-446655440000");

    // When-Then
    assertThat(response1).isEqualTo(response2);
  }

  @Test
  void equals_differentValues_returnsFalse() {
    // Given
    WorkflowStartResponse response1 =
        new WorkflowStartResponse("550e8400-e29b-41d4-a716-446655440000");
    WorkflowStartResponse response2 =
        new WorkflowStartResponse("550e8400-e29b-41d4-a716-446655440001");

    // When-Then
    assertThat(response1).isNotEqualTo(response2);
  }

  @Test
  void toString_contains_relevantFieldValues() {
    // Given
    String executionId = "550e8400-e29b-41d4-a716-446655440000";
    WorkflowStartResponse response = new WorkflowStartResponse(executionId);

    // When
    String actual = response.toString();

    // Then
    assertThat(actual).contains("WorkflowStartResponse").contains(executionId);
  }
}
