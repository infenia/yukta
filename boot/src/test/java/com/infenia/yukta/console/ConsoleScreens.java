// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.console;

import com.infenia.yukta.console.ConsoleTheme.Role;
import java.time.Duration;
import java.util.List;
import org.jline.utils.AttributedString;

/** Test helpers that draw the console screen and flatten it to text. */
final class ConsoleScreens {

  /** The uptime the motion tests draw with: three minutes. */
  /* default */ static final Duration UP = Duration.ofMinutes(3);

  private ConsoleScreens() {}

  /** The screen as drawn at 80x20 with a 65 second uptime. */
  /* default */ static List<AttributedString> screen(
      final ConsoleView view, final ConsoleView.Snapshot snap) {
    return view.render(snap, Duration.ofSeconds(65), 80, 20, 0);
  }

  /* default */ static String text(final List<AttributedString> lines) {
    return String.join("\n", lines.stream().map(AttributedString::toString).toList());
  }

  /* default */ static List<AttributedString> draw(
      final ConsoleView v, final ConsoleView.Snapshot s, final long frameMs) {
    return v.render(s, UP, 80, 24, frameMs);
  }

  /* default */ static String body(final List<AttributedString> lines) {
    return text(lines.subList(4, lines.size() - 1));
  }

  /* default */ static ConsoleView.Run run(final String status) {
    return new ConsoleView.Run("build", status, "10:00:00", "me");
  }

  /** The role of the first blank cell right of the row's text, on screen row {@code row}. */
  /* default */ static Role roleOfBlank(final List<AttributedString> lines, final int row) {
    final AttributedString line = lines.get(row);
    final var flash = Role.FLASH.style();
    return line.styleAt(60).equals(flash) ? Role.FLASH : Role.TEXT;
  }
}
