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
