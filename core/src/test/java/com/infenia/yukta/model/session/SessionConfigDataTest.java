// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.model.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.model.workflow.WorkflowDefinition;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/** Tests for {@link SessionConfigData}. */
@SuppressWarnings({
  "PMD.TooManyMethods",
  "PMD.AvoidDuplicateLiterals",
  "PMD.CommentDefaultAccessModifier",
  "PMD.TestClassWithoutTestCases",
  "PMD.FieldDeclarationsShouldBeAtStartOfClass"
})
@SpringJUnitConfig(SessionConfigDataTest.TestConfig.class)
@Tag("SessionConfigDataTest")
@NoArgsConstructor
class SessionConfigDataTest {

  /** Test configuration for validation. */
  @Configuration
  static class TestConfig {
    /**
     * Creates a validator bean.
     *
     * @return the validator
     */
    @Bean
    LocalValidatorFactoryBean validator() {
      return new LocalValidatorFactoryBean();
    }
  }

  /** The validator instance. */
  @Autowired private Validator validator;

  @Test
  void testSessionConfigData() {
    final SessionConfigData data = new SessionConfigData("s", "d", "i", null, "/p", null);
    assertThat(data.sessionId()).isEqualTo("s");
    assertThat(data.tags()).isNotNull().isEmpty();
    assertThat(data.workflows()).isNotNull().isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"session-123", "test-session", "prod-workflow-session"})
  void validationShouldNotFailWithValidSessionId(final String sessionId) {
    final var workflows =
        Map.of(
            "wf1",
            new WorkflowDefinition(
                "wf1", "desc", List.of(new WorkflowDefinition.Node("n1", "t1", null)), null));
    final var data =
        new SessionConfigData(sessionId, "desc", "initiator", null, "/path", workflows);
    final var result = validator.validateProperty(data, "sessionId");
    assertThat(result).isEmpty();
  }

  @ParameterizedTest
  @NullSource
  @EmptySource
  @ValueSource(strings = {" ", "\t", "\n"})
  void validationShouldFailWithInvalidSessionId(final String sessionId) {
    final var workflows =
        Map.of(
            "wf1",
            new WorkflowDefinition(
                "wf1", "desc", List.of(new WorkflowDefinition.Node("n1", "t1", null)), null));
    final var data =
        new SessionConfigData(sessionId, "desc", "initiator", null, "/path", workflows);
    final var result = validator.validateProperty(data, "sessionId");
    assertThat(result).isNotEmpty();
  }

  @Test
  void validationShouldFailWithSessionIdContainingPathTraversal() {
    final var workflows =
        Map.of(
            "wf1",
            new WorkflowDefinition(
                "wf1", "desc", List.of(new WorkflowDefinition.Node("n1", "t1", null)), null));
    final var data =
        new SessionConfigData("../session", "desc", "initiator", null, "/path", workflows);
    final var result = validator.validateProperty(data, "sessionId");
    assertThat(result).isNotEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"d", "description", "This is a session description"})
  void validationShouldNotFailWithValidDescription(final String description) {
    final var workflows =
        Map.of(
            "wf1",
            new WorkflowDefinition(
                "wf1", "desc", List.of(new WorkflowDefinition.Node("n1", "t1", null)), null));
    final var data =
        new SessionConfigData("session-1", description, "initiator", null, "/path", workflows);
    final var result = validator.validateProperty(data, "description");
    assertThat(result).isEmpty();
  }

  @ParameterizedTest
  @NullSource
  @EmptySource
  @ValueSource(strings = {" ", "\t", "\n"})
  void validationShouldFailWithInvalidDescription(final String description) {
    final var workflows =
        Map.of(
            "wf1",
            new WorkflowDefinition(
                "wf1", "desc", List.of(new WorkflowDefinition.Node("n1", "t1", null)), null));
    final var data =
        new SessionConfigData("session-1", description, "initiator", null, "/path", workflows);
    final var result = validator.validateProperty(data, "description");
    assertThat(result).isNotEmpty();
  }

  @Test
  void validationShouldFailWhenDescriptionExceedsMaxLength() {
    final var workflows =
        Map.of(
            "wf1",
            new WorkflowDefinition(
                "wf1", "desc", List.of(new WorkflowDefinition.Node("n1", "t1", null)), null));
    final var tooLongDescription = "a".repeat(257);
    final var data =
        new SessionConfigData(
            "session-1", tooLongDescription, "initiator", null, "/path", workflows);
    final var result = validator.validateProperty(data, "description");
    assertThat(result).isNotEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"initiator", "AI Agent", "test-runner"})
  void validationShouldNotFailWithValidInitiator(final String initiator) {
    final var workflows =
        Map.of(
            "wf1",
            new WorkflowDefinition(
                "wf1", "desc", List.of(new WorkflowDefinition.Node("n1", "t1", null)), null));
    final var data =
        new SessionConfigData("session-1", "desc", initiator, null, "/path", workflows);
    final var result = validator.validateProperty(data, "initiator");
    assertThat(result).isEmpty();
  }

  @ParameterizedTest
  @NullSource
  @EmptySource
  @ValueSource(strings = {" ", "\t", "\n"})
  void validationShouldFailWithInvalidInitiator(final String initiator) {
    final var workflows =
        Map.of(
            "wf1",
            new WorkflowDefinition(
                "wf1", "desc", List.of(new WorkflowDefinition.Node("n1", "t1", null)), null));
    final var data =
        new SessionConfigData("session-1", "desc", initiator, null, "/path", workflows);
    final var result = validator.validateProperty(data, "initiator");
    assertThat(result).isNotEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"/path/to/project", "/home/user/workspace", "/projects/my-app"})
  void validationShouldNotFailWithValidProjectPath(final String projectPath) {
    final var workflows =
        Map.of(
            "wf1",
            new WorkflowDefinition(
                "wf1", "desc", List.of(new WorkflowDefinition.Node("n1", "t1", null)), null));
    final var data =
        new SessionConfigData("session-1", "desc", "initiator", null, projectPath, workflows);
    final var result = validator.validateProperty(data, "projectPath");
    assertThat(result).isEmpty();
  }

  @ParameterizedTest
  @NullSource
  @EmptySource
  @ValueSource(strings = {" ", "\t", "\n"})
  void validationShouldFailWithInvalidProjectPath(final String projectPath) {
    final var workflows =
        Map.of(
            "wf1",
            new WorkflowDefinition(
                "wf1", "desc", List.of(new WorkflowDefinition.Node("n1", "t1", null)), null));
    final var data =
        new SessionConfigData("session-1", "desc", "initiator", null, projectPath, workflows);
    final var result = validator.validateProperty(data, "projectPath");
    assertThat(result).isNotEmpty();
  }

  @Test
  void validationShouldFailWhenProjectPathExceedsMaxLength() {
    final var workflows =
        Map.of(
            "wf1",
            new WorkflowDefinition(
                "wf1", "desc", List.of(new WorkflowDefinition.Node("n1", "t1", null)), null));
    final var tooLongPath = "/path" + "/a".repeat(512);
    final var data =
        new SessionConfigData("session-1", "desc", "initiator", null, tooLongPath, workflows);
    final var result = validator.validateProperty(data, "projectPath");
    assertThat(result).isNotEmpty();
  }

  @Test
  void validationShouldNotFailWithValidWorkflows() {
    final var workflows =
        Map.of(
            "wf1",
            new WorkflowDefinition(
                "wf1", "desc", List.of(new WorkflowDefinition.Node("n1", "t1", null)), null),
            "wf2",
            new WorkflowDefinition(
                "wf2", "desc", List.of(new WorkflowDefinition.Node("n2", "t2", null)), null));
    final var data =
        new SessionConfigData("session-1", "desc", "initiator", null, "/path", workflows);
    final var result = validator.validateProperty(data, "workflows");
    assertThat(result).isEmpty();
  }

  @ParameterizedTest
  @NullSource
  void validationShouldFailWithNullWorkflows(final Map<String, WorkflowDefinition> workflows) {
    final var data =
        new SessionConfigData("session-1", "desc", "initiator", null, "/path", workflows);
    final var result = validator.validateProperty(data, "workflows");
    assertThat(result).isNotEmpty();
  }

  @Test
  void validationShouldFailWithEmptyWorkflows() {
    final var workflows = Map.<String, WorkflowDefinition>of();
    final var data =
        new SessionConfigData("session-1", "desc", "initiator", null, "/path", workflows);
    final var result = validator.validateProperty(data, "workflows");
    assertThat(result).isNotEmpty();
  }

  @Test
  void tagsMapShouldBeEmptyWhenNull() {
    final var workflows =
        Map.of(
            "wf1",
            new WorkflowDefinition(
                "wf1", "desc", List.of(new WorkflowDefinition.Node("n1", "t1", null)), null));
    final var data =
        new SessionConfigData("session-1", "desc", "initiator", null, "/path", workflows);
    assertThat(data.tags()).isEmpty();
  }

  @Test
  void tagsMapShouldBeImmutable() {
    final var tags = Map.of("env", "prod", "client", "web");
    final var workflows =
        Map.of(
            "wf1",
            new WorkflowDefinition(
                "wf1", "desc", List.of(new WorkflowDefinition.Node("n1", "t1", null)), null));
    final var data =
        new SessionConfigData("session-1", "desc", "initiator", tags, "/path", workflows);
    assertThat(data.tags()).isEqualTo(tags);
    assertThat(data.tags()).containsExactlyEntriesOf(tags);
  }

  @Test
  @SuppressWarnings("PMD.ShortVariable")
  void workflowsMapShouldBeImmutable() {
    final var wf =
        new WorkflowDefinition(
            "wf1", "desc", List.of(new WorkflowDefinition.Node("n1", "t1", null)), null);
    final var workflows = Map.of("wf1", wf);
    final var data =
        new SessionConfigData("session-1", "desc", "initiator", null, "/path", workflows);
    assertThat(data.workflows()).containsEntry("wf1", wf);
    assertThat(data.workflows()).hasSize(1);
  }
}
