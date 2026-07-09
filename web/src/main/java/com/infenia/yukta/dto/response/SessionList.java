// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * List of available sessions in the system.
 *
 * @param sessionIds the list of session identifiers
 */
@Schema(description = "List of available sessions")
public record SessionList(
    @Schema(description = "The list of session identifiers") List<String> sessionIds) {
  /** Compact constructor ensuring immutability. */
  public SessionList {
    sessionIds = sessionIds != null ? List.copyOf(sessionIds) : List.of();
  }
}
