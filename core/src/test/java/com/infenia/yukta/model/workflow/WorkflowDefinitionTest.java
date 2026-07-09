// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.model.workflow;

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

/** Tests for {@link WorkflowDefinition}. */
@SuppressWarnings({
  "PMD.TooManyMethods",
  "PMD.AvoidDuplicateLiterals",
  "PMD.CommentDefaultAccessModifier",
  "PMD.TestClassWithoutTestCases",
  "PMD.FieldDeclarationsShouldBeAtStartOfClass"
})
@SpringJUnitConfig(WorkflowDefinitionTest.TestConfig.class)
@Tag("WorkflowDefinitionTest")
@NoArgsConstructor
class WorkflowDefinitionTest {

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
  void testWorkflowDefinition() {
    final Map<String, Object> config = Map.of("k", "v");
    final WorkflowDefinition.Node node = new WorkflowDefinition.Node("n", "t", config);
    assertThat(node.nodeId()).isEqualTo("n");
    assertThat(node.type()).isEqualTo("t");
    assertThat(node.config()).isEqualTo(config);

    final WorkflowDefinition.Edge edge = new WorkflowDefinition.Edge("s", "t", "p");
    assertThat(edge.source()).isEqualTo("s");
    assertThat(edge.target()).isEqualTo("t");
    assertThat(edge.sourcePort()).isEqualTo("p");

    final WorkflowDefinition def = new WorkflowDefinition("wf-1", "d", List.of(node), null);
    assertThat(def.workflowId()).isEqualTo("wf-1");
    assertThat(def.description()).isEqualTo("d");
    assertThat(def.nodes()).hasSize(1);
    assertThat(def.edges()).isNotNull().isEmpty();

    final WorkflowDefinition defNullNodes =
        new WorkflowDefinition("wf-2", "d", null, List.of(edge));
    assertThat(defNullNodes.nodes()).isNotNull().isEmpty();
    assertThat(defNullNodes.edges()).hasSize(1);
  }

  @Test
  void testWorkflowDefinitionImmutability() {
    final WorkflowDefinition.Node node = new WorkflowDefinition.Node("n1", "t1", null);
    final WorkflowDefinition.Edge edge = new WorkflowDefinition.Edge("n1", "n2", "default");
    final WorkflowDefinition def =
        new WorkflowDefinition("wf-3", "desc", List.of(node), List.of(edge));

    assertThat(def.nodes()).isNotNull().hasSize(1);
    assertThat(def.edges()).isNotNull().hasSize(1);
  }

  @ParameterizedTest
  @ValueSource(strings = {"wf-1", "workflow-123", "my-workflow-id"})
  void validationShouldNotFailWithValidWorkflowId(final String workflowId) {
    final WorkflowDefinition.Node node = new WorkflowDefinition.Node("n1", "t1", null);
    final var def = new WorkflowDefinition(workflowId, "desc", List.of(node), null);
    final var result = validator.validateProperty(def, "workflowId");
    assertThat(result).isEmpty();
  }

  @ParameterizedTest
  @NullSource
  @EmptySource
  @ValueSource(strings = {" ", "\t", "\n"})
  void validationShouldFailWithInvalidWorkflowId(final String workflowId) {
    final WorkflowDefinition.Node node = new WorkflowDefinition.Node("n1", "t1", null);
    final var def = new WorkflowDefinition(workflowId, "desc", List.of(node), null);
    final var result = validator.validateProperty(def, "workflowId");
    assertThat(result).isNotEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"d", "description", "This is a workflow description"})
  void validationShouldNotFailWithValidDescription(final String description) {
    final WorkflowDefinition.Node node = new WorkflowDefinition.Node("n1", "t1", null);
    final var def = new WorkflowDefinition("wf-1", description, List.of(node), null);
    final var result = validator.validateProperty(def, "description");
    assertThat(result).isEmpty();
  }

  @ParameterizedTest
  @NullSource
  @EmptySource
  @ValueSource(strings = {" ", "\t", "\n"})
  void validationShouldFailWithInvalidDescription(final String description) {
    final WorkflowDefinition.Node node = new WorkflowDefinition.Node("n1", "t1", null);
    final var def = new WorkflowDefinition("wf-1", description, List.of(node), null);
    final var result = validator.validateProperty(def, "description");
    assertThat(result).isNotEmpty();
  }

  @Test
  void validationShouldFailWhenDescriptionExceedsMaxLength() {
    final WorkflowDefinition.Node node = new WorkflowDefinition.Node("n1", "t1", null);
    final var tooLongDescription = "a".repeat(257);
    final var def = new WorkflowDefinition("wf-1", tooLongDescription, List.of(node), null);
    final var result = validator.validateProperty(def, "description");
    assertThat(result).isNotEmpty();
  }

  @Test
  void validationShouldNotFailWithValidNodes() {
    final WorkflowDefinition.Node node = new WorkflowDefinition.Node("n1", "t1", null);
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
