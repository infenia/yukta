// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Who may send which command to a node, a run level or the server.
 *
 * @param commands one entry per command, by name
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SupervisionAnswer(List<Command> commands) {

  /** Keeps an immutable copy of the commands. */
  public SupervisionAnswer {
    commands = List.copyOf(commands);
  }

  /**
   * One command.
   *
   * @param command the command name
   * @param allowed whether people may send it now
   * @param via the owning supervisor, or {@code default} when nobody owns the unit
   * @param reason why not, or null
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Command(String command, boolean allowed, String via, @Nullable String reason) {}
}
