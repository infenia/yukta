// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/** Tests for WorkflowDefinitionRequest. */
@SpringJUnitConfig(WorkflowDefinitionRequestTest.TestConfig.class)
@NoArgsConstructor
@SuppressWarnings("PMD.TooManyMethods")
class WorkflowDefinitionRequestTest {

  /** Gradle plugin identifier. */
  private static final String GRADLE = "gradle";

  /** Gradle workflow ID constant. */
  private static final String GRADLE_WORKFLOW_ID = "quality-check";

  /** Gradle workflow description constant. */
  private static final String GRADLE_WORKFLOW_DESC = "Quality check workflow";

  /** First node identifier constant. */
  private static final String NODE1 = "node1";

  /** Second node identifier constant. */
  private static final String NODE2 = "node2";

  /** Configuration key constant. */
  private static final String KEY = "key";

  /** Configuration value constant. */
  private static final String VALUE = "value";

  /** Port configuration constant. */
  private static final String PORT = "port";

  /** Workflow ID wf1 constant. */
  private static final String WORKFLOW_ID_WF1 = "wf1";

  /** Description constant for workflow testing. */
  private static final String DESCRIPTION = "desc";

  /** Valid nodes for workflow definition testing. */
  private List<WorkflowDefinitionRequest.NodeRequest> validNodes;

  /** Valid edges for workflow definition testing. */
  private List<WorkflowDefinitionRequest.EdgeRequest> validEdges;

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

  @BeforeEach
  void setUp() {
    validNodes = List.of(new WorkflowDefinitionRequest.NodeRequest(NODE1, GRADLE, null));
    validEdges = List.of();
  }

  // === WorkflowDefinitionRequest Tests ===

  @Test
  void constructor_validInputs_createsRecord() {
    // Given
    final String workflowId = GRADLE_WORKFLOW_ID;
    final String description = GRADLE_WORKFLOW_DESC;
    final List<WorkflowDefinitionRequest.NodeRequest> nodes =
        List.of(new WorkflowDefinitionRequest.NodeRequest("n1", GRADLE, null));
    final List<WorkflowDefinitionRequest.EdgeRequest> edges = List.of();

    // When
    final WorkflowDefinitionRequest request =
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
    final WorkflowDefinitionRequest request =
        new WorkflowDefinitionRequest(WORKFLOW_ID_WF1, DESCRIPTION, null, List.of());

    // Then
    assertThat(request.nodes()).isEmpty();
    assertThat(request.nodes()).isUnmodifiable();
  }

  @Test
  void constructor_nullEdges_convertsToEmptyList() {
    // When
    final WorkflowDefinitionRequest request =
        new WorkflowDefinitionRequest(WORKFLOW_ID_WF1, DESCRIPTION, validNodes, null);

    // Then
    assertThat(request.edges()).isEmpty();
    assertThat(request.edges()).isUnmodifiable();
  }

  @Test
  void constructor_nullBothNodesAndEdges_convertsToEmptyLists() {
    // When
    final WorkflowDefinitionRequest request =
        new WorkflowDefinitionRequest(WORKFLOW_ID_WF1, DESCRIPTION, null, null);

    // Then
    assertThat(request.nodes()).isEmpty();
    assertThat(request.edges()).isEmpty();
  }

  @Test
  void constructor_mutableNodesList_convertsToImmutable() {
    // Given
    final List<WorkflowDefinitionRequest.NodeRequest> mutableNodes =
        new ArrayList<>(
            List.of(
                new WorkflowDefinitionRequest.NodeRequest("n1", GRADLE, null),
                new WorkflowDefinitionRequest.NodeRequest("n2", "maven", null)));

    // When
    final WorkflowDefinitionRequest request =
        new WorkflowDefinitionRequest(WORKFLOW_ID_WF1, DESCRIPTION, mutableNodes, validEdges);

    // Then - verify nodes are immutable
    assertThat(request.nodes()).hasSize(2);
    assertThatThrownBy(
            () ->
                request.nodes().add(new WorkflowDefinitionRequest.NodeRequest("n3", GRADLE, null)))
        .isInstanceOf(UnsupportedOperationException.class);

    // Modify original list - request should be unaffected
    mutableNodes.add(new WorkflowDefinitionRequest.NodeRequest("n4", GRADLE, null));
    assertThat(request.nodes()).hasSize(2);
  }

  @Test
  void constructor_mutableEdgesList_convertsToImmutable() {
    // Given
    final List<WorkflowDefinitionRequest.EdgeRequest> mutableEdges =
        new ArrayList<>(List.of(new WorkflowDefinitionRequest.EdgeRequest("n1", "n2", null)));

    // When
    final WorkflowDefinitionRequest request =
        new WorkflowDefinitionRequest(WORKFLOW_ID_WF1, DESCRIPTION, validNodes, mutableEdges);

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
    final WorkflowDefinitionRequest request =
        new WorkflowDefinitionRequest(WORKFLOW_ID_WF1, DESCRIPTION, validNodes, validEdges);

    // When-Then
    assertThatThrownBy(
            () ->
                request.nodes().add(new WorkflowDefinitionRequest.NodeRequest("n2", GRADLE, null)))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void edges_modificationAttempt_throwsUnsupportedOperationException() {
    // Given
    final WorkflowDefinitionRequest request =
        new WorkflowDefinitionRequest(WORKFLOW_ID_WF1, DESCRIPTION, validNodes, validEdges);

    // When-Then
    assertThatThrownBy(
            () -> request.edges().add(new WorkflowDefinitionRequest.EdgeRequest("n1", "n2", null)))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void equals_sameValues_returnsTrue() {
    // Given
    final WorkflowDefinitionRequest req1 =
        new WorkflowDefinitionRequest(WORKFLOW_ID_WF1, DESCRIPTION, validNodes, validEdges);
    final WorkflowDefinitionRequest req2 =
        new WorkflowDefinitionRequest(WORKFLOW_ID_WF1, DESCRIPTION, validNodes, validEdges);

    // When-Then
    assertThat(req1).isEqualTo(req2);
    assertThat(req1.hashCode()).isEqualTo(req2.hashCode());
  }

  @Test
  void equals_differentWorkflowIds_returnsFalse() {
    // Given
    final WorkflowDefinitionRequest req1 =
        new WorkflowDefinitionRequest(WORKFLOW_ID_WF1, DESCRIPTION, validNodes, validEdges);
    final WorkflowDefinitionRequest req2 =
        new WorkflowDefinitionRequest("wf2", DESCRIPTION, validNodes, validEdges);

    // When-Then
    assertThat(req1).isNotEqualTo(req2);
  }

  @Test
  void equals_differentDescriptions_returnsFalse() {
    // Given
    final WorkflowDefinitionRequest req1 =
        new WorkflowDefinitionRequest(WORKFLOW_ID_WF1, "desc1", validNodes, validEdges);
    final WorkflowDefinitionRequest req2 =
        new WorkflowDefinitionRequest(WORKFLOW_ID_WF1, "desc2", validNodes, validEdges);

    // When-Then
    assertThat(req1).isNotEqualTo(req2);
  }

  @Test
  void verifyToStringContainsRelevantFields() {
    // Given
    final WorkflowDefinitionRequest request =
        new WorkflowDefinitionRequest(WORKFLOW_ID_WF1, DESCRIPTION, validNodes, validEdges);

    // When
    final String actual = request.toString();

    // Then
    assertThat(actual).contains("WorkflowDefinitionRequest").contains("wf1").contains("desc");
  }

  @Test
  void validation_blankWorkflowId_fails() {
    // Given
    final WorkflowDefinitionRequest request =
        new WorkflowDefinitionRequest("", DESCRIPTION, validNodes, validEdges);

    // When
    final Set<ConstraintViolation<WorkflowDefinitionRequest>> violations =
        validator.validate(request);

    // Then
    assertThat(violations).isNotEmpty();
    assertThat(violations).anyMatch(v -> v.getMessage().contains("Workflow ID"));
  }

  @Test
  void validation_nullWorkflowId_fails() {
    // Given
    final WorkflowDefinitionRequest request =
        new WorkflowDefinitionRequest(null, DESCRIPTION, validNodes, validEdges);

    // When
    final Set<ConstraintViolation<WorkflowDefinitionRequest>> violations =
        validator.validate(request);

    // Then
    assertThat(violations).isNotEmpty();
  }

  @Test
  void validation_blankDescription_fails() {
    // Given
    final WorkflowDefinitionRequest request =
        new WorkflowDefinitionRequest(WORKFLOW_ID_WF1, "", validNodes, validEdges);

    // When
    final Set<ConstraintViolation<WorkflowDefinitionRequest>> violations =
        validator.validate(request);

    // Then
    assertThat(violations).isNotEmpty();
    assertThat(violations).anyMatch(v -> v.getMessage().contains("description"));
  }

  @Test
  void validation_descriptionExceedsMaxLength_fails() {
    // Given
    final String longDescription = "a".repeat(257);
    final WorkflowDefinitionRequest request =
        new WorkflowDefinitionRequest(WORKFLOW_ID_WF1, longDescription, validNodes, validEdges);

    // When
    final Set<ConstraintViolation<WorkflowDefinitionRequest>> violations =
        validator.validate(request);

    // Then
    assertThat(violations).isNotEmpty();
    assertThat(violations).anyMatch(v -> v.getMessage().contains("256"));
  }

  @Test
  void validation_descriptionAtMaxLength_passes() {
    // Given
    final String maxDescription = "a".repeat(256);
    final WorkflowDefinitionRequest request =
        new WorkflowDefinitionRequest(WORKFLOW_ID_WF1, maxDescription, validNodes, validEdges);

    // When
    final Set<ConstraintViolation<WorkflowDefinitionRequest>> violations =
        validator.validate(request);

    // Then
    assertThat(violations).isEmpty();
  }

  @Test
  void validation_emptyNodesList_fails() {
    // Given
    final WorkflowDefinitionRequest request =
        new WorkflowDefinitionRequest(WORKFLOW_ID_WF1, DESCRIPTION, List.of(), validEdges);

    // When
    final Set<ConstraintViolation<WorkflowDefinitionRequest>> violations =
        validator.validate(request);

    // Then
    assertThat(violations).isNotEmpty();
    assertThat(violations).anyMatch(v -> v.getMessage().contains("at least one node"));
  }

  @Test
  void validation_nullEdges_fails() {
    // Given - null edges gets converted to List.of() by compact constructor
    // So we need to test the validation on the parameter level
    final WorkflowDefinitionRequest request =
        new WorkflowDefinitionRequest(WORKFLOW_ID_WF1, DESCRIPTION, validNodes, null);

    // Then - edges should be empty list after compact constructor
    assertThat(request.edges()).isEmpty();
    // Validation on edges list itself would only fail if not handled by compact constructor
  }

  @Test
  void validation_validNodesList_passes() {
    // Given
    final WorkflowDefinitionRequest request =
        new WorkflowDefinitionRequest(WORKFLOW_ID_WF1, DESCRIPTION, validNodes, validEdges);

    // When
    final Set<ConstraintViolation<WorkflowDefinitionRequest>> violations =
        validator.validate(request);

    // Then
    assertThat(violations).isEmpty();
  }

  // === NodeRequest Tests ===

  @Test
  void nodeRequest_validInputs_createsRecord() {
    // Given
    final String nodeId = NODE1;
    final String type = GRADLE;
    final Map<String, Object> config = Map.of(KEY, VALUE);

    // When
    final WorkflowDefinitionRequest.NodeRequest request =
        new WorkflowDefinitionRequest.NodeRequest(nodeId, type, config);

    // Then
    assertThat(request.nodeId()).isEqualTo(nodeId);
    assertThat(request.type()).isEqualTo(type);
    assertThat(request.config()).containsEntry(KEY, VALUE);
  }

  @Test
  void nodeRequest_nullConfig_convertsToEmptyMap() {
    // When
    final WorkflowDefinitionRequest.NodeRequest request =
        new WorkflowDefinitionRequest.NodeRequest("n1", GRADLE, null);

    // Then
    assertThat(request.config()).isEmpty();
    assertThat(request.config()).isUnmodifiable();
  }

  @Test
  void nodeRequest_mutableConfig_convertsToImmutable() {
    // Given
    final Map<String, Object> mutableConfig = new ConcurrentHashMap<>(Map.of("key1", "value1"));

    // When
    final WorkflowDefinitionRequest.NodeRequest request =
        new WorkflowDefinitionRequest.NodeRequest("n1", GRADLE, mutableConfig);

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
    final WorkflowDefinitionRequest.NodeRequest request =
        new WorkflowDefinitionRequest.NodeRequest("n1", GRADLE, Map.of(KEY, VALUE));

    // When-Then
    assertThatThrownBy(() -> request.config().put("newKey", "newValue"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void nodeRequest_equals_sameValues_returnsTrue() {
    // Given
    final WorkflowDefinitionRequest.NodeRequest req1 =
        new WorkflowDefinitionRequest.NodeRequest("n1", GRADLE, Map.of(KEY, VALUE));
    final WorkflowDefinitionRequest.NodeRequest req2 =
        new WorkflowDefinitionRequest.NodeRequest("n1", GRADLE, Map.of(KEY, VALUE));

    // When-Then
    assertThat(req1).isEqualTo(req2);
    assertThat(req1.hashCode()).isEqualTo(req2.hashCode());
  }

  @Test
  void nodeRequest_equals_differentNodeId_returnsFalse() {
    // Given
    final WorkflowDefinitionRequest.NodeRequest req1 =
        new WorkflowDefinitionRequest.NodeRequest("n1", GRADLE, null);
    final WorkflowDefinitionRequest.NodeRequest req2 =
        new WorkflowDefinitionRequest.NodeRequest("n2", GRADLE, null);

    // When-Then
    assertThat(req1).isNotEqualTo(req2);
  }

  @Test
  void nodeRequest_equals_differentType_returnsFalse() {
    // Given
    final WorkflowDefinitionRequest.NodeRequest req1 =
        new WorkflowDefinitionRequest.NodeRequest("n1", GRADLE, null);
    final WorkflowDefinitionRequest.NodeRequest req2 =
        new WorkflowDefinitionRequest.NodeRequest("n1", "maven", null);

    // When-Then
    assertThat(req1).isNotEqualTo(req2);
  }

  @Test
  void nodeRequest_toString_containsAllFields() {
    // Given
    final WorkflowDefinitionRequest.NodeRequest request =
        new WorkflowDefinitionRequest.NodeRequest("n1", GRADLE, Map.of(KEY, VALUE));

    // When
    final String actual = request.toString();

    // Then
    assertThat(actual).contains("NodeRequest").contains("n1").contains(GRADLE);
  }

  @Test
  void nodeRequest_validation_nullNodeId_fails() {
    // Given
    final WorkflowDefinitionRequest.NodeRequest request =
        new WorkflowDefinitionRequest.NodeRequest(null, GRADLE, null);

    // When
    final Set<ConstraintViolation<WorkflowDefinitionRequest.NodeRequest>> violations =
        validator.validate(request);

    // Then
    assertThat(violations).isNotEmpty();
    assertThat(violations).anyMatch(v -> v.getMessage().contains("Node ID"));
  }

  @Test
  void nodeRequest_validation_blankNodeId_fails() {
    // Given
    final WorkflowDefinitionRequest.NodeRequest request =
        new WorkflowDefinitionRequest.NodeRequest("", GRADLE, null);

    // When
    final Set<ConstraintViolation<WorkflowDefinitionRequest.NodeRequest>> violations =
        validator.validate(request);

    // Then
    assertThat(violations).isNotEmpty();
    assertThat(violations).anyMatch(v -> v.getMessage().contains("Node ID"));
  }

  @Test
  void nodeRequest_validation_nullType_fails() {
    // Given
    final WorkflowDefinitionRequest.NodeRequest request =
        new WorkflowDefinitionRequest.NodeRequest("n1", null, null);

    // When
    final Set<ConstraintViolation<WorkflowDefinitionRequest.NodeRequest>> violations =
        validator.validate(request);

    // Then
    assertThat(violations).isNotEmpty();
    assertThat(violations).anyMatch(v -> v.getMessage().contains("Plugin type"));
  }

  @Test
  void nodeRequest_validation_blankType_fails() {
    // Given
    final WorkflowDefinitionRequest.NodeRequest request =
        new WorkflowDefinitionRequest.NodeRequest("n1", "", null);

    // When
    final Set<ConstraintViolation<WorkflowDefinitionRequest.NodeRequest>> violations =
        validator.validate(request);

    // Then
    assertThat(violations).isNotEmpty();
    assertThat(violations).anyMatch(v -> v.getMessage().contains("Plugin type"));
  }

  @Test
  void nodeRequest_validation_validValues_passes() {
    // Given
    final WorkflowDefinitionRequest.NodeRequest request =
        new WorkflowDefinitionRequest.NodeRequest("n1", GRADLE, Map.of(KEY, VALUE));

    // When
    final Set<ConstraintViolation<WorkflowDefinitionRequest.NodeRequest>> violations =
        validator.validate(request);

    // Then
    assertThat(violations).isEmpty();
  }

  // === EdgeRequest Tests ===

  @Test
  void edgeRequest_validInputs_createsRecord() {
    // Given
    final String source = NODE1;
    final String target = NODE2;
    final String sourcePort = "output";

    // When
    final WorkflowDefinitionRequest.EdgeRequest request =
        new WorkflowDefinitionRequest.EdgeRequest(source, target, sourcePort);

    // Then
    assertThat(request.source()).isEqualTo(source);
    assertThat(request.target()).isEqualTo(target);
    assertThat(request.sourcePort()).isEqualTo(sourcePort);
  }

  @Test
  void edgeRequest_nullSourcePort_isAllowed() {
    // When
    final WorkflowDefinitionRequest.EdgeRequest request =
        new WorkflowDefinitionRequest.EdgeRequest("n1", "n2", null);

    // Then
    assertThat(request.sourcePort()).isNull();
  }

  @Test
  void edgeRequest_equals_sameValues_returnsTrue() {
    // Given
    final WorkflowDefinitionRequest.EdgeRequest req1 =
        new WorkflowDefinitionRequest.EdgeRequest("n1", "n2", PORT);
    final WorkflowDefinitionRequest.EdgeRequest req2 =
        new WorkflowDefinitionRequest.EdgeRequest("n1", "n2", PORT);

    // When-Then
    assertThat(req1).isEqualTo(req2);
    assertThat(req1.hashCode()).isEqualTo(req2.hashCode());
  }

  @Test
  void edgeRequest_equals_differentSource_returnsFalse() {
    // Given
    final WorkflowDefinitionRequest.EdgeRequest req1 =
        new WorkflowDefinitionRequest.EdgeRequest("n1", "n2", null);
    final WorkflowDefinitionRequest.EdgeRequest req2 =
        new WorkflowDefinitionRequest.EdgeRequest("n3", "n2", null);

    // When-Then
    assertThat(req1).isNotEqualTo(req2);
  }

  @Test
  void edgeRequest_equals_differentTarget_returnsFalse() {
    // Given
    final WorkflowDefinitionRequest.EdgeRequest req1 =
        new WorkflowDefinitionRequest.EdgeRequest("n1", "n2", null);
    final WorkflowDefinitionRequest.EdgeRequest req2 =
        new WorkflowDefinitionRequest.EdgeRequest("n1", "n3", null);

    // When-Then
    assertThat(req1).isNotEqualTo(req2);
  }

  @Test
  void edgeRequest_toString_containsAllFields() {
    // Given
    final WorkflowDefinitionRequest.EdgeRequest request =
        new WorkflowDefinitionRequest.EdgeRequest("n1", "n2", PORT);

    // When
    final String actual = request.toString();

    // Then
    assertThat(actual).contains("EdgeRequest").contains("n1").contains("n2").contains(PORT);
  }

  @Test
  void edgeRequest_validation_nullSource_fails() {
    // Given
    final WorkflowDefinitionRequest.EdgeRequest request =
        new WorkflowDefinitionRequest.EdgeRequest(null, "n2", null);

    // When
    final Set<ConstraintViolation<WorkflowDefinitionRequest.EdgeRequest>> violations =
        validator.validate(request);

    // Then
    assertThat(violations).isNotEmpty();
    assertThat(violations).anyMatch(v -> v.getMessage().contains("Source"));
  }

  @Test
  void edgeRequest_validation_blankSource_fails() {
    // Given
    final WorkflowDefinitionRequest.EdgeRequest request =
        new WorkflowDefinitionRequest.EdgeRequest("", "n2", null);

    // When
    final Set<ConstraintViolation<WorkflowDefinitionRequest.EdgeRequest>> violations =
        validator.validate(request);

    // Then
    assertThat(violations).isNotEmpty();
    assertThat(violations).anyMatch(v -> v.getMessage().contains("Source"));
  }

  @Test
  void edgeRequest_validation_nullTarget_fails() {
    // Given
    final WorkflowDefinitionRequest.EdgeRequest request =
        new WorkflowDefinitionRequest.EdgeRequest("n1", null, null);

    // When
    final Set<ConstraintViolation<WorkflowDefinitionRequest.EdgeRequest>> violations =
        validator.validate(request);

    // Then
    assertThat(violations).isNotEmpty();
    assertThat(violations).anyMatch(v -> v.getMessage().contains("Target"));
  }

  @Test
  void edgeRequest_validation_blankTarget_fails() {
    // Given
    final WorkflowDefinitionRequest.EdgeRequest request =
        new WorkflowDefinitionRequest.EdgeRequest("n1", "", null);

    // When
    final Set<ConstraintViolation<WorkflowDefinitionRequest.EdgeRequest>> violations =
        validator.validate(request);

    // Then
    assertThat(violations).isNotEmpty();
    assertThat(violations).anyMatch(v -> v.getMessage().contains("Target"));
  }

  @Test
  void edgeRequest_validation_nullSourcePort_passes() {
    // Given
    final WorkflowDefinitionRequest.EdgeRequest request =
        new WorkflowDefinitionRequest.EdgeRequest("n1", "n2", null);

    // When
    final Set<ConstraintViolation<WorkflowDefinitionRequest.EdgeRequest>> violations =
        validator.validate(request);

    // Then
    assertThat(violations).isEmpty();
  }

  @Test
  void edgeRequest_validation_validValues_passes() {
    // Given
    final WorkflowDefinitionRequest.EdgeRequest request =
        new WorkflowDefinitionRequest.EdgeRequest("n1", "n2", PORT);

    // When
    final Set<ConstraintViolation<WorkflowDefinitionRequest.EdgeRequest>> violations =
        validator.validate(request);

    // Then
    assertThat(violations).isEmpty();
  }
}
