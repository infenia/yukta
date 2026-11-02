// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.console;

import static com.infenia.yukta.console.ConsoleScreens.screen;
import static com.infenia.yukta.console.ConsoleScreens.text;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import java.time.Duration;
import java.util.List;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.junit.jupiter.api.Test;

class ConsoleViewTest {

  private final ConsoleLogs logs = new ConsoleLogs();
  private final ConsoleView view = new ConsoleView("1.2", "http://h:1", logs, false);
  private final ConsoleView.Snapshot snap =
      new ConsoleView.Snapshot(
          "UP",
          2,
          1,
          3,
          List.of(new ConsoleView.Run("build", "RUNNING", "2026-01-01 10:00", "me")));

  @Test
  void showsHeaderCountsAndRuns() {
    final String all = text(screen(view, snap));
    assertThat(all).contains("Yukta 1.2", "up 0h01m05s", "health UP", "running 2", "build");
    assertThat(screen(view, snap)).hasSize(20);
  }

  @Test
  void colorsOnlyWhenWanted() {
    final var colored = new ConsoleView("1.2", "http://h:1", logs, true);
    final String ansi =
        String.join(
            "",
            colored.render(snap, Duration.ZERO, 80, 20).stream()
                .map(AttributedString::toAnsi)
                .toList());
    assertThat(ansi).contains("\033[32mUP", "\033[31m3");
    assertThat(screen(view, snap)).allSatisfy(l -> assertThat(l.toAnsi()).doesNotContain("\033"));
  }

  @Test
  void colorFollowsNoColorAndTerm() {
    assertThat(ConsoleView.colorWanted(java.util.Map.of("TERM", "xterm"))).isTrue();
    assertThat(ConsoleView.colorWanted(java.util.Map.of("TERM", "xterm", "NO_COLOR", "")))
        .isFalse();
    assertThat(ConsoleView.colorWanted(java.util.Map.of("TERM", "dumb"))).isFalse();
    assertThat(ConsoleView.colorWanted(java.util.Map.of())).isFalse();
  }

  @Test
  void drawsForAnUnsizedTerminal() {
    assertThat(view.render(snap, Duration.ZERO, 0, 0)).hasSize(24);
    assertThat(view.render(snap, Duration.ZERO, 10, 1)).hasSize(5);
  }

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
  void keysSwitchPagesAndQuit() {
    view.onKey("?");
    assertThat(view.page()).isEqualTo(ConsoleView.Page.HELP);
    view.onKey("?");
    assertThat(view.page()).isEqualTo(ConsoleView.Page.DASHBOARD);
    view.onKey("r");
    assertThat(view.wantsManyRuns()).isTrue();
    assertThat(view.quit()).isFalse();
    view.onKey("q");
    assertThat(view.quit()).isTrue();
  }

  @Test
  void clipCountsColumnsNotCharacters() {
    final var red = new AttributedString("abcdef", AttributedStyle.DEFAULT.foreground(1));
    assertThat(ConsoleView.clip(red, 3).toString()).isEqualTo("abc");
    // a wide character takes two columns
    assertThat(ConsoleView.clip(new AttributedString("你好a"), 3).toString()).isEqualTo("你");
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
