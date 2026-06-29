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
package com.infenia.yukta.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.model.session.SessionConfigData;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Tests for model validation. */
@NoArgsConstructor
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.LawOfDemeter"})
class ValidationTest {

  /** The validator instance. */
  private static Validator validator;

  @SuppressWarnings("PMD.UnnecessaryModifier")
  @BeforeAll
  static void setUp() {
    try (final ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
      validator = factory.getValidator();
    }
  }

  @Test
  void testWorkflowDefinitionValid() {
    final WorkflowDefinition.Node node = new WorkflowDefinition.Node("n1", "gradle", Map.of());
    final WorkflowDefinition def =
        new WorkflowDefinition("test-workflow", "desc", List.of(node), List.of());
    final Set<ConstraintViolation<WorkflowDefinition>> violations = validator.validate(def);
    assertThat(violations).isEmpty();
  }

  @Test
  void testSessionConfigDataValid() {
    final WorkflowDefinition def =
        new WorkflowDefinition(
            "test-workflow",
            "desc",
            List.of(new WorkflowDefinition.Node("n1", "gradle", Map.of())),
            List.of());
    final SessionConfigData data =
        new SessionConfigData(
            "session-1", "desc", "initiator-1", Map.of(), "/path", Map.of("w1", def));
    final Set<ConstraintViolation<SessionConfigData>> violations = validator.validate(data);
    assertThat(violations).isEmpty();
  }

  @Test
  void testSessionConfigDataInvalidSession() {
    final WorkflowDefinition def =
        new WorkflowDefinition("test-workflow", "desc", List.of(), List.of());
    final SessionConfigData data =
        new SessionConfigData(
            "../session", "desc", "initiator-1", Map.of(), "/path", Map.of("w1", def));
    final Set<ConstraintViolation<SessionConfigData>> violations = validator.validate(data);
    assertThat(violations)
        .isNotEmpty()
        .anySatisfy(v -> assertThat(v.getMessage()).contains("Invalid session ID format"));
  }

  @Test
  void testSessionConfigDataDescriptionTooLong() {
    final WorkflowDefinition def =
        new WorkflowDefinition("test-workflow", "desc", List.of(), List.of());
    final String longDesc = "a".repeat(257);
    final SessionConfigData data =
        new SessionConfigData(
            "session-1", longDesc, "initiator-1", Map.of(), "/path", Map.of("w1", def));
    final Set<ConstraintViolation<SessionConfigData>> violations = validator.validate(data);
    assertThat(violations)
        .isNotEmpty()
        .anySatisfy(
            v ->
                assertThat(v.getMessage())
                    .contains("Session description must be at most 256 characters"));
  }
}
