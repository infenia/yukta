// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

import com.infenia.yukta.plugin.ai.AiBackend;
import java.util.List;

/**
 * The AI backends returned by the {@code list_ai_backends} MCP tool.
 *
 * @param backends each backend with the options it reads
 */
public record AiBackendList(List<AiBackend.Info> backends) {

  /** Compact constructor to ensure immutability. */
  public AiBackendList {
    backends = backends == null ? List.of() : List.copyOf(backends);
  }
}
