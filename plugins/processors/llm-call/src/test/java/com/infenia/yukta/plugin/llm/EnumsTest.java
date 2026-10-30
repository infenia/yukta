// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.plugin.core.FailureMode;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@NoArgsConstructor
class EnumsTest {

  @Test
  void parsesProviders() {
    assertThat(Provider.from("openai")).isEqualTo(Provider.OPENAI);
    assertThat(Provider.from(" OpenAI-Compatible ")).isEqualTo(Provider.OPENAI);
    assertThat(Provider.from("anthropic")).isEqualTo(Provider.ANTHROPIC);
    assertThat(Provider.from("Ollama")).isEqualTo(Provider.OLLAMA);
  }

  @Test
  void rejectsBadProviders() {
    assertThatThrownBy(() -> Provider.from(null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Provider.from("gemini")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void parsesFailureModes() {
    assertThat(FailureMode.from(null)).isEqualTo(FailureMode.ERROR);
    assertThat(FailureMode.from(" continue ")).isEqualTo(FailureMode.CONTINUE);
    assertThatThrownBy(() -> FailureMode.from("x")).isInstanceOf(IllegalArgumentException.class);
  }
}
