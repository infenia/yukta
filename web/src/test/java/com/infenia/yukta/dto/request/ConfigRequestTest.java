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

/** Tests for ConfigRequest. */
@SpringJUnitConfig(ConfigRequestTest.TestConfig.class)
@Tag("ConfigRequestTest")
@NoArgsConstructor
@SuppressWarnings("PMD.TooManyMethods")
class ConfigRequestTest {

  /** Test workflow identifier. */
  private static final String WORKFLOW_ID = "wf1";

  /** Test workflow description. */
  private static final String WORKFLOW_DESC = "desc";

  /** Test session identifier. */
  private static final String SESSION_ID = "session-1";

  /** Test initiator constant for validation testing. */
  private static final String INITIATOR = "initiator";

  /** Test description field constant for validation testing. */
  private static final String DESCRIPTION_FIELD = "description";

  /** Test project path. */
  private static final String PROJECT_PATH = "/path";

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
  void testConfigRequest() {
    final ConfigRequest request = new ConfigRequest("s", "d", "i", null, "/p", null);
    assertThat(request.sessionId()).as("session ID should be 's'").isEqualTo("s");
    assertThat(request.tags()).as("tags should not be null").isNotNull();
    assertThat(request.workflows()).as("workflows should not be null").isNotNull();
    assertThat(request.tags()).as("tags should be empty").isEmpty();
    assertThat(request.workflows()).as("workflows should be empty").isEmpty();
  }

  private static WorkflowDefinitionRequest createWorkflowDefinitionRequest(
      final String workflowId, final String description) {
    return new WorkflowDefinitionRequest(
        workflowId,
        description,
        List.of(new WorkflowDefinitionRequest.NodeRequest("n1", "t1", null)),
        List.of());
  }

  @ParameterizedTest
  @ValueSource(strings = {"session-123", "test-session", "prod-workflow-session"})
  void validationShouldNotFailWithValidSessionId(final String sessionId) {
    final var workflows =
        Map.of(WORKFLOW_ID, createWorkflowDefinitionRequest(WORKFLOW_ID, WORKFLOW_DESC));
    final var request =
        new ConfigRequest(sessionId, WORKFLOW_DESC, INITIATOR, null, PROJECT_PATH, workflows);
    final var result = validator.validateProperty(request, "sessionId");
    assertThat(result).isEmpty();
  }

  @ParameterizedTest
  @NullSource
  @EmptySource
  @ValueSource(strings = {" ", "\t", "\n"})
  void validationShouldFailWithInvalidSessionId(final String sessionId) {
    final var workflows =
        Map.of(WORKFLOW_ID, createWorkflowDefinitionRequest(WORKFLOW_ID, WORKFLOW_DESC));
    final var request =
        new ConfigRequest(sessionId, WORKFLOW_DESC, INITIATOR, null, PROJECT_PATH, workflows);
    final var result = validator.validateProperty(request, "sessionId");
    assertThat(result).isNotEmpty();
  }

  @Test
  void validationShouldFailWithSessionIdContainingPathTraversal() {
    final var workflows =
        Map.of(WORKFLOW_ID, createWorkflowDefinitionRequest(WORKFLOW_ID, WORKFLOW_DESC));
    final var request =
        new ConfigRequest("../session", WORKFLOW_DESC, INITIATOR, null, PROJECT_PATH, workflows);
    final var result = validator.validateProperty(request, "sessionId");
    assertThat(result).isNotEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"d", "description", "This is a session description"})
  void validationShouldNotFailWithValidDescription(final String description) {
    final var workflows =
        Map.of(WORKFLOW_ID, createWorkflowDefinitionRequest(WORKFLOW_ID, WORKFLOW_DESC));
    final var request =
        new ConfigRequest(SESSION_ID, description, INITIATOR, null, PROJECT_PATH, workflows);
    final var result = validator.validateProperty(request, DESCRIPTION_FIELD);
    assertThat(result).isEmpty();
  }

  @ParameterizedTest
  @NullSource
  @EmptySource
  @ValueSource(strings = {" ", "\t", "\n"})
  void validationShouldFailWithInvalidDescription(final String description) {
    final var workflows =
        Map.of(WORKFLOW_ID, createWorkflowDefinitionRequest(WORKFLOW_ID, WORKFLOW_DESC));
    final var request =
        new ConfigRequest(SESSION_ID, description, INITIATOR, null, PROJECT_PATH, workflows);
    final var result = validator.validateProperty(request, DESCRIPTION_FIELD);
    assertThat(result).isNotEmpty();
  }

  @Test
  void validationShouldFailWhenDescriptionExceedsMaxLength() {
    final var workflows =
        Map.of(WORKFLOW_ID, createWorkflowDefinitionRequest(WORKFLOW_ID, WORKFLOW_DESC));
    final var tooLongDescription = "a".repeat(257);
    final var request =
        new ConfigRequest(SESSION_ID, tooLongDescription, INITIATOR, null, PROJECT_PATH, workflows);
    final var result = validator.validateProperty(request, DESCRIPTION_FIELD);
    assertThat(result).isNotEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"initiator", "AI Agent", "test-runner"})
  void validationShouldNotFailWithValidInitiator(final String initiator) {
    final var workflows =
        Map.of(WORKFLOW_ID, createWorkflowDefinitionRequest(WORKFLOW_ID, WORKFLOW_DESC));
    final var request =
        new ConfigRequest(SESSION_ID, WORKFLOW_DESC, initiator, null, PROJECT_PATH, workflows);
    final var result = validator.validateProperty(request, INITIATOR);
    assertThat(result).isEmpty();
  }

  @ParameterizedTest
  @NullSource
  @EmptySource
  @ValueSource(strings = {" ", "\t", "\n"})
  void validationShouldFailWithInvalidInitiator(final String initiator) {
    final var workflows =
        Map.of(WORKFLOW_ID, createWorkflowDefinitionRequest(WORKFLOW_ID, WORKFLOW_DESC));
    final var request =
        new ConfigRequest(SESSION_ID, WORKFLOW_DESC, initiator, null, PROJECT_PATH, workflows);
    final var result = validator.validateProperty(request, INITIATOR);
    assertThat(result).isNotEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"/path/to/project", "/home/user/workspace", "/projects/my-app"})
  void validationShouldNotFailWithValidProjectPath(final String projectPath) {
    final var workflows =
        Map.of(WORKFLOW_ID, createWorkflowDefinitionRequest(WORKFLOW_ID, WORKFLOW_DESC));
    final var request =
        new ConfigRequest(SESSION_ID, WORKFLOW_DESC, INITIATOR, null, projectPath, workflows);
    final var result = validator.validateProperty(request, "projectPath");
    assertThat(result).isEmpty();
  }

  @ParameterizedTest
  @NullSource
  @EmptySource
  @ValueSource(strings = {" ", "\t", "\n"})
  void validationShouldFailWithInvalidProjectPath(final String projectPath) {
    final var workflows =
        Map.of(WORKFLOW_ID, createWorkflowDefinitionRequest(WORKFLOW_ID, WORKFLOW_DESC));
    final var request =
        new ConfigRequest(SESSION_ID, WORKFLOW_DESC, INITIATOR, null, projectPath, workflows);
    final var result = validator.validateProperty(request, "projectPath");
    assertThat(result).isNotEmpty();
  }

  @Test
  void validationShouldFailWhenProjectPathExceedsMaxLength() {
    final var workflows =
        Map.of(WORKFLOW_ID, createWorkflowDefinitionRequest(WORKFLOW_ID, WORKFLOW_DESC));
    final var tooLongPath = "/path" + "/a".repeat(512);
    final var request =
        new ConfigRequest(SESSION_ID, WORKFLOW_DESC, INITIATOR, null, tooLongPath, workflows);
    final var result = validator.validateProperty(request, "projectPath");
    assertThat(result).isNotEmpty();
  }

  @Test
  void validationShouldNotFailWithValidWorkflows() {
    final var workflows =
        Map.of(
            WORKFLOW_ID,
            createWorkflowDefinitionRequest(WORKFLOW_ID, WORKFLOW_DESC),
            "wf2",
            createWorkflowDefinitionRequest("wf2", WORKFLOW_DESC));
    final var request =
        new ConfigRequest(SESSION_ID, WORKFLOW_DESC, INITIATOR, null, PROJECT_PATH, workflows);
    final var result = validator.validateProperty(request, "workflows");
    assertThat(result).isEmpty();
  }

  @ParameterizedTest
  @NullSource
  void validationShouldFailWithNullWorkflows(
      final Map<String, WorkflowDefinitionRequest> workflows) {
    final var request =
        new ConfigRequest(SESSION_ID, WORKFLOW_DESC, INITIATOR, null, PROJECT_PATH, workflows);
    final var result = validator.validateProperty(request, "workflows");
    assertThat(result).isNotEmpty();
  }

  @Test
  void validationShouldFailWithEmptyWorkflows() {
    final var workflows = Map.<String, WorkflowDefinitionRequest>of();
    final var request =
        new ConfigRequest(SESSION_ID, WORKFLOW_DESC, INITIATOR, null, PROJECT_PATH, workflows);
    final var result = validator.validateProperty(request, "workflows");
    assertThat(result).isNotEmpty();
  }

  @Test
  void tagsMapShouldBeEmptyWhenNull() {
    final var workflows =
        Map.of(WORKFLOW_ID, createWorkflowDefinitionRequest(WORKFLOW_ID, WORKFLOW_DESC));
    final var request =
        new ConfigRequest(SESSION_ID, WORKFLOW_DESC, INITIATOR, null, PROJECT_PATH, workflows);
    assertThat(request.tags()).isEmpty();
  }

  @Test
  void tagsMapShouldBeImmutable() {
    final var tags = Map.of("env", "prod", "client", "web");
    final var workflows =
        Map.of(WORKFLOW_ID, createWorkflowDefinitionRequest(WORKFLOW_ID, WORKFLOW_DESC));
    final var request =
        new ConfigRequest(SESSION_ID, WORKFLOW_DESC, INITIATOR, tags, PROJECT_PATH, workflows);
    assertThat(request.tags()).isEqualTo(tags);
    assertThat(request.tags()).containsExactlyEntriesOf(tags);
  }

  @Test
  void workflowsMapShouldBeImmutable() {
    final var workflowDefinition = createWorkflowDefinitionRequest(WORKFLOW_ID, WORKFLOW_DESC);
    final var workflows = Map.of(WORKFLOW_ID, workflowDefinition);
    final var request =
        new ConfigRequest(SESSION_ID, WORKFLOW_DESC, INITIATOR, null, PROJECT_PATH, workflows);
    assertThat(request.workflows()).containsEntry(WORKFLOW_ID, workflowDefinition);
    assertThat(request.workflows()).hasSize(1);
  }
}
