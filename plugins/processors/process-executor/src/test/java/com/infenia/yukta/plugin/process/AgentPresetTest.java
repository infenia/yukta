// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Reading a harness preset from its JSON. */
@NoArgsConstructor
class AgentPresetTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static ObjectNode claudeJson() throws IOException {
    try (var in = AgentPresetTest.class.getResourceAsStream("/agent-harnesses/claude-code.json")) {
      return (ObjectNode) JSON.readTree(in);
    }
  }

  @Test
  void presetWithoutId_isRefused() throws IOException {
    final ObjectNode root = claudeJson();
    root.remove("id");

    assertThatThrownBy(() -> AgentPreset.parse(root))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("agent harness preset: 'id' is required");
  }

  @Test
  void nullInteractiveBlock_meansTheHarnessCannotConverse() throws IOException {
    final ObjectNode root = claudeJson();
    root.set("interactive", JSON.nullNode());

    assertThat(AgentPreset.parse(root).interactive()).isNull();
  }

  @Test
  void theRecordsCopyWhatTheyAreGiven() throws IOException {
    final var output = AgentPreset.parse(claudeJson()).output();

    assertThat(output.live()).isNotEmpty().isUnmodifiable();
    assertThat(output.resultWhen()).containsEntry("type", "result").isUnmodifiable();
  }

  @Test
  void harnessesLoadWithoutOperatorDirectory() throws IOException {
    assertThat(new AgentHarnesses(JSON, null).all().stream().map(AgentPreset::id).toList())
        .contains("claude-code", "codex")
        .isInstanceOf(List.class);
  }
}
