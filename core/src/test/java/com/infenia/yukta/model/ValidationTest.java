// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.model.session.SessionConfigData;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.testing.Fixtures;
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
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
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
    final WorkflowDefinition.Node node = Fixtures.node("n1", "gradle", Map.of());
    final WorkflowDefinition def =
        Fixtures.workflow("test-workflow", "desc", List.of(node), List.of());
    final Set<ConstraintViolation<WorkflowDefinition>> violations = validator.validate(def);
    assertThat(violations).isEmpty();
  }

  @Test
  void testSessionConfigDataValid() {
    final WorkflowDefinition def =
        Fixtures.workflow(
            "test-workflow", "desc", List.of(Fixtures.node("n1", "gradle", Map.of())), List.of());
    final SessionConfigData data =
        new SessionConfigData(
            "session-1",
            "test-session",
            "desc",
            "initiator-1",
            Map.of(),
            "/path",
            Map.of("w1", def));
    final Set<ConstraintViolation<SessionConfigData>> violations = validator.validate(data);
    assertThat(violations).isEmpty();
  }

  @Test
  void testSessionConfigDataInvalidSession() {
    final WorkflowDefinition def = Fixtures.workflow("test-workflow", "desc", List.of(), List.of());
    final SessionConfigData data =
        new SessionConfigData(
            "../session",
            "invalid-session",
            "desc",
            "initiator-1",
            Map.of(),
            "/path",
            Map.of("w1", def));
    final Set<ConstraintViolation<SessionConfigData>> violations = validator.validate(data);
    assertThat(violations)
        .isNotEmpty()
        .anySatisfy(v -> assertThat(v.getMessage()).contains("Invalid session ID format"));
  }

  @Test
  void testSessionConfigDataRejectsControlCharactersAndUnpairedSurrogates() {
    final WorkflowDefinition def = Fixtures.workflow("test-workflow", "desc", List.of(), List.of());
    final char highSurrogate = (char) 0xD800;
    final char lowSurrogate = (char) 0xDC00;
    final List<String> ids =
        List.of("a\0b", "a\nb", highSurrogate + "\0" + lowSurrogate, "lone" + lowSurrogate);
    for (final String id : ids) {
      assertThat(validator.validate(sessionConfigWithId(id, def)))
          .as("session id %s", id.codePoints().boxed().toList())
          .anySatisfy(v -> assertThat(v.getMessage()).contains("Invalid session ID format"));
    }
  }

  private static SessionConfigData sessionConfigWithId(
      final String id, final WorkflowDefinition def) {
    return new SessionConfigData(
        id, "name", "desc", "initiator-1", Map.of(), "/path", Map.of("w1", def));
  }

  @Test
  void testSessionConfigDataAcceptsUnicodeSessionId() {
    final WorkflowDefinition def = Fixtures.workflow("test-workflow", "desc", List.of(), List.of());
    final SessionConfigData data =
        new SessionConfigData(
            "séssion-😀", "name", "desc", "initiator-1", Map.of(), "/path", Map.of("w1", def));
    assertThat(validator.validate(data))
        .noneSatisfy(v -> assertThat(v.getMessage()).contains("Invalid session ID format"));
  }

  @Test
  void testSessionConfigDataDescriptionTooLong() {
    final WorkflowDefinition def = Fixtures.workflow("test-workflow", "desc", List.of(), List.of());
    final String longDesc = "a".repeat(257);
    final SessionConfigData data =
        new SessionConfigData(
            "session-1",
            "long-desc-session",
            longDesc,
            "initiator-1",
            Map.of(),
            "/path",
            Map.of("w1", def));
    final Set<ConstraintViolation<SessionConfigData>> violations = validator.validate(data);
    assertThat(violations)
        .isNotEmpty()
        .anySatisfy(v ->
            assertThat(v.getMessage())
                .contains("Session description must be at most 256 characters"));
  }
}
