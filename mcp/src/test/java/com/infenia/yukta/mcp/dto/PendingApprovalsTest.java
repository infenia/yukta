// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

import static org.assertj.core.api.Assertions.assertThat;

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@NoArgsConstructor
@SuppressWarnings("PMD.CommentRequired")
class PendingApprovalsTest {

  @Test
  void missingListsAreEmpty() {
    assertThat(new PendingApprovals("s", null).approvals()).isEmpty();
    assertThat(new WorkflowVersions("s", "w", null).versions()).isEmpty();
  }
}
