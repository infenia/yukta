// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.process;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Dotted paths into a JSON line. */
@NoArgsConstructor
class JsonPathsTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static JsonNode node(final String json) {
    return JSON.readTree(json);
  }

  @Test
  void values_visitEveryElementOfAnArray() {
    final JsonNode line = node("{\"a\":[{\"b\":\"x\"},{\"b\":\"y\"},{\"c\":\"z\"}]}");

    assertThat(JsonPaths.values(line, "a[*].b")).containsExactly("x", "y");
  }

  @Test
  void values_skipMissingAndNullFields() {
    final JsonNode line = node("{\"a\":null,\"b\":{\"c\":\"d\"}}");

    assertThat(JsonPaths.values(line, "a")).isEmpty();
    assertThat(JsonPaths.values(line, "nope.deeper")).isEmpty();
    assertThat(JsonPaths.first(line, "b.c")).isEqualTo("d");
    assertThat(JsonPaths.first(line, "b.none")).isEmpty();
  }

  @Test
  void matches_needsEveryFieldToAgree_andAtLeastOne() {
    final JsonNode line = node("{\"type\":\"result\",\"x\":{\"y\":\"1\"}}");

    assertThat(JsonPaths.matches(line, Map.of("type", "result", "x.y", "1"))).isTrue();
    assertThat(JsonPaths.matches(line, Map.of("type", "other"))).isFalse();
    assertThat(JsonPaths.matches(line, Map.of())).isFalse();
  }

  @Test
  void pathNode_andFirstOr() {
    final JsonNode line = node("{\"a\":{\"b\":1}}");

    assertThat(JsonPaths.pathNode(line, "a.b").asInt()).isEqualTo(1);
    assertThat(JsonPaths.pathNode(line, "a.z").isMissingNode()).isTrue();
    assertThat(JsonPaths.firstOr("", "fallback")).isEqualTo("fallback");
    assertThat(JsonPaths.firstOr("", null)).isEmpty();
    assertThat(JsonPaths.firstOr("v", "fallback")).isEqualTo("v");
  }

  @Test
  void values_dropEmptyStrings() {
    assertThat(JsonPaths.values(node("{\"a\":[\"\",\"x\"]}"), "a[*]")).containsExactly("x");
  }
}
