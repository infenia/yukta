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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.Validator;
import java.util.List;
import java.util.Map;
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

@SpringJUnitConfig(ConfigRequestTest.TestConfig.class)
@Tag("ConfigRequestTest")
class ConfigRequestTest {

  @Configuration
  static class TestConfig {
    @Bean
    LocalValidatorFactoryBean validator() {
      return new LocalValidatorFactoryBean();
    }
  }

  @Autowired private Validator validator;

  @Test
  void testConfigRequest() {
    ConfigRequest request = new ConfigRequest("s", "d", "i", null, "/p", null);
    assertEquals("s", request.sessionId());
    assertNotNull(request.tags());
    assertNotNull(request.workflows());
    assertTrue(request.tags().isEmpty());
    assertTrue(request.workflows().isEmpty());
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
    final var workflows = Map.of("wf1", createWorkflowDefinitionRequest("wf1", "desc"));
    final var request = new ConfigRequest(sessionId, "desc", "initiator", null, "/path", workflows);
    final var result = validator.validateProperty(request, "sessionId");
    assertThat(result).isEmpty();
  }

  @ParameterizedTest
  @NullSource
  @EmptySource
  @ValueSource(strings = {" ", "\t", "\n"})
  void validationShouldFailWithInvalidSessionId(final String sessionId) {
    final var workflows = Map.of("wf1", createWorkflowDefinitionRequest("wf1", "desc"));
    final var request = new ConfigRequest(sessionId, "desc", "initiator", null, "/path", workflows);
    final var result = validator.validateProperty(request, "sessionId");
    assertThat(result).isNotEmpty();
  }

  @Test
  void validationShouldFailWithSessionIdContainingPathTraversal() {
    final var workflows = Map.of("wf1", createWorkflowDefinitionRequest("wf1", "desc"));
    final var request =
        new ConfigRequest("../session", "desc", "initiator", null, "/path", workflows);
    final var result = validator.validateProperty(request, "sessionId");
    assertThat(result).isNotEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"d", "description", "This is a session description"})
  void validationShouldNotFailWithValidDescription(final String description) {
    final var workflows = Map.of("wf1", createWorkflowDefinitionRequest("wf1", "desc"));
    final var request =
        new ConfigRequest("session-1", description, "initiator", null, "/path", workflows);
    final var result = validator.validateProperty(request, "description");
    assertThat(result).isEmpty();
  }

  @ParameterizedTest
  @NullSource
  @EmptySource
  @ValueSource(strings = {" ", "\t", "\n"})
  void validationShouldFailWithInvalidDescription(final String description) {
    final var workflows = Map.of("wf1", createWorkflowDefinitionRequest("wf1", "desc"));
    final var request =
        new ConfigRequest("session-1", description, "initiator", null, "/path", workflows);
    final var result = validator.validateProperty(request, "description");
    assertThat(result).isNotEmpty();
  }

  @Test
  void validationShouldFailWhenDescriptionExceedsMaxLength() {
    final var workflows = Map.of("wf1", createWorkflowDefinitionRequest("wf1", "desc"));
    final var tooLongDescription = "a".repeat(257);
    final var request =
        new ConfigRequest("session-1", tooLongDescription, "initiator", null, "/path", workflows);
    final var result = validator.validateProperty(request, "description");
    assertThat(result).isNotEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"initiator", "AI Agent", "test-runner"})
  void validationShouldNotFailWithValidInitiator(final String initiator) {
    final var workflows = Map.of("wf1", createWorkflowDefinitionRequest("wf1", "desc"));
    final var request = new ConfigRequest("session-1", "desc", initiator, null, "/path", workflows);
    final var result = validator.validateProperty(request, "initiator");
    assertThat(result).isEmpty();
  }

  @ParameterizedTest
  @NullSource
  @EmptySource
  @ValueSource(strings = {" ", "\t", "\n"})
  void validationShouldFailWithInvalidInitiator(final String initiator) {
    final var workflows = Map.of("wf1", createWorkflowDefinitionRequest("wf1", "desc"));
    final var request = new ConfigRequest("session-1", "desc", initiator, null, "/path", workflows);
    final var result = validator.validateProperty(request, "initiator");
    assertThat(result).isNotEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"/path/to/project", "/home/user/workspace", "/projects/my-app"})
  void validationShouldNotFailWithValidProjectPath(final String projectPath) {
    final var workflows = Map.of("wf1", createWorkflowDefinitionRequest("wf1", "desc"));
    final var request =
        new ConfigRequest("session-1", "desc", "initiator", null, projectPath, workflows);
    final var result = validator.validateProperty(request, "projectPath");
    assertThat(result).isEmpty();
  }

  @ParameterizedTest
  @NullSource
  @EmptySource
  @ValueSource(strings = {" ", "\t", "\n"})
  void validationShouldFailWithInvalidProjectPath(final String projectPath) {
    final var workflows = Map.of("wf1", createWorkflowDefinitionRequest("wf1", "desc"));
    final var request =
        new ConfigRequest("session-1", "desc", "initiator", null, projectPath, workflows);
    final var result = validator.validateProperty(request, "projectPath");
    assertThat(result).isNotEmpty();
  }

  @Test
  void validationShouldFailWhenProjectPathExceedsMaxLength() {
    final var workflows = Map.of("wf1", createWorkflowDefinitionRequest("wf1", "desc"));
    final var tooLongPath = "/path" + "/a".repeat(512);
    final var request =
        new ConfigRequest("session-1", "desc", "initiator", null, tooLongPath, workflows);
    final var result = validator.validateProperty(request, "projectPath");
    assertThat(result).isNotEmpty();
  }

  @Test
  void validationShouldNotFailWithValidWorkflows() {
    final var workflows =
        Map.of(
            "wf1", createWorkflowDefinitionRequest("wf1", "desc"),
            "wf2", createWorkflowDefinitionRequest("wf2", "desc"));
    final var request =
        new ConfigRequest("session-1", "desc", "initiator", null, "/path", workflows);
    final var result = validator.validateProperty(request, "workflows");
    assertThat(result).isEmpty();
  }

  @ParameterizedTest
  @NullSource
  void validationShouldFailWithNullWorkflows(
      final Map<String, WorkflowDefinitionRequest> workflows) {
    final var request =
        new ConfigRequest("session-1", "desc", "initiator", null, "/path", workflows);
    final var result = validator.validateProperty(request, "workflows");
    assertThat(result).isNotEmpty();
  }

  @Test
  void validationShouldFailWithEmptyWorkflows() {
    final var workflows = Map.<String, WorkflowDefinitionRequest>of();
    final var request =
        new ConfigRequest("session-1", "desc", "initiator", null, "/path", workflows);
    final var result = validator.validateProperty(request, "workflows");
    assertThat(result).isNotEmpty();
  }

  @Test
  void tagsMapShouldBeEmptyWhenNull() {
    final var workflows = Map.of("wf1", createWorkflowDefinitionRequest("wf1", "desc"));
    final var request =
        new ConfigRequest("session-1", "desc", "initiator", null, "/path", workflows);
    assertThat(request.tags()).isEmpty();
  }

  @Test
  void tagsMapShouldBeImmutable() {
    final var tags = Map.of("env", "prod", "client", "web");
    final var workflows = Map.of("wf1", createWorkflowDefinitionRequest("wf1", "desc"));
    final var request =
        new ConfigRequest("session-1", "desc", "initiator", tags, "/path", workflows);
    assertThat(request.tags()).isEqualTo(tags);
    assertThat(request.tags()).containsExactlyEntriesOf(tags);
  }

  @Test
  void workflowsMapShouldBeImmutable() {
    final var wf = createWorkflowDefinitionRequest("wf1", "desc");
    final var workflows = Map.of("wf1", wf);
    final var request =
        new ConfigRequest("session-1", "desc", "initiator", null, "/path", workflows);
    assertThat(request.workflows()).containsEntry("wf1", wf);
    assertThat(request.workflows()).hasSize(1);
  }
}
