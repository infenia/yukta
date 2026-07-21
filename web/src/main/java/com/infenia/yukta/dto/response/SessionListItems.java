// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Response wrapper for a list of session summaries.
 *
 * @param sessions list of session summaries
 */
@Schema(description = "List of session summaries")
public record SessionListItems(
    @Schema(description = "Array of session items") List<SessionListItem> sessions) {

  /** Compact constructor for defensive copying. */
  public SessionListItems {
    sessions = sessions == null ? List.of() : List.copyOf(sessions);
  }
}
