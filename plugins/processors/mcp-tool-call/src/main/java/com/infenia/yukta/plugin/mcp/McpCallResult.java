// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.mcp;

import java.util.List;

/**
 * Outcome of an MCP tool call. A tool-reported error is data, not an exception.
 *
 * @param isError whether the tool (or the JSON-RPC layer) reported an error
 * @param text the text content items joined by newlines
 * @param content the raw content items
 * @param structured the tool's structured result, or null
 * @param error the error description when {@code isError}, else null
 */
public record McpCallResult(
    boolean isError, String text, List<Object> content, Object structured, String error) {

  /** Defensively copies the content. */
  public McpCallResult {
    content = List.copyOf(content);
  }

  /**
   * Creates the result for a call that failed before the tool answered.
   *
   * @param message the failure description
   * @return an error result
   */
  public static McpCallResult transportFailure(final String message) {
    return new McpCallResult(true, "", List.of(), null, message);
  }
}
