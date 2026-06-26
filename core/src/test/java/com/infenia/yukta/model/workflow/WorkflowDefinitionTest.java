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
package com.infenia.yukta.model.workflow;

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

@SpringJUnitConfig(WorkflowDefinitionTest.TestConfig.class)
@Tag("WorkflowDefinitionTest")
class WorkflowDefinitionTest {

  @Configuration
  static class TestConfig {
    @Bean
    LocalValidatorFactoryBean validator() {
      return new LocalValidatorFactoryBean();
    }
  }

  @Autowired private Validator validator;

  @Test
  void testWorkflowDefinition() {
    Map<String, Object> config = Map.of("k", "v");
    WorkflowDefinition.Node node = new WorkflowDefinition.Node("n", "t", config);
    assertEquals("n", node.nodeId());
    assertEquals("t", node.type());
    assertEquals(config, node.config());

    WorkflowDefinition.Edge edge = new WorkflowDefinition.Edge("s", "t", "p");
    assertEquals("s", edge.source());
    assertEquals("t", edge.target());
    assertEquals("p", edge.sourcePort());

    WorkflowDefinition def = new WorkflowDefinition("wf-1", "d", List.of(node), null);
    assertEquals("wf-1", def.workflowId());
    assertEquals("d", def.description());
    assertEquals(1, def.nodes().size());
    assertNotNull(def.edges());
    assertTrue(def.edges().isEmpty());

    WorkflowDefinition defNullNodes = new WorkflowDefinition("wf-2", "d", null, List.of(edge));
    assertNotNull(defNullNodes.nodes());
    assertTrue(defNullNodes.nodes().isEmpty());
    assertEquals(1, defNullNodes.edges().size());
  }

  @Test
  void testWorkflowDefinitionImmutability() {
    WorkflowDefinition.Node node = new WorkflowDefinition.Node("n1", "t1", null);
    WorkflowDefinition.Edge edge = new WorkflowDefinition.Edge("n1", "n2", "default");
    WorkflowDefinition def = new WorkflowDefinition("wf-3", "desc", List.of(node), List.of(edge));

    assertNotNull(def.nodes());
    assertNotNull(def.edges());
    assertEquals(1, def.nodes().size());
  }

  @ParameterizedTest
  @ValueSource(strings = {"wf-1", "workflow-123", "my-workflow-id"})
  void validationShouldNotFailWithValidWorkflowId(final String workflowId) {
    WorkflowDefinition.Node node = new WorkflowDefinition.Node("n1", "t1", null);
    final var def = new WorkflowDefinition(workflowId, "desc", List.of(node), null);
    final var result = validator.validateProperty(def, "workflowId");
    assertThat(result).isEmpty();
  }

  @ParameterizedTest
  @NullSource
  @EmptySource
  @ValueSource(strings = {" ", "\t", "\n"})
  void validationShouldFailWithInvalidWorkflowId(final String workflowId) {
    WorkflowDefinition.Node node = new WorkflowDefinition.Node("n1", "t1", null);
    final var def = new WorkflowDefinition(workflowId, "desc", List.of(node), null);
    final var result = validator.validateProperty(def, "workflowId");
    assertThat(result).isNotEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"d", "description", "This is a workflow description"})
  void validationShouldNotFailWithValidDescription(final String description) {
    WorkflowDefinition.Node node = new WorkflowDefinition.Node("n1", "t1", null);
    final var def = new WorkflowDefinition("wf-1", description, List.of(node), null);
    final var result = validator.validateProperty(def, "description");
    assertThat(result).isEmpty();
  }

  @ParameterizedTest
  @NullSource
  @EmptySource
  @ValueSource(strings = {" ", "\t", "\n"})
  void validationShouldFailWithInvalidDescription(final String description) {
    WorkflowDefinition.Node node = new WorkflowDefinition.Node("n1", "t1", null);
    final var def = new WorkflowDefinition("wf-1", description, List.of(node), null);
    final var result = validator.validateProperty(def, "description");
    assertThat(result).isNotEmpty();
  }

  @Test
  void validationShouldFailWhenDescriptionExceedsMaxLength() {
    WorkflowDefinition.Node node = new WorkflowDefinition.Node("n1", "t1", null);
    final var tooLongDescription = "a".repeat(257);
    final var def = new WorkflowDefinition("wf-1", tooLongDescription, List.of(node), null);
    final var result = validator.validateProperty(def, "description");
    assertThat(result).isNotEmpty();
  }

  @Test
  void validationShouldNotFailWithValidNodes() {
    WorkflowDefinition.Node node = new WorkflowDefinition.Node("n1", "t1", null);
    final var def = new WorkflowDefinition("wf-1", "desc", List.of(node), null);
    final var result = validator.validateProperty(def, "nodes");
    assertThat(result).isEmpty();
  }

  @ParameterizedTest
  @NullSource
  void validationShouldFailWithNullOrEmptyNodes(final List<WorkflowDefinition.Node> nodes) {
    final var def = new WorkflowDefinition("wf-1", "desc", nodes, null);
    final var result = validator.validateProperty(def, "nodes");
    assertThat(result).isNotEmpty();
  }

  @Test
  void validationShouldFailWithEmptyNodesList() {
    final var def = new WorkflowDefinition("wf-1", "desc", List.of(), null);
    final var result = validator.validateProperty(def, "nodes");
    assertThat(result).isNotEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"n1", "node-1", "my-node-id"})
  void nodeShouldNotFailValidationWithValidNodeId(final String nodeId) {
    final var node = new WorkflowDefinition.Node(nodeId, "type", null);
    final var result = validator.validateProperty(node, "nodeId");
    assertThat(result).isEmpty();
  }

  @ParameterizedTest
  @NullSource
  @EmptySource
  @ValueSource(strings = {" ", "\t", "\n"})
  void nodeShouldFailValidationWithInvalidNodeId(final String nodeId) {
    final var node = new WorkflowDefinition.Node(nodeId, "type", null);
    final var result = validator.validateProperty(node, "nodeId");
    assertThat(result).isNotEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"t", "plugin-type", "TriggerPlugin"})
  void nodeShouldNotFailValidationWithValidType(final String type) {
    final var node = new WorkflowDefinition.Node("n1", type, null);
    final var result = validator.validateProperty(node, "type");
    assertThat(result).isEmpty();
  }

  @ParameterizedTest
  @NullSource
  @EmptySource
  @ValueSource(strings = {" ", "\t", "\n"})
  void nodeShouldFailValidationWithInvalidType(final String type) {
    final var node = new WorkflowDefinition.Node("n1", type, null);
    final var result = validator.validateProperty(node, "type");
    assertThat(result).isNotEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"s1", "source-node", "start"})
  void edgeShouldNotFailValidationWithValidSource(final String source) {
    final var edge = new WorkflowDefinition.Edge(source, "t1", "default");
    final var result = validator.validateProperty(edge, "source");
    assertThat(result).isEmpty();
  }

  @ParameterizedTest
  @NullSource
  @EmptySource
  @ValueSource(strings = {" ", "\t", "\n"})
  void edgeShouldFailValidationWithInvalidSource(final String source) {
    final var edge = new WorkflowDefinition.Edge(source, "t1", "default");
    final var result = validator.validateProperty(edge, "source");
    assertThat(result).isNotEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"t1", "target-node", "end"})
  void edgeShouldNotFailValidationWithValidTarget(final String target) {
    final var edge = new WorkflowDefinition.Edge("s1", target, "default");
    final var result = validator.validateProperty(edge, "target");
    assertThat(result).isEmpty();
  }

  @ParameterizedTest
  @NullSource
  @EmptySource
  @ValueSource(strings = {" ", "\t", "\n"})
  void edgeShouldFailValidationWithInvalidTarget(final String target) {
    final var edge = new WorkflowDefinition.Edge("s1", target, "default");
    final var result = validator.validateProperty(edge, "target");
    assertThat(result).isNotEmpty();
  }
}
