// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

import java.util.List;

/**
 * List of sessions returned by the {@code list_sessions} MCP tool. Wrapping the list in a record
 * keeps the tool's output schema generatable (bare {@code Mono<List<T>>} return types break schema
 * generation on the async stateless MCP stack).
 *
 * @param sessions the session summaries
 */
public record SessionList(List<SessionSummary> sessions) {

  /** Compact constructor to ensure immutability. */
  public SessionList {
    sessions = sessions == null ? List.of() : List.copyOf(sessions);
  }
}
