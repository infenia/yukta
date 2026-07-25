// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Tests for WorkflowSummary. */
@SuppressWarnings("PMD.AtLeastOneConstructor")
class WorkflowSummaryTest {

  /** Workflow ID constant. */
  private static final String WF_ID_1 = "wf1";

  /** Workflow ID constant. */
  private static final String WF_ID_2 = "wf2";

  /** Description constant. */
  private static final String DESC = "desc";

  /** Test description constant. */
  private static final String TEST_DESC = "test";

  /** Status constant. */
  private static final String STATUS_SUCCESS = "SUCCESS";

  @Test
  void constructor_validInputs_createsRecord() {
    final WorkflowSummary summary = new WorkflowSummary(WF_ID_1, DESC, 5, 4, STATUS_SUCCESS);

    assertThat(summary.workflowId()).isEqualTo(WF_ID_1);
    assertThat(summary.description()).isEqualTo(DESC);
    assertThat(summary.nodeCount()).isEqualTo(5);
    assertThat(summary.edgeCount()).isEqualTo(4);
    assertThat(summary.status()).isEqualTo(STATUS_SUCCESS);
  }

  @Test
  void constructor_withNullStatus_preservesNull() {
    final WorkflowSummary summary = new WorkflowSummary(WF_ID_2, TEST_DESC, 2, 1, null);

    assertThat(summary.status()).isNull();
  }

  @Test
  void equals_sameValues_returnsTrue() {
    final WorkflowSummary summary1 = new WorkflowSummary(WF_ID_1, DESC, 5, 4, STATUS_SUCCESS);
    final WorkflowSummary summary2 = new WorkflowSummary(WF_ID_1, DESC, 5, 4, STATUS_SUCCESS);

    assertThat(summary1).isEqualTo(summary2);
  }

  @Test
  void equals_differentValues_returnsFalse() {
    final WorkflowSummary summary1 = new WorkflowSummary(WF_ID_1, DESC, 5, 4, STATUS_SUCCESS);
    final WorkflowSummary summary2 = new WorkflowSummary(WF_ID_2, DESC, 5, 4, STATUS_SUCCESS);

    assertThat(summary1).isNotEqualTo(summary2);
  }

  @Test
  void hashCode_consistent() {
    final WorkflowSummary summary1 = new WorkflowSummary(WF_ID_1, DESC, 5, 4, STATUS_SUCCESS);
    final WorkflowSummary summary2 = new WorkflowSummary(WF_ID_1, DESC, 5, 4, STATUS_SUCCESS);

    assertThat(summary1.hashCode()).isEqualTo(summary2.hashCode());
  }

  @Test
  void summaryToStringContainsAllFields() {
    final WorkflowSummary summary = new WorkflowSummary(WF_ID_1, DESC, 5, 4, STATUS_SUCCESS);
    final String str = summary.toString();

    assertThat(str)
        .contains("WorkflowSummary")
        .contains(WF_ID_1)
        .contains(DESC)
        .contains("5")
        .contains("4")
        .contains(STATUS_SUCCESS);
  }
}
