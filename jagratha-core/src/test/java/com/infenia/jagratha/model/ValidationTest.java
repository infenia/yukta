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
package com.infenia.jagratha.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ValidationTest {

  private static Validator validator;

  @BeforeAll
  static void setUp() {
    ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
    validator = factory.getValidator();
  }

  @Test
  void testWorkflowDefinitionValid() {
    WorkflowDefinition.Node node = new WorkflowDefinition.Node("n1", "gradle", Map.of());
    WorkflowDefinition def = new WorkflowDefinition(List.of(node), List.of());
    Set<ConstraintViolation<WorkflowDefinition>> violations = validator.validate(def);
    assertTrue(violations.isEmpty());
  }

  @Test
  void testAppConfigDataValid() {
    WorkflowDefinition def =
        new WorkflowDefinition(
            List.of(new WorkflowDefinition.Node("n1", "gradle", Map.of())), List.of());
    AppConfigData data = new AppConfigData("session-1", "initiator-1", Map.of(), "/path", def);
    Set<ConstraintViolation<AppConfigData>> violations = validator.validate(data);
    assertTrue(violations.isEmpty());
  }

  @Test
  void testAppConfigDataInvalidSession() {
    WorkflowDefinition def = new WorkflowDefinition(List.of(), List.of());
    AppConfigData data = new AppConfigData("../session", "initiator-1", Map.of(), "/path", def);
    Set<ConstraintViolation<AppConfigData>> violations = validator.validate(data);
    assertFalse(violations.isEmpty());
    assertTrue(
        violations.stream().anyMatch(v -> v.getMessage().contains("Invalid session ID format")));
  }
}
