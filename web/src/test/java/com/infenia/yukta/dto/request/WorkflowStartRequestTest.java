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
package com.infenia.yukta.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

@SpringJUnitConfig(WorkflowStartRequestTest.TestConfig.class)
class WorkflowStartRequestTest {

  @Configuration
  static class TestConfig {
    @Bean
    LocalValidatorFactoryBean validator() {
      return new LocalValidatorFactoryBean();
    }
  }

  @Autowired private Validator validator;

  @Test
  void constructor_validInputs_createsRecord() {
    // Given
    String sessionId = "session-1";
    String workflowId = "workflow-1";

    // When
    WorkflowStartRequest request = new WorkflowStartRequest(sessionId, workflowId);

    // Then
    assertThat(request.sessionId()).isEqualTo(sessionId);
    assertThat(request.workflowId()).isEqualTo(workflowId);
  }

  @Test
  void equals_sameValues_returnsTrue() {
    // Given
    WorkflowStartRequest req1 = new WorkflowStartRequest("session-1", "workflow-1");
    WorkflowStartRequest req2 = new WorkflowStartRequest("session-1", "workflow-1");

    // When-Then
    assertThat(req1).isEqualTo(req2);
    assertThat(req1.hashCode()).isEqualTo(req2.hashCode());
  }

  @Test
  void equals_differentSessionId_returnsFalse() {
    // Given
    WorkflowStartRequest req1 = new WorkflowStartRequest("session-1", "workflow-1");
    WorkflowStartRequest req2 = new WorkflowStartRequest("session-2", "workflow-1");

    // When-Then
    assertThat(req1).isNotEqualTo(req2);
  }

  @Test
  void equals_differentWorkflowId_returnsFalse() {
    // Given
    WorkflowStartRequest req1 = new WorkflowStartRequest("session-1", "workflow-1");
    WorkflowStartRequest req2 = new WorkflowStartRequest("session-1", "workflow-2");

    // When-Then
    assertThat(req1).isNotEqualTo(req2);
  }

  @Test
  void toString_containsAllFields() {
    // Given
    WorkflowStartRequest request = new WorkflowStartRequest("session-1", "workflow-1");

    // When
    String actual = request.toString();

    // Then
    assertThat(actual)
        .contains("WorkflowStartRequest")
        .contains("session-1")
        .contains("workflow-1");
  }

  @Test
  void validation_blankSessionId_fails() {
    // Given
    WorkflowStartRequest request = new WorkflowStartRequest("", "workflow-1");

    // When
    Set<ConstraintViolation<WorkflowStartRequest>> violations = validator.validate(request);

    // Then
    assertThat(violations).isNotEmpty();
    assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("sessionId"));
  }

  @Test
  void validation_nullSessionId_fails() {
    // Given
    WorkflowStartRequest request = new WorkflowStartRequest(null, "workflow-1");

    // When
    Set<ConstraintViolation<WorkflowStartRequest>> violations = validator.validate(request);

    // Then
    assertThat(violations).isNotEmpty();
  }

  @Test
  void validation_invalidSessionIdWithPathTraversal_fails() {
    // Given - session ID with path traversal
    WorkflowStartRequest request = new WorkflowStartRequest("../invalid", "workflow-1");

    // When
    Set<ConstraintViolation<WorkflowStartRequest>> violations = validator.validate(request);

    // Then
    assertThat(violations).isNotEmpty();
    assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("sessionId"));
  }

  @Test
  void validation_validSessionId_passes() {
    // Given
    WorkflowStartRequest request = new WorkflowStartRequest("session-123", "workflow-1");

    // When
    Set<ConstraintViolation<WorkflowStartRequest>> violations = validator.validate(request);

    // Then
    assertThat(violations).isEmpty();
  }

  @Test
  void validation_blankWorkflowId_fails() {
    // Given
    WorkflowStartRequest request = new WorkflowStartRequest("session-1", "");

    // When
    Set<ConstraintViolation<WorkflowStartRequest>> violations = validator.validate(request);

    // Then
    assertThat(violations).isNotEmpty();
    assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("workflowId"));
  }

  @Test
  void validation_nullWorkflowId_fails() {
    // Given
    WorkflowStartRequest request = new WorkflowStartRequest("session-1", null);

    // When
    Set<ConstraintViolation<WorkflowStartRequest>> violations = validator.validate(request);

    // Then
    assertThat(violations).isNotEmpty();
  }

  @Test
  void validation_validWorkflowId_passes() {
    // Given
    WorkflowStartRequest request = new WorkflowStartRequest("session-1", "quality-check");

    // When
    Set<ConstraintViolation<WorkflowStartRequest>> violations = validator.validate(request);

    // Then
    assertThat(violations).isEmpty();
  }

  @Test
  void validation_validInputs_passes() {
    // Given
    WorkflowStartRequest request = new WorkflowStartRequest("session-123", "workflow-abc");

    // When
    Set<ConstraintViolation<WorkflowStartRequest>> violations = validator.validate(request);

    // Then
    assertThat(violations).isEmpty();
  }
}
