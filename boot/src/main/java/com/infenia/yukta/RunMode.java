// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta;

import java.util.Locale;
import java.util.Map;

/** How the server presents itself: an interactive console, plain logs, or a background daemon. */
public enum RunMode {
  /** Interactive dashboard; the default when stdin and stdout are a terminal. */
  CONSOLE,
  /** Traditional log-only output; the default when not on a terminal. */
  PLAIN,
  /** Detached background process, managed by {@code yukta start|stop|status}. */
  DAEMON;

  /** Name of the command-line option and the environment variable's suffix. */
  /* default */ static final String ENV = "YUKTA_MODE";

  /**
   * Resolves the mode: {@code --plain}, then {@code --mode=<m>} / {@code --mode <m>}, then {@code
   * YUKTA_MODE}, then console on a terminal and plain otherwise.
   *
   * @throws IllegalArgumentException for an unknown mode name
   */
  /* default */ static RunMode resolve(
      final Map<String, String> env, final boolean tty, final String... args) {
    String name = env.get(ENV);
    for (int i = 0; i < args.length; i++) {
      if ("--plain".equals(args[i])) {
        return PLAIN;
      }
      if (args[i].startsWith("--mode=")) {
        name = args[i].substring("--mode=".length());
      } else if ("--mode".equals(args[i]) && i + 1 < args.length) {
        name = args[i + 1];
      }
    }
    if (name == null || name.isBlank()) {
      return tty ? CONSOLE : PLAIN;
    }
    try {
      return valueOf(name.strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "Unknown run mode '" + name + "' (expected console, plain or daemon)", e);
    }
  }

  /** Whether stdin and stdout are both a terminal. */
  /* default */ static boolean isTty() {
    return System.console().isTerminal();
  }
}
