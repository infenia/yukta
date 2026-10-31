// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.console;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConsoleViewTest {

  private final ConsoleLogs logs = new ConsoleLogs();
  private final ConsoleView view = new ConsoleView("1.2", "http://h:1", logs);
  private final ConsoleView.Snapshot snap =
      new ConsoleView.Snapshot(
          "UP",
          2,
          1,
          3,
          List.of(new ConsoleView.Run("build", "RUNNING", "2026-01-01 10:00", "me")));

  private List<String> screen() {
    return view.render(snap, Duration.ofSeconds(65), 80, 20);
  }

  @Test
  void showsHeaderCountsAndRuns() {
    final String all = String.join("\n", screen());
    assertThat(all).contains("Yukta 1.2", "up 0h01m05s", "health UP", "running 2", "build");
    assertThat(screen()).hasSize(20);
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
    final String info = String.join("\n", screen());
    assertThat(info).contains("info-1", "warn-1").doesNotContain("dbg");
    view.onKey("f"); // INFO -> WARN
    assertThat(String.join("\n", screen())).contains("warn-1").doesNotContain("info-1");
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
  void clipIgnoresEscapeSequences() {
    assertThat(ConsoleView.clip("\033[31mabcdef\033[0m", 3)).startsWith("\033[31mabc\033[0m");
  }
}
