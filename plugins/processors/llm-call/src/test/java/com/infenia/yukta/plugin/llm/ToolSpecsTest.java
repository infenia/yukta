// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@NoArgsConstructor
@SuppressWarnings({"PMD.CommentRequired", "PMD.AvoidDuplicateLiterals"})
class ToolSpecsTest {

  @Test
  void namedMapsBecomeToolSpecs_withDefaults() {
    final List<ToolSpec> tools =
        ToolSpecs.parse(
            List.of(
                Map.of("name", "weather", "description", "Weather", "inputSchema", Map.of("a", 1)),
                Map.of("name", "ping")));

    assertThat(tools)
        .containsExactly(
            new ToolSpec("weather", "Weather", Map.of("a", 1)),
            new ToolSpec("ping", "", Map.of("type", "object", "properties", Map.of())));
  }

  @Test
  void anythingButList_meansNoTools() {
    assertThat(ToolSpecs.parse(null)).isEmpty();
    assertThat(ToolSpecs.parse("")).isEmpty();
    assertThat(ToolSpecs.parse(Map.of("name", "x"))).isEmpty();
  }

  @Test
  void everyToolNeedsName() {
    assertThatThrownBy(() -> ToolSpecs.parse(List.of(Map.of("description", "no name"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ToolSpecs.parse(List.of(Map.of("name", " "))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ToolSpecs.parse(List.of("text")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void validateAcceptsListsAndExpressions_butNothingElse() {
    ToolSpecs.validate(null);
    ToolSpecs.validate("payload['tools']");
    ToolSpecs.validate(List.of(Map.of("name", "ok")));
    assertThatThrownBy(() -> ToolSpecs.validate(List.of(Map.of())))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ToolSpecs.validate(5)).isInstanceOf(IllegalArgumentException.class);
  }
}
