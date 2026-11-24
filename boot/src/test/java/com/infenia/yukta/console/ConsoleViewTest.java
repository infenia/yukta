// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.console;

import static com.infenia.yukta.console.ConsoleScreens.screen;
import static com.infenia.yukta.console.ConsoleScreens.text;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;

class ConsoleViewTest {

  private static final String TERM = "TERM";
  private static final String XTERM = "xterm";

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
  void showsHeaderCountsAndRuns() {
    final String all = text(screen(view, snap));
    assertThat(all).contains("YUKTA 1.2", "up 0h01m", "UP", "Running", "build");
    assertThat(screen(view, snap)).hasSize(20);
  }

  @Test
  void colorsOnlyWhenWanted() {
    final var colored = new ConsoleView("1.2", "http://h:1", logs, true, true);
    colored.onKey("x"); // any key ends the welcome box
    final String ansi =
        String.join(
            "",
            colored.render(snap, Duration.ZERO, 80, 20, 0).stream()
                .map(AttributedString::toAnsi)
                .toList());
    assertThat(ansi).contains("\033[").contains("UP");
    assertThat(screen(view, snap)).allSatisfy(l -> assertThat(l.toAnsi()).doesNotContain("\033"));
  }

  @Test
  void motionFollowsColourAndTheSwitch() {
    assertThat(ConsoleView.motionWanted(java.util.Map.of(TERM, XTERM))).isTrue();
    assertThat(
            ConsoleView.motionWanted(
                java.util.Map.of(TERM, XTERM, "YUKTA_CONSOLE_ANIMATION", "off")))
        .isFalse();
    assertThat(ConsoleView.motionWanted(java.util.Map.of(TERM, "dumb"))).isFalse();
  }

  @Test
  void colorFollowsNoColorAndTerm() {
    assertThat(ConsoleView.colorWanted(java.util.Map.of(TERM, XTERM))).isTrue();
    assertThat(ConsoleView.colorWanted(java.util.Map.of(TERM, XTERM, "NO_COLOR", ""))).isFalse();
    assertThat(ConsoleView.colorWanted(java.util.Map.of(TERM, "dumb"))).isFalse();
    assertThat(ConsoleView.colorWanted(java.util.Map.of())).isFalse();
  }

  @Test
  void drawsForAnUnsizedTerminal() {
    assertThat(view.render(snap, Duration.ZERO, 0, 0, 0)).hasSize(24);
    assertThat(view.render(snap, Duration.ZERO, 10, 1, 0)).hasSize(5);
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
  void cellsClipByColumnsNotCharacters() {
    final var cells = new Cells(3, 1);
    cells.put(0, 0, "abcdef", ConsoleTheme.Role.TEXT);
    assertThat(cells.lines(true).get(0).toString()).isEqualTo("abc");
    // a wide character takes two columns
    final var wide = new Cells(3, 1);
    wide.put(0, 0, "你好a", ConsoleTheme.Role.TEXT);
    assertThat(wide.lines(false).get(0).toString()).isEqualTo("你");
  }
}
