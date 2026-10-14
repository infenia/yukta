// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for the behaviour described by {@link ToolApproval}. */
@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class ToolApprovalTest {

  @Test
  void required_matchesListedToolsAndStar() {
    assertThat(ToolApproval.required(Map.of("requireApproval", List.of("a")), "a")).isTrue();
    assertThat(ToolApproval.required(Map.of("requireApproval", List.of("a")), "b")).isFalse();
    assertThat(ToolApproval.required(Map.of("requireApproval", List.of("*")), "b")).isTrue();
    assertThat(ToolApproval.required(Map.of(), "a")).isFalse();
  }

  @Test
  void wait_isZeroWithoutToolsAndTheConfiguredTimeoutOtherwise() {
    assertThat(ToolApproval.wait(Map.of())).isEqualTo(Duration.ZERO);
    assertThat(ToolApproval.wait(Map.of("requireApproval", List.of()))).isEqualTo(Duration.ZERO);
    assertThat(
            ToolApproval.wait(Map.of("requireApproval", List.of("a"), "approvalTimeoutSeconds", 9)))
        .isEqualTo(Duration.ofSeconds(9));
  }
}
