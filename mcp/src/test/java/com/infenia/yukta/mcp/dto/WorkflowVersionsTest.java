// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@NoArgsConstructor
@SuppressWarnings("PMD.CommentRequired")
class WorkflowVersionsTest {

  @Test
  void missingListsAreEmpty() {
    assertThat(new WorkflowVersions("s", "w", null).versions()).isEmpty();
  }

  @Test
  void versionsAreCopied() {
    assertThat(new WorkflowVersions("s", "w", List.of()).versions()).isEmpty();
  }
}
