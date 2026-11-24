// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.console;

import static com.infenia.yukta.console.ConsoleScreens.screen;
import static com.infenia.yukta.console.ConsoleScreens.text;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConsoleLogPaneTest {

  private final ConsoleLogs logs = new ConsoleLogs();
  private final ConsoleView view = new ConsoleView("1.2", "http://h:1", logs, false, false);
  private final ConsoleView.Snapshot snap =
      new ConsoleView.Snapshot(
          "UP",
          2,
          1,
          3,
          List.of(new ConsoleView.Run("build", "RUNNING", "2026-01-01 10:00", "me")));

  @Test
  void logPaneFiltersByLevelAndScrolls() {
    logs.add(Level.DEBUG, "dbg");
    logs.add(Level.INFO, "info-1");
    logs.add(Level.WARN, "warn-1");
    view.onKey("l");
    final String info = text(screen(view, snap));
    assertThat(info).contains("info-1", "warn-1").doesNotContain("dbg");
    view.onKey("f"); // INFO -> WARN
    assertThat(text(screen(view, snap))).contains("warn-1").doesNotContain("info-1");
  }

  @Test
  void ringDropsTheOldestLines() {
    for (int i = 0; i < ConsoleLogs.CAPACITY + 5; i++) {
      logs.add(Level.INFO, "n" + i);
    }
    final var all = logs.snapshot(Level.ALL);
    assertThat(all).hasSize(ConsoleLogs.CAPACITY);
    assertThat(all.getFirst().text()).isEqualTo("n5");
  }

  @Test
  void escapeSequencesInLogsAreDropped() {
    logs.add(Level.INFO, "\033[2Jclear\tme");
    view.onKey("l");
    assertThat(text(screen(view, snap))).contains("clear me").doesNotContain("\033");
  }

  @Test
  void homeAndEndMoveTheLogView() {
    for (int i = 0; i < 50; i++) {
      logs.add(Level.INFO, "n" + i);
    }
    view.onKey("l");
    view.onKey("HOME");
    assertThat(text(screen(view, snap))).contains("n0").doesNotContain("n49");
    view.onKey("END");
    assertThat(text(screen(view, snap))).contains("n49");
  }
}
