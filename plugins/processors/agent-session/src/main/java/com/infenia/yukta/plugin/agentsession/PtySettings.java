// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.agentsession;

import java.util.List;

/**
 * The terminal tier of the server ({@code yukta.agents.pty.*}).
 *
 * @param enabled whether a terminal agent may run; off by default, because a terminal accepts any
 *     keystroke and nothing can review what is typed
 * @param host the executable that provides the {@code pty-host} command
 * @param cols the width of a new terminal
 * @param rows the height of a new terminal
 */
public record PtySettings(boolean enabled, String host, int cols, int rows) {

  /** The most columns or rows a terminal may have. */
  public static final int MAX_SIZE = 1000;

  /** The tier as the server ships it: off. */
  public static final PtySettings OFF = new PtySettings(false, "yukta-cli", 120, 40);

  /**
   * The prefix that runs a command in a terminal.
   *
   * @param width the width of this terminal
   * @param height the height of this terminal
   * @return the host, its command and the size, ending in {@code --}
   */
  /* default */ List<String> launcher(final int width, final int height) {
    return List.of(
        host, "pty-host", "--cols", String.valueOf(width), "--rows", String.valueOf(height), "--");
  }
}
