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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.HashMap;
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
  void testPluginRegistrationValid() {
    PluginRegistration reg = new PluginRegistration("gradle", Map.of("key", "value"));
    Set<ConstraintViolation<PluginRegistration>> violations = validator.validate(reg);
    assertTrue(violations.isEmpty());
  }

  @Test
  void testPluginRegistrationInvalidKey() {
    Map<String, Object> config = new HashMap<>();
    config.put("", "value");
    PluginRegistration reg = new PluginRegistration("gradle", config);
    Set<ConstraintViolation<PluginRegistration>> violations = validator.validate(reg);
    assertFalse(violations.isEmpty());
    assertTrue(violations.stream().anyMatch(v -> v.getMessage().contains("key cannot be blank")));
  }

  @Test
  void testPluginRegistrationNullValue() {
    Map<String, Object> config = new HashMap<>();
    config.put("key", null);
    // Map.copyOf in constructor will throw NPE before validation
    assertThrows(NullPointerException.class, () -> new PluginRegistration("gradle", config));
  }

  @Test
  void testAppConfigDataValid() {
    AppConfigData data =
        new AppConfigData(
            "session-1",
            "/path",
            List.of(new PluginRegistration("gradle", Map.of("k", "v"))),
            List.of(new WorkflowConfig("test", null, null)));
    Set<ConstraintViolation<AppConfigData>> violations = validator.validate(data);
    assertTrue(violations.isEmpty());
  }

  @Test
  void testAppConfigDataInvalidSession() {
    AppConfigData data =
        new AppConfigData(
            "../session",
            "/path",
            List.of(new PluginRegistration("gradle", Map.of("k", "v"))),
            List.of(new WorkflowConfig("test", null, null)));
    Set<ConstraintViolation<AppConfigData>> violations = validator.validate(data);
    assertFalse(violations.isEmpty());
    assertTrue(
        violations.stream().anyMatch(v -> v.getMessage().contains("Invalid session ID format")));
  }

  @Test
  void testAppConfigDataBlankSession() {
    AppConfigData data =
        new AppConfigData(
            "",
            "/path",
            List.of(new PluginRegistration("gradle", Map.of("k", "v"))),
            List.of(new WorkflowConfig("test", null, null)));
    Set<ConstraintViolation<AppConfigData>> violations = validator.validate(data);
    assertFalse(violations.isEmpty());
    assertTrue(
        violations.stream().anyMatch(v -> v.getMessage().contains("Session ID is required")));
  }
}
