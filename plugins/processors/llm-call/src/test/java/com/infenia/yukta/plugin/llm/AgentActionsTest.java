// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.CommentRequired")
@NoArgsConstructor
class AgentActionsTest {

  @Test
  void plainTextHasNoActions() {
    final var parsed = AgentActions.parse("  hello  ");
    assertThat(parsed.content()).isEqualTo("hello");
    assertThat(parsed.actions()).isEmpty();
  }

  @Test
  void extractsJsonBlocksAndStripsThem() {
    final var parsed =
        AgentActions.parse(
            "Sure.<agent_action>{\"name\":\"add\",\"qty\":2}</agent_action> Done."
                + "\n<agent_action>\n{\"name\":\"pay\"}\n</agent_action>");
    assertThat(parsed.content()).isEqualTo("Sure. Done.");
    assertThat(parsed.actions())
        .containsExactly(Map.of("name", "add", "qty", 2), Map.of("name", "pay"));
  }

  @Test
  void keepsInvalidJsonBlockAsRawText() {
    final var parsed = AgentActions.parse("<agent_action>not json</agent_action>");
    assertThat(parsed.content()).isEmpty();
    assertThat(parsed.actions()).containsExactly("not json");
  }

  @Test
  void nativeToolCallsBecomeActions_withParsedOrRawArguments() {
    assertThat(
            AgentActions.fromToolCall(new ToolCallRequest("c1", "weather", "{\"city\":\"Pune\"}")))
        .isEqualTo(Map.of("tool", "weather", "arguments", Map.of("city", "Pune"), "id", "c1"));
    assertThat(AgentActions.fromToolCall(new ToolCallRequest("c2", "echo", "not json")))
        .isEqualTo(Map.of("tool", "echo", "arguments", "not json", "id", "c2"));
  }
}
