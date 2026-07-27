// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for WorkflowGraphEdge. */
@NoArgsConstructor
class WorkflowGraphEdgeTest {

  /** Source node identifier constant. */
  private static final String SOURCE = "data-ingress";

  /** Target node identifier constant. */
  private static final String TARGET = "vectorize-batch";

  @Test
  void constructor_validInputs_createsRecord() {
    // Given-When
    final WorkflowGraphEdge edge = new WorkflowGraphEdge(SOURCE, TARGET, "default");

    // Then
    assertThat(edge.source()).isEqualTo(SOURCE);
    assertThat(edge.target()).isEqualTo(TARGET);
    assertThat(edge.sourcePort()).isEqualTo("default");
  }

  @Test
  void constructor_nullSourcePort_routesAllMessages() {
    // Given-When
    final WorkflowGraphEdge edge = new WorkflowGraphEdge(SOURCE, TARGET, null);

    // Then
    assertThat(edge.sourcePort()).isNull();
  }

  @Test
  void equals_sameValues_returnsTrue() {
    // Given-When-Then
    assertThat(new WorkflowGraphEdge(SOURCE, TARGET, null))
        .isEqualTo(new WorkflowGraphEdge(SOURCE, TARGET, null));
  }

  @Test
  void equals_differentValues_returnsFalse() {
    // Given-When-Then
    assertThat(new WorkflowGraphEdge(SOURCE, TARGET, "a"))
        .isNotEqualTo(new WorkflowGraphEdge(SOURCE, TARGET, "b"));
  }

  @Test
  void verifyToStringContainsRelevantFieldValues() {
    // Given-When
    final String actual = new WorkflowGraphEdge(SOURCE, TARGET, null).toString();

    // Then
    assertThat(actual).contains("WorkflowGraphEdge").contains(SOURCE).contains(TARGET);
  }
}
