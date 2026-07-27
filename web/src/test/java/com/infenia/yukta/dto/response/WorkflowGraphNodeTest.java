// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.plugin.core.PluginCategory;
import com.infenia.yukta.plugin.core.UiDesign;
import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for WorkflowGraphNode. */
@NoArgsConstructor
class WorkflowGraphNodeTest {

  /** Node identifier constant. */
  private static final String NODE_ID = "data-ingress";

  /** Plugin type constant. */
  private static final String TYPE = "ingress-v1";

  /** Description constant. */
  private static final String DESCRIPTION = "Ingest data";

  /** Default output port constant. */
  private static final String DEFAULT_PORT = "default";

  @Test
  void constructor_validInputs_createsRecord() {
    // Given
    final UiDesign uiDesign = new UiDesign("<div>{{nodeId}}</div>", 200, 80);

    // When
    final WorkflowGraphNode node =
        new WorkflowGraphNode(
            NODE_ID,
            TYPE,
            PluginCategory.TRIGGER,
            DESCRIPTION,
            uiDesign,
            List.of(DEFAULT_PORT, "error"));

    // Then
    assertThat(node.nodeId()).isEqualTo(NODE_ID);
    assertThat(node.type()).isEqualTo(TYPE);
    assertThat(node.category()).isEqualTo(PluginCategory.TRIGGER);
    assertThat(node.description()).isEqualTo(DESCRIPTION);
    assertThat(node.uiDesign()).isEqualTo(uiDesign);
    assertThat(node.outputPorts()).containsExactly(DEFAULT_PORT, "error");
  }

  @Test
  void constructor_nullPluginMetadata_allowsUnknownPluginType() {
    // Given-When
    final WorkflowGraphNode node =
        new WorkflowGraphNode(NODE_ID, TYPE, null, null, null, List.of(DEFAULT_PORT));

    // Then
    assertThat(node.category()).isNull();
    assertThat(node.description()).isNull();
    assertThat(node.uiDesign()).isNull();
  }

  @Test
  void constructor_withNullOutputPorts_convertsToEmptyList() {
    // Given-When
    final WorkflowGraphNode node =
        new WorkflowGraphNode(NODE_ID, TYPE, PluginCategory.PROCESSOR, DESCRIPTION, null, null);

    // Then
    assertThat(node.outputPorts()).isEmpty();
    assertThat(node.outputPorts()).isUnmodifiable();
  }

  @Test
  void outputPorts_modificationAttempt_throwsUnsupportedOperationException() {
    // Given
    final WorkflowGraphNode node =
        new WorkflowGraphNode(
            NODE_ID, TYPE, PluginCategory.PROCESSOR, DESCRIPTION, null, List.of(DEFAULT_PORT));

    // When-Then
    assertThatThrownBy(() -> node.outputPorts().add("extra"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void equals_sameValues_returnsTrue() {
    // Given
    final WorkflowGraphNode node1 =
        new WorkflowGraphNode(
            NODE_ID, TYPE, PluginCategory.TERMINAL, DESCRIPTION, null, List.of(DEFAULT_PORT));
    final WorkflowGraphNode node2 =
        new WorkflowGraphNode(
            NODE_ID, TYPE, PluginCategory.TERMINAL, DESCRIPTION, null, List.of(DEFAULT_PORT));

    // When-Then
    assertThat(node1).isEqualTo(node2);
  }

  @Test
  void verifyToStringContainsRelevantFieldValues() {
    // Given
    final WorkflowGraphNode node =
        new WorkflowGraphNode(NODE_ID, TYPE, PluginCategory.TRIGGER, DESCRIPTION, null, List.of());

    // When
    final String actual = node.toString();

    // Then
    assertThat(actual).contains("WorkflowGraphNode").contains(NODE_ID).contains(TYPE);
  }
}
