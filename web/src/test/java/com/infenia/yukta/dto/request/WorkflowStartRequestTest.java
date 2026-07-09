// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Set;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/** Tests for WorkflowStartRequest. */
@SpringJUnitConfig(WorkflowStartRequestTest.TestConfig.class)
@NoArgsConstructor
@SuppressWarnings("PMD.TooManyMethods")
class WorkflowStartRequestTest {

  /** Session ID constant for testing. */
  private static final String SESSION_ID_CONST = "session-1";

  /** Workflow ID constant for testing. */
  private static final String WORKFLOW_ID_CONST = "workflow-1";

  /** Validator for testing constraint violations. */
  @Autowired private Validator validator;

  /** Test configuration for validator bean. */
  @Configuration
  @SuppressWarnings("PMD.TestClassWithoutTestCases")
  /* default */ static class TestConfig {
    /**
     * Creates a LocalValidatorFactoryBean for validation testing.
     *
     * @return validator factory bean
     */
    @Bean
    /* default */ LocalValidatorFactoryBean validator() {
      return new LocalValidatorFactoryBean();
    }
  }

  @Test
  void constructor_validInputs_createsRecord() {
    // Given
    final String sessionId = SESSION_ID_CONST;
    final String workflowId = WORKFLOW_ID_CONST;

    // When
    final WorkflowStartRequest request = new WorkflowStartRequest(sessionId, workflowId);

    // Then
    assertThat(request.sessionId()).isEqualTo(sessionId);
    assertThat(request.workflowId()).isEqualTo(workflowId);
  }

  @Test
  void equals_sameValues_returnsTrue() {
    // Given
    final WorkflowStartRequest req1 = new WorkflowStartRequest(SESSION_ID_CONST, WORKFLOW_ID_CONST);
    final WorkflowStartRequest req2 = new WorkflowStartRequest(SESSION_ID_CONST, WORKFLOW_ID_CONST);

    // When-Then
    assertThat(req1).isEqualTo(req2);
    assertThat(req1.hashCode()).isEqualTo(req2.hashCode());
  }

  @Test
  void equals_differentSessionId_returnsFalse() {
    // Given
    final WorkflowStartRequest req1 = new WorkflowStartRequest(SESSION_ID_CONST, WORKFLOW_ID_CONST);
    final WorkflowStartRequest req2 = new WorkflowStartRequest("session-2", WORKFLOW_ID_CONST);

    // When-Then
    assertThat(req1).isNotEqualTo(req2);
  }

  @Test
  void equals_differentWorkflowId_returnsFalse() {
    // Given
    final WorkflowStartRequest req1 = new WorkflowStartRequest(SESSION_ID_CONST, WORKFLOW_ID_CONST);
    final WorkflowStartRequest req2 = new WorkflowStartRequest(SESSION_ID_CONST, "workflow-2");

    // When-Then
    assertThat(req1).isNotEqualTo(req2);
  }

  @Test
  void verifyToStringContainsAllFields() {
    // Given
    final WorkflowStartRequest request =
        new WorkflowStartRequest(SESSION_ID_CONST, WORKFLOW_ID_CONST);

    // When
    final String actual = request.toString();

    // Then
    assertThat(actual)
        .contains("WorkflowStartRequest")
        .contains(SESSION_ID_CONST)
        .contains(WORKFLOW_ID_CONST);
  }

  @Test
  void validation_blankSessionId_fails() {
    // Given
    final WorkflowStartRequest request = new WorkflowStartRequest("", WORKFLOW_ID_CONST);

    // When
    final Set<ConstraintViolation<WorkflowStartRequest>> violations = validator.validate(request);

    // Then
    assertThat(violations).isNotEmpty();
    assertThat(violations).anyMatch(v -> "sessionId".equals(v.getPropertyPath().toString()));
  }

  @Test
  void validation_nullSessionId_fails() {
    // Given
    final WorkflowStartRequest request = new WorkflowStartRequest(null, WORKFLOW_ID_CONST);

    // When
    final Set<ConstraintViolation<WorkflowStartRequest>> violations = validator.validate(request);

    // Then
    assertThat(violations).isNotEmpty();
  }

  @Test
  void validation_invalidSessionIdWithPathTraversal_fails() {
    // Given - session ID with path traversal
    final WorkflowStartRequest request = new WorkflowStartRequest("../invalid", WORKFLOW_ID_CONST);

    // When
    final Set<ConstraintViolation<WorkflowStartRequest>> violations = validator.validate(request);

    // Then
    assertThat(violations).isNotEmpty();
    assertThat(violations).anyMatch(v -> "sessionId".equals(v.getPropertyPath().toString()));
  }

  @Test
  void validation_validSessionId_passes() {
    // Given
    final WorkflowStartRequest request = new WorkflowStartRequest("session-123", WORKFLOW_ID_CONST);

    // When
    final Set<ConstraintViolation<WorkflowStartRequest>> violations = validator.validate(request);

    // Then
    assertThat(violations).isEmpty();
  }

  @Test
  void validation_blankWorkflowId_fails() {
    // Given
    final WorkflowStartRequest request = new WorkflowStartRequest(SESSION_ID_CONST, "");

    // When
    final Set<ConstraintViolation<WorkflowStartRequest>> violations = validator.validate(request);

    // Then
    assertThat(violations).isNotEmpty();
    assertThat(violations).anyMatch(v -> "workflowId".equals(v.getPropertyPath().toString()));
  }

  @Test
  void validation_nullWorkflowId_fails() {
    // Given
    final WorkflowStartRequest request = new WorkflowStartRequest(SESSION_ID_CONST, null);

    // When
    final Set<ConstraintViolation<WorkflowStartRequest>> violations = validator.validate(request);

    // Then
    assertThat(violations).isNotEmpty();
  }

  @Test
  void validation_validWorkflowId_passes() {
    // Given
    final WorkflowStartRequest request =
        new WorkflowStartRequest(SESSION_ID_CONST, "quality-check");

    // When
    final Set<ConstraintViolation<WorkflowStartRequest>> violations = validator.validate(request);

    // Then
    assertThat(violations).isEmpty();
  }

  @Test
  void validation_validInputs_passes() {
    // Given
    final WorkflowStartRequest request = new WorkflowStartRequest("session-123", "workflow-abc");

    // When
    final Set<ConstraintViolation<WorkflowStartRequest>> violations = validator.validate(request);

    // Then
    assertThat(violations).isEmpty();
  }
}
