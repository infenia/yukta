// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.llm;

import static org.assertj.core.api.Assertions.assertThat;

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@NoArgsConstructor
@SuppressWarnings("PMD.CommentRequired")
class ChatTurnTest {

  @Test
  void missingToolCallsAreEmpty() {
    assertThat(new ChatTurn("user", "x", null, null, null).toolCalls()).isEmpty();
    assertThat(new ChatTurn("user", "x").toolCalls()).isEmpty();
  }
}
