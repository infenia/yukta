// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Tests for WorkflowSummaries. */
@SuppressWarnings("PMD.AtLeastOneConstructor")
class WorkflowSummariesTest {

  /** Workflow ID constant for testing. */
  private static final String WF_ID_1 = "wf1";

  /** Workflow ID constant for testing. */
  private static final String WF_ID_2 = "wf2";

  /** Workflow description constant. */
  private static final String DESC_1 = "desc1";

  /** Workflow description constant. */
  private static final String DESC_2 = "desc2";

  /** Workflow status constant. */
  private static final String STATUS_SUCCESS = "SUCCESS";

  @Test
  void constructor_validInputs_createsRecord() {
    final List<WorkflowSummary> workflows = new java.util.ArrayList<>();
    workflows.add(new WorkflowSummary(WF_ID_1, DESC_1, 5, 4, STATUS_SUCCESS));
    workflows.add(new WorkflowSummary(WF_ID_2, DESC_2, 3, 2, null));
    final WorkflowSummaries summaries = new WorkflowSummaries(workflows);

    workflows.clear();

    assertThat(summaries.workflows()).hasSize(2);
    assertThat(summaries.workflows().get(0).workflowId()).isEqualTo(WF_ID_1);
    assertThat(summaries.workflows().get(1).workflowId()).isEqualTo(WF_ID_2);
  }

  @Test
  void constructor_withNullWorkflows_convertsToEmptyList() {
    final WorkflowSummaries summaries = new WorkflowSummaries(null);

    assertThat(summaries.workflows()).isEmpty();
    assertThat(summaries.workflows()).isUnmodifiable();
  }

  @Test
  void workflows_modificationAttempt_throwsUnsupportedOperationException() {
    final List<WorkflowSummary> workflows =
        List.of(new WorkflowSummary(WF_ID_1, DESC_1, 5, 4, STATUS_SUCCESS));
    final WorkflowSummaries summaries = new WorkflowSummaries(workflows);

    assertThatThrownBy(
            () -> summaries.workflows().add(new WorkflowSummary(WF_ID_2, DESC_2, 3, 2, null)))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void equals_sameValues_returnsTrue() {
    final List<WorkflowSummary> workflows =
        List.of(new WorkflowSummary(WF_ID_1, DESC_1, 5, 4, STATUS_SUCCESS));
    final WorkflowSummaries summaries1 = new WorkflowSummaries(workflows);
    final WorkflowSummaries summaries2 = new WorkflowSummaries(workflows);

    assertThat(summaries1).isEqualTo(summaries2);
  }

  @Test
  void equals_differentValues_returnsFalse() {
    final WorkflowSummaries summaries1 =
        new WorkflowSummaries(List.of(new WorkflowSummary(WF_ID_1, DESC_1, 5, 4, STATUS_SUCCESS)));
    final WorkflowSummaries summaries2 =
        new WorkflowSummaries(List.of(new WorkflowSummary(WF_ID_2, DESC_2, 3, 2, null)));

    assertThat(summaries1).isNotEqualTo(summaries2);
  }

  @Test
  void summariesToStringContainsFields() {
    final WorkflowSummaries summaries =
        new WorkflowSummaries(List.of(new WorkflowSummary(WF_ID_1, DESC_1, 5, 4, STATUS_SUCCESS)));
    final String str = summaries.toString();

    assertThat(str).contains("WorkflowSummaries").contains("workflows");
  }
}
