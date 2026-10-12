// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.experimental.UtilityClass;

/** Reads the {@code tools} option of an LLM node. */
@UtilityClass
public class ToolSpecs {

  /** Schema of a tool that takes no arguments. */
  private static final Map<String, Object> NO_ARGUMENTS =
      Map.of("type", "object", "properties", Map.of());

  /**
   * Turns the option into tool specs.
   *
   * @param value a list of {@code {name, description?, inputSchema?}} maps; anything else yields no
   *     tools (an expression that finds nothing leaves the call without tools)
   * @return the tools, empty for none
   * @throws IllegalArgumentException if a tool has no name
   */
  public static List<ToolSpec> parse(final Object value) {
    final List<ToolSpec> tools = new ArrayList<>();
    if (value instanceof final List<?> list) {
      for (final Object item : list) {
        tools.add(toSpec(item));
      }
    }
    return tools;
  }

  /**
   * Checks the option when the workflow is validated; an expression string is checked when it runs.
   *
   * @param value the option
   * @throws IllegalArgumentException if it is neither an expression nor a list of named tools
   */
  public static void validate(final Object value) {
    if (value instanceof final List<?> list) {
      list.forEach(ToolSpecs::toSpec);
    } else if (value != null && !(value instanceof String)) {
      throw new IllegalArgumentException(
          "tools must be a list of {name, description, inputSchema}");
    }
  }

  @SuppressWarnings("unchecked")
  private static ToolSpec toSpec(final Object item) {
    if (item instanceof final Map<?, ?> tool
        && tool.get("name") instanceof final String name
        && !name.isBlank()) {
      return new ToolSpec(
          name,
          tool.get("description") == null ? "" : tool.get("description").toString(),
          tool.get("inputSchema") instanceof final Map<?, ?> schema
              ? (Map<String, Object>) schema
              : NO_ARGUMENTS);
    }
    throw new IllegalArgumentException("every tool needs a name: " + item);
  }
}
