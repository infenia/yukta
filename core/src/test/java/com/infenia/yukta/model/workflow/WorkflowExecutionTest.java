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
package com.infenia.yukta.model.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.model.session.TaskResponse;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class WorkflowExecutionTest {

  @Test
  void construction_validExecutionIdAndResult_createsRecord() {
    // Given
    String executionId = "exec-123";
    Mono<TaskResponse> result = Mono.just(new TaskResponse("SUCCESS", "Completed"));

    // When
    WorkflowExecution execution = new WorkflowExecution(executionId, result);

    // Then
    assertThat(execution.executionId()).isEqualTo(executionId);
    assertThat(execution.result()).isSameAs(result);
  }

  @Test
  void executionId_afterConstruction_returnsCorrectValue() {
    // Given
    String executionId = "exec-123";
    Mono<TaskResponse> result = Mono.just(new TaskResponse("SUCCESS", "Completed"));
    WorkflowExecution execution = new WorkflowExecution(executionId, result);

    // When
    String resultId = execution.executionId();

    // Then
    assertThat(resultId).isEqualTo(executionId);
  }

  @Test
  void result_afterConstruction_returnsCorrectMono() {
    // Given
    String executionId = "exec-123";
    Mono<TaskResponse> result = Mono.just(new TaskResponse("SUCCESS", "Completed"));
    WorkflowExecution execution = new WorkflowExecution(executionId, result);

    // When
    Mono<TaskResponse> resultMono = execution.result();

    // Then
    assertThat(resultMono).isSameAs(result);
  }

  @Test
  void equals_twoInstancesWithSameValues_returnsTrue() {
    // Given
    String executionId = "exec-123";
    Mono<TaskResponse> result = Mono.just(new TaskResponse("SUCCESS", "Completed"));
    WorkflowExecution execution1 = new WorkflowExecution(executionId, result);
    WorkflowExecution execution2 = new WorkflowExecution(executionId, result);

    // When & Then
    assertThat(execution1).isEqualTo(execution2);
  }

  @Test
  void equals_twoInstancesWithDifferentExecutionId_returnsFalse() {
    // Given
    Mono<TaskResponse> result = Mono.just(new TaskResponse("SUCCESS", "Completed"));
    WorkflowExecution execution1 = new WorkflowExecution("exec-1", result);
    WorkflowExecution execution2 = new WorkflowExecution("exec-2", result);

    // When & Then
    assertThat(execution1).isNotEqualTo(execution2);
  }

  @Test
  void equals_twoInstancesWithDifferentResult_returnsFalse() {
    // Given
    String executionId = "exec-123";
    Mono<TaskResponse> result1 = Mono.just(new TaskResponse("SUCCESS", "Completed"));
    Mono<TaskResponse> result2 = Mono.just(new TaskResponse("FAILURE", "Failed"));
    WorkflowExecution execution1 = new WorkflowExecution(executionId, result1);
    WorkflowExecution execution2 = new WorkflowExecution(executionId, result2);

    // When & Then
    assertThat(execution1).isNotEqualTo(execution2);
  }

  @Test
  void equals_recordAndNull_returnsFalse() {
    // Given
    Mono<TaskResponse> result = Mono.just(new TaskResponse("SUCCESS", "Completed"));
    WorkflowExecution execution = new WorkflowExecution("exec-123", result);

    // When & Then
    assertThat(execution).isNotEqualTo(null);
  }

  @Test
  void equals_recordAndDifferentType_returnsFalse() {
    // Given
    Mono<TaskResponse> result = Mono.just(new TaskResponse("SUCCESS", "Completed"));
    WorkflowExecution execution = new WorkflowExecution("exec-123", result);
    String other = "not a workflow execution";

    // When & Then
    assertThat(execution).isNotEqualTo(other);
  }

  @Test
  void hashCode_twoEqualRecords_returnsSameHashCode() {
    // Given
    String executionId = "exec-123";
    Mono<TaskResponse> result = Mono.just(new TaskResponse("SUCCESS", "Completed"));
    WorkflowExecution execution1 = new WorkflowExecution(executionId, result);
    WorkflowExecution execution2 = new WorkflowExecution(executionId, result);

    // When & Then
    assertThat(execution1.hashCode()).isEqualTo(execution2.hashCode());
  }

  @Test
  void hashCode_differentRecords_canReturnDifferentHashCodes() {
    // Given
    Mono<TaskResponse> result1 = Mono.just(new TaskResponse("SUCCESS", "Completed"));
    Mono<TaskResponse> result2 = Mono.just(new TaskResponse("FAILURE", "Failed"));
    WorkflowExecution execution1 = new WorkflowExecution("exec-1", result1);
    WorkflowExecution execution2 = new WorkflowExecution("exec-2", result2);

    // When & Then
    assertThat(execution1.hashCode()).isNotEqualTo(execution2.hashCode());
  }

  @Test
  void toString_recordCreated_includesExecutionIdAndResult() {
    // Given
    String executionId = "exec-456";
    Mono<TaskResponse> result = Mono.just(new TaskResponse("SUCCESS", "Completed"));
    WorkflowExecution execution = new WorkflowExecution(executionId, result);

    // When
    String resultString = execution.toString();

    // Then
    assertThat(resultString).contains("exec-456");
  }

  @Test
  void construction_withNullExecutionId_createsRecord() {
    // Given
    Mono<TaskResponse> result = Mono.just(new TaskResponse("SUCCESS", "Completed"));

    // When
    WorkflowExecution execution = new WorkflowExecution(null, result);

    // Then
    assertThat(execution.executionId()).isNull();
    assertThat(execution.result()).isSameAs(result);
  }

  @Test
  void construction_withNullResult_createsRecord() {
    // Given
    String executionId = "exec-123";

    // When
    WorkflowExecution execution = new WorkflowExecution(executionId, null);

    // Then
    assertThat(execution.executionId()).isEqualTo(executionId);
    assertThat(execution.result()).isNull();
  }
}
