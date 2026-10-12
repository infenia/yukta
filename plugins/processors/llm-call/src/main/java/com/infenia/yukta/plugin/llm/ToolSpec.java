// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.llm;

import java.util.Map;

/**
 * A tool the model may ask to call.
 *
 * @param name the tool name
 * @param description what the tool does, shown to the model
 * @param inputSchema JSON Schema of the arguments
 */
public record ToolSpec(String name, String description, Map<String, Object> inputSchema) {

  /** Defensively copies the schema. */
  public ToolSpec {
    inputSchema = Map.copyOf(inputSchema);
  }
}
