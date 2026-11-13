// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.process;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** Reads values out of a parsed JSON line by dotted path ({@code a.b}, {@code a[*].b}). */
@UtilityClass
class JsonPaths {

  /* default */ boolean matches(final JsonNode node, final Map<String, String> when) {
    return !when.isEmpty()
        && when.entrySet().stream().allMatch(e -> e.getValue().equals(first(node, e.getKey())));
  }

  /** The node at a dotted path; missing when absent. */
  /* default */ JsonNode pathNode(final JsonNode root, final String path) {
    JsonNode current = root;
    for (final String segment : path.split("\\.")) {
      current = current.path(segment);
    }
    return current;
  }

  /* default */ String firstOr(final String value, final @Nullable String fallback) {
    return value.isEmpty() && fallback != null ? fallback : value;
  }

  /* default */ String first(final JsonNode node, final String path) {
    final List<String> found = values(node, path);
    return found.isEmpty() ? "" : found.getFirst();
  }

  /** Values at a dotted path; a segment {@code name[*]} visits every element of that array. */
  /* default */ List<String> values(final JsonNode root, final String path) {
    List<JsonNode> current = List.of(root);
    for (final String segment : path.split("\\.")) {
      current = step(current, segment);
    }
    return current.stream().map(n -> n.asString("")).filter(s -> !s.isEmpty()).toList();
  }

  private List<JsonNode> step(final List<JsonNode> current, final String segment) {
    final boolean each = segment.endsWith("[*]");
    final String name = each ? segment.substring(0, segment.length() - 3) : segment;
    final List<JsonNode> next = new ArrayList<>();
    current.forEach(n -> collect(n.path(name), each, next));
    return next;
  }

  private void collect(final JsonNode child, final boolean each, final List<JsonNode> out) {
    if (each) {
      child.forEach(out::add);
    } else if (!child.isMissingNode() && !child.isNull()) {
      out.add(child);
    }
  }
}
