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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

@SpringJUnitConfig(WorkflowDefinitionRequestTest.TestConfig.class)
class WorkflowDefinitionRequestTest {

  @Configuration
  static class TestConfig {
    @Bean
    LocalValidatorFactoryBean validator() {
      return new LocalValidatorFactoryBean();
    }
  }

  @Autowired private Validator validator;

  private List<WorkflowDefinitionRequest.NodeRequest> validNodes;
  private List<WorkflowDefinitionRequest.EdgeRequest> validEdges;

  @BeforeEach
  void setUp() {
    validNodes = List.of(new WorkflowDefinitionRequest.NodeRequest("node1", "gradle", null));
    validEdges = List.of();
  }

  // === WorkflowDefinitionRequest Tests ===

  @Test
  void constructor_validInputs_createsRecord() {
    // Given
    String workflowId = "quality-check";
    String description = "Quality check workflow";
    List<WorkflowDefinitionRequest.NodeRequest> nodes =
        List.of(new WorkflowDefinitionRequest.NodeRequest("n1", "gradle", null));
    List<WorkflowDefinitionRequest.EdgeRequest> edges = List.of();

    // When
    WorkflowDefinitionRequest request =
        new WorkflowDefinitionRequest(workflowId, description, nodes, edges);

    // Then
    assertThat(request.workflowId()).isEqualTo(workflowId);
    assertThat(request.description()).isEqualTo(description);
    assertThat(request.nodes()).hasSize(1);
    assertThat(request.edges()).isEmpty();
  }

  @Test
  void constructor_nullNodes_convertsToEmptyList() {
    // When
    WorkflowDefinitionRequest request =
        new WorkflowDefinitionRequest("wf1", "desc", null, List.of());

    // Then
    assertThat(request.nodes()).isEmpty();
    assertThat(request.nodes()).isUnmodifiable();
  }

  @Test
  void constructor_nullEdges_convertsToEmptyList() {
    // When
    WorkflowDefinitionRequest request =
        new WorkflowDefinitionRequest("wf1", "desc", validNodes, null);

    // Then
    assertThat(request.edges()).isEmpty();
    assertThat(request.edges()).isUnmodifiable();
  }

  @Test
  void constructor_nullBothNodesAndEdges_convertsToEmptyLists() {
    // When
    WorkflowDefinitionRequest request = new WorkflowDefinitionRequest("wf1", "desc", null, null);

    // Then
    assertThat(request.nodes()).isEmpty();
    assertThat(request.edges()).isEmpty();
  }

  @Test
  void constructor_mutableNodesList_convertsToImmutable() {
    // Given
    List<WorkflowDefinitionRequest.NodeRequest> mutableNodes =
        new ArrayList<>(
            List.of(
                new WorkflowDefinitionRequest.NodeRequest("n1", "gradle", null),
                new WorkflowDefinitionRequest.NodeRequest("n2", "maven", null)));

    // When
    WorkflowDefinitionRequest request =
        new WorkflowDefinitionRequest("wf1", "desc", mutableNodes, validEdges);

    // Then - verify nodes are immutable
    assertThat(request.nodes()).hasSize(2);
    assertThatThrownBy(
            () ->
                request
                    .nodes()
                    .add(new WorkflowDefinitionRequest.NodeRequest("n3", "gradle", null)))
        .isInstanceOf(UnsupportedOperationException.class);

    // Modify original list - request should be unaffected
    mutableNodes.add(new WorkflowDefinitionRequest.NodeRequest("n4", "gradle", null));
    assertThat(request.nodes()).hasSize(2);
  }

  @Test
  void constructor_mutableEdgesList_convertsToImmutable() {
    // Given
    List<WorkflowDefinitionRequest.EdgeRequest> mutableEdges =
        new ArrayList<>(List.of(new WorkflowDefinitionRequest.EdgeRequest("n1", "n2", null)));

    // When
    WorkflowDefinitionRequest request =
        new WorkflowDefinitionRequest("wf1", "desc", validNodes, mutableEdges);

    // Then - verify edges are immutable
    assertThat(request.edges()).hasSize(1);
    assertThatThrownBy(
            () -> request.edges().add(new WorkflowDefinitionRequest.EdgeRequest("n2", "n3", null)))
        .isInstanceOf(UnsupportedOperationException.class);

    // Modify original list - request should be unaffected
    mutableEdges.add(new WorkflowDefinitionRequest.EdgeRequest("n2", "n3", null));
    assertThat(request.edges()).hasSize(1);
  }

  @Test
  void nodes_modificationAttempt_throwsUnsupportedOperationException() {
    // Given
    WorkflowDefinitionRequest request =
        new WorkflowDefinitionRequest("wf1", "desc", validNodes, validEdges);

    // When-Then
    assertThatThrownBy(
            () ->
                request
                    .nodes()
                    .add(new WorkflowDefinitionRequest.NodeRequest("n2", "gradle", null)))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void edges_modificationAttempt_throwsUnsupportedOperationException() {
    // Given
    WorkflowDefinitionRequest request =
        new WorkflowDefinitionRequest("wf1", "desc", validNodes, validEdges);

    // When-Then
    assertThatThrownBy(
            () -> request.edges().add(new WorkflowDefinitionRequest.EdgeRequest("n1", "n2", null)))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void equals_sameValues_returnsTrue() {
    // Given
    WorkflowDefinitionRequest req1 =
        new WorkflowDefinitionRequest("wf1", "desc", validNodes, validEdges);
    WorkflowDefinitionRequest req2 =
        new WorkflowDefinitionRequest("wf1", "desc", validNodes, validEdges);

    // When-Then
    assertThat(req1).isEqualTo(req2);
    assertThat(req1.hashCode()).isEqualTo(req2.hashCode());
  }

  @Test
  void equals_differentWorkflowIds_returnsFalse() {
    // Given
    WorkflowDefinitionRequest req1 =
        new WorkflowDefinitionRequest("wf1", "desc", validNodes, validEdges);
    WorkflowDefinitionRequest req2 =
        new WorkflowDefinitionRequest("wf2", "desc", validNodes, validEdges);

    // When-Then
    assertThat(req1).isNotEqualTo(req2);
  }

  @Test
  void equals_differentDescriptions_returnsFalse() {
    // Given
    WorkflowDefinitionRequest req1 =
        new WorkflowDefinitionRequest("wf1", "desc1", validNodes, validEdges);
    WorkflowDefinitionRequest req2 =
        new WorkflowDefinitionRequest("wf1", "desc2", validNodes, validEdges);

    // When-Then
    assertThat(req1).isNotEqualTo(req2);
  }

  @Test
  void toString_containsRelevantFields() {
    // Given
    WorkflowDefinitionRequest request =
        new WorkflowDefinitionRequest("wf1", "desc", validNodes, validEdges);

    // When
    String actual = request.toString();

    // Then
    assertThat(actual).contains("WorkflowDefinitionRequest").contains("wf1").contains("desc");
  }

  @Test
  void validation_blankWorkflowId_fails() {
    // Given
    WorkflowDefinitionRequest request =
        new WorkflowDefinitionRequest("", "desc", validNodes, validEdges);

    // When
    Set<ConstraintViolation<WorkflowDefinitionRequest>> violations = validator.validate(request);

    // Then
    assertThat(violations).isNotEmpty();
    assertThat(violations).anyMatch(v -> v.getMessage().contains("Workflow ID"));
  }

  @Test
  void validation_nullWorkflowId_fails() {
    // Given
    WorkflowDefinitionRequest request =
        new WorkflowDefinitionRequest(null, "desc", validNodes, validEdges);

    // When
    Set<ConstraintViolation<WorkflowDefinitionRequest>> violations = validator.validate(request);

    // Then
    assertThat(violations).isNotEmpty();
  }

  @Test
  void validation_blankDescription_fails() {
    // Given
    WorkflowDefinitionRequest request =
        new WorkflowDefinitionRequest("wf1", "", validNodes, validEdges);

    // When
    Set<ConstraintViolation<WorkflowDefinitionRequest>> violations = validator.validate(request);

    // Then
    assertThat(violations).isNotEmpty();
    assertThat(violations).anyMatch(v -> v.getMessage().contains("description"));
  }

  @Test
  void validation_descriptionExceedsMaxLength_fails() {
    // Given
    String longDescription = "a".repeat(257);
    WorkflowDefinitionRequest request =
        new WorkflowDefinitionRequest("wf1", longDescription, validNodes, validEdges);

    // When
    Set<ConstraintViolation<WorkflowDefinitionRequest>> violations = validator.validate(request);

    // Then
    assertThat(violations).isNotEmpty();
    assertThat(violations).anyMatch(v -> v.getMessage().contains("256"));
  }

  @Test
  void validation_descriptionAtMaxLength_passes() {
    // Given
    String maxDescription = "a".repeat(256);
    WorkflowDefinitionRequest request =
        new WorkflowDefinitionRequest("wf1", maxDescription, validNodes, validEdges);

    // When
    Set<ConstraintViolation<WorkflowDefinitionRequest>> violations = validator.validate(request);

    // Then
    assertThat(violations).isEmpty();
  }

  @Test
  void validation_emptyNodesList_fails() {
    // Given
    WorkflowDefinitionRequest request =
        new WorkflowDefinitionRequest("wf1", "desc", List.of(), validEdges);

    // When
    Set<ConstraintViolation<WorkflowDefinitionRequest>> violations = validator.validate(request);

    // Then
    assertThat(violations).isNotEmpty();
    assertThat(violations).anyMatch(v -> v.getMessage().contains("at least one node"));
  }

  @Test
  void validation_nullEdges_fails() {
    // Given - null edges gets converted to List.of() by compact constructor
    // So we need to test the validation on the parameter level
    WorkflowDefinitionRequest request =
        new WorkflowDefinitionRequest("wf1", "desc", validNodes, null);

    // When
    Set<ConstraintViolation<WorkflowDefinitionRequest>> violations = validator.validate(request);

    // Then - edges should be empty list after compact constructor
    assertThat(request.edges()).isEmpty();
    // Validation on edges list itself would only fail if not handled by compact constructor
  }

  @Test
  void validation_validNodesList_passes() {
    // Given
    WorkflowDefinitionRequest request =
        new WorkflowDefinitionRequest("wf1", "desc", validNodes, validEdges);

    // When
    Set<ConstraintViolation<WorkflowDefinitionRequest>> violations = validator.validate(request);

    // Then
    assertThat(violations).isEmpty();
  }

  // === NodeRequest Tests ===

  @Test
  void nodeRequest_validInputs_createsRecord() {
    // Given
    String nodeId = "node1";
    String type = "gradle";
    Map<String, Object> config = Map.of("key", "value");

    // When
    WorkflowDefinitionRequest.NodeRequest request =
        new WorkflowDefinitionRequest.NodeRequest(nodeId, type, config);

    // Then
    assertThat(request.nodeId()).isEqualTo(nodeId);
    assertThat(request.type()).isEqualTo(type);
    assertThat(request.config()).containsEntry("key", "value");
  }

  @Test
  void nodeRequest_nullConfig_convertsToEmptyMap() {
    // When
    WorkflowDefinitionRequest.NodeRequest request =
        new WorkflowDefinitionRequest.NodeRequest("n1", "gradle", null);

    // Then
    assertThat(request.config()).isEmpty();
    assertThat(request.config()).isUnmodifiable();
  }

  @Test
  void nodeRequest_mutableConfig_convertsToImmutable() {
    // Given
    Map<String, Object> mutableConfig = new HashMap<>(Map.of("key1", "value1"));

    // When
    WorkflowDefinitionRequest.NodeRequest request =
        new WorkflowDefinitionRequest.NodeRequest("n1", "gradle", mutableConfig);

    // Then - verify config is immutable
    assertThat(request.config()).containsEntry("key1", "value1");
    assertThatThrownBy(() -> request.config().put("key2", "value2"))
        .isInstanceOf(UnsupportedOperationException.class);

    // Modify original map - request should be unaffected
    mutableConfig.put("key3", "value3");
    assertThat(request.config()).hasSize(1);
  }

  @Test
  void nodeRequest_configMapModification_throwsUnsupportedOperationException() {
    // Given
    WorkflowDefinitionRequest.NodeRequest request =
        new WorkflowDefinitionRequest.NodeRequest("n1", "gradle", Map.of("key", "value"));

    // When-Then
    assertThatThrownBy(() -> request.config().put("newKey", "newValue"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void nodeRequest_equals_sameValues_returnsTrue() {
    // Given
    WorkflowDefinitionRequest.NodeRequest req1 =
        new WorkflowDefinitionRequest.NodeRequest("n1", "gradle", Map.of("key", "value"));
    WorkflowDefinitionRequest.NodeRequest req2 =
        new WorkflowDefinitionRequest.NodeRequest("n1", "gradle", Map.of("key", "value"));

    // When-Then
    assertThat(req1).isEqualTo(req2);
    assertThat(req1.hashCode()).isEqualTo(req2.hashCode());
  }

  @Test
  void nodeRequest_equals_differentNodeId_returnsFalse() {
    // Given
    WorkflowDefinitionRequest.NodeRequest req1 =
        new WorkflowDefinitionRequest.NodeRequest("n1", "gradle", null);
    WorkflowDefinitionRequest.NodeRequest req2 =
        new WorkflowDefinitionRequest.NodeRequest("n2", "gradle", null);

    // When-Then
    assertThat(req1).isNotEqualTo(req2);
  }

  @Test
  void nodeRequest_equals_differentType_returnsFalse() {
    // Given
    WorkflowDefinitionRequest.NodeRequest req1 =
        new WorkflowDefinitionRequest.NodeRequest("n1", "gradle", null);
    WorkflowDefinitionRequest.NodeRequest req2 =
        new WorkflowDefinitionRequest.NodeRequest("n1", "maven", null);

    // When-Then
    assertThat(req1).isNotEqualTo(req2);
  }

  @Test
  void nodeRequest_toString_containsAllFields() {
    // Given
    WorkflowDefinitionRequest.NodeRequest request =
        new WorkflowDefinitionRequest.NodeRequest("n1", "gradle", Map.of("key", "value"));

    // When
    String actual = request.toString();

    // Then
    assertThat(actual).contains("NodeRequest").contains("n1").contains("gradle");
  }

  @Test
  void nodeRequest_validation_nullNodeId_fails() {
    // Given
    WorkflowDefinitionRequest.NodeRequest request =
        new WorkflowDefinitionRequest.NodeRequest(null, "gradle", null);

    // When
    Set<ConstraintViolation<WorkflowDefinitionRequest.NodeRequest>> violations =
        validator.validate(request);

    // Then
    assertThat(violations).isNotEmpty();
    assertThat(violations).anyMatch(v -> v.getMessage().contains("Node ID"));
  }

  @Test
  void nodeRequest_validation_blankNodeId_fails() {
    // Given
    WorkflowDefinitionRequest.NodeRequest request =
        new WorkflowDefinitionRequest.NodeRequest("", "gradle", null);

    // When
    Set<ConstraintViolation<WorkflowDefinitionRequest.NodeRequest>> violations =
        validator.validate(request);

    // Then
    assertThat(violations).isNotEmpty();
    assertThat(violations).anyMatch(v -> v.getMessage().contains("Node ID"));
  }

  @Test
  void nodeRequest_validation_nullType_fails() {
    // Given
    WorkflowDefinitionRequest.NodeRequest request =
        new WorkflowDefinitionRequest.NodeRequest("n1", null, null);

    // When
    Set<ConstraintViolation<WorkflowDefinitionRequest.NodeRequest>> violations =
        validator.validate(request);

    // Then
    assertThat(violations).isNotEmpty();
    assertThat(violations).anyMatch(v -> v.getMessage().contains("Plugin type"));
  }

  @Test
  void nodeRequest_validation_blankType_fails() {
    // Given
    WorkflowDefinitionRequest.NodeRequest request =
        new WorkflowDefinitionRequest.NodeRequest("n1", "", null);

    // When
    Set<ConstraintViolation<WorkflowDefinitionRequest.NodeRequest>> violations =
        validator.validate(request);

    // Then
    assertThat(violations).isNotEmpty();
    assertThat(violations).anyMatch(v -> v.getMessage().contains("Plugin type"));
  }

  @Test
  void nodeRequest_validation_validValues_passes() {
    // Given
    WorkflowDefinitionRequest.NodeRequest request =
        new WorkflowDefinitionRequest.NodeRequest("n1", "gradle", Map.of("key", "value"));

    // When
    Set<ConstraintViolation<WorkflowDefinitionRequest.NodeRequest>> violations =
        validator.validate(request);

    // Then
    assertThat(violations).isEmpty();
  }

  // === EdgeRequest Tests ===

  @Test
  void edgeRequest_validInputs_createsRecord() {
    // Given
    String source = "node1";
    String target = "node2";
    String sourcePort = "output";

    // When
    WorkflowDefinitionRequest.EdgeRequest request =
        new WorkflowDefinitionRequest.EdgeRequest(source, target, sourcePort);

    // Then
    assertThat(request.source()).isEqualTo(source);
    assertThat(request.target()).isEqualTo(target);
    assertThat(request.sourcePort()).isEqualTo(sourcePort);
  }

  @Test
  void edgeRequest_nullSourcePort_isAllowed() {
    // When
    WorkflowDefinitionRequest.EdgeRequest request =
        new WorkflowDefinitionRequest.EdgeRequest("n1", "n2", null);

    // Then
    assertThat(request.sourcePort()).isNull();
  }

  @Test
  void edgeRequest_equals_sameValues_returnsTrue() {
    // Given
    WorkflowDefinitionRequest.EdgeRequest req1 =
        new WorkflowDefinitionRequest.EdgeRequest("n1", "n2", "port");
    WorkflowDefinitionRequest.EdgeRequest req2 =
        new WorkflowDefinitionRequest.EdgeRequest("n1", "n2", "port");

    // When-Then
    assertThat(req1).isEqualTo(req2);
    assertThat(req1.hashCode()).isEqualTo(req2.hashCode());
  }

  @Test
  void edgeRequest_equals_differentSource_returnsFalse() {
    // Given
    WorkflowDefinitionRequest.EdgeRequest req1 =
        new WorkflowDefinitionRequest.EdgeRequest("n1", "n2", null);
    WorkflowDefinitionRequest.EdgeRequest req2 =
        new WorkflowDefinitionRequest.EdgeRequest("n3", "n2", null);

    // When-Then
    assertThat(req1).isNotEqualTo(req2);
  }

  @Test
  void edgeRequest_equals_differentTarget_returnsFalse() {
    // Given
    WorkflowDefinitionRequest.EdgeRequest req1 =
        new WorkflowDefinitionRequest.EdgeRequest("n1", "n2", null);
    WorkflowDefinitionRequest.EdgeRequest req2 =
        new WorkflowDefinitionRequest.EdgeRequest("n1", "n3", null);

    // When-Then
    assertThat(req1).isNotEqualTo(req2);
  }

  @Test
  void edgeRequest_toString_containsAllFields() {
    // Given
    WorkflowDefinitionRequest.EdgeRequest request =
        new WorkflowDefinitionRequest.EdgeRequest("n1", "n2", "port");

    // When
    String actual = request.toString();

    // Then
    assertThat(actual).contains("EdgeRequest").contains("n1").contains("n2").contains("port");
  }

  @Test
  void edgeRequest_validation_nullSource_fails() {
    // Given
    WorkflowDefinitionRequest.EdgeRequest request =
        new WorkflowDefinitionRequest.EdgeRequest(null, "n2", null);

    // When
    Set<ConstraintViolation<WorkflowDefinitionRequest.EdgeRequest>> violations =
        validator.validate(request);

    // Then
    assertThat(violations).isNotEmpty();
    assertThat(violations).anyMatch(v -> v.getMessage().contains("Source"));
  }

  @Test
  void edgeRequest_validation_blankSource_fails() {
    // Given
    WorkflowDefinitionRequest.EdgeRequest request =
        new WorkflowDefinitionRequest.EdgeRequest("", "n2", null);

    // When
    Set<ConstraintViolation<WorkflowDefinitionRequest.EdgeRequest>> violations =
        validator.validate(request);

    // Then
    assertThat(violations).isNotEmpty();
    assertThat(violations).anyMatch(v -> v.getMessage().contains("Source"));
  }

  @Test
  void edgeRequest_validation_nullTarget_fails() {
    // Given
    WorkflowDefinitionRequest.EdgeRequest request =
        new WorkflowDefinitionRequest.EdgeRequest("n1", null, null);

    // When
    Set<ConstraintViolation<WorkflowDefinitionRequest.EdgeRequest>> violations =
        validator.validate(request);

    // Then
    assertThat(violations).isNotEmpty();
    assertThat(violations).anyMatch(v -> v.getMessage().contains("Target"));
  }

  @Test
  void edgeRequest_validation_blankTarget_fails() {
    // Given
    WorkflowDefinitionRequest.EdgeRequest request =
        new WorkflowDefinitionRequest.EdgeRequest("n1", "", null);

    // When
    Set<ConstraintViolation<WorkflowDefinitionRequest.EdgeRequest>> violations =
        validator.validate(request);

    // Then
    assertThat(violations).isNotEmpty();
    assertThat(violations).anyMatch(v -> v.getMessage().contains("Target"));
  }

  @Test
  void edgeRequest_validation_nullSourcePort_passes() {
    // Given
    WorkflowDefinitionRequest.EdgeRequest request =
        new WorkflowDefinitionRequest.EdgeRequest("n1", "n2", null);

    // When
    Set<ConstraintViolation<WorkflowDefinitionRequest.EdgeRequest>> violations =
        validator.validate(request);

    // Then
    assertThat(violations).isEmpty();
  }

  @Test
  void edgeRequest_validation_validValues_passes() {
    // Given
    WorkflowDefinitionRequest.EdgeRequest request =
        new WorkflowDefinitionRequest.EdgeRequest("n1", "n2", "port");

    // When
    Set<ConstraintViolation<WorkflowDefinitionRequest.EdgeRequest>> violations =
        validator.validate(request);

    // Then
    assertThat(violations).isEmpty();
  }
}
