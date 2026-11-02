// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.console;

import java.time.Duration;
import java.util.List;
import org.jline.utils.AttributedString;

/** Test helpers that draw the console screen and flatten it to text. */
final class ConsoleScreens {

  private ConsoleScreens() {}

  /** The screen as drawn at 80x20 with a 65 second uptime. */
  /* default */ static List<AttributedString> screen(
      final ConsoleView view, final ConsoleView.Snapshot snap) {
    return view.render(snap, Duration.ofSeconds(65), 80, 20);
  }

  /* default */ static String text(final List<AttributedString> lines) {
    return String.join("\n", lines.stream().map(AttributedString::toString).toList());
  }
}
