// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.ai.AiBackend;
import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for {@link AiBackendList}. */
@NoArgsConstructor
class AiBackendListTest {

  @Test
  void nullBecomesEmptyAndListIsKept() {
    final AiBackend.Info info = new AiBackend.Info("A", "d", List.of());

    assertThat(new AiBackendList(null).backends()).isEmpty();
    assertThat(new AiBackendList(List.of(info)).backends()).containsExactly(info);
  }
}
