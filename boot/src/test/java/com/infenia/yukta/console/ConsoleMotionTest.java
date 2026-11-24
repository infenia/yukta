// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.console;

import static com.infenia.yukta.console.ConsoleScreens.text;
import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.console.ConsoleTheme.Role;
import java.time.Duration;
import java.util.List;
import org.jline.utils.AttributedString;
import org.junit.jupiter.api.Test;

class ConsoleMotionTest {

  private static final Duration UP = Duration.ofMinutes(3);

  private final ConsoleView view =
      new ConsoleView("1.0", "http://h:9", new ConsoleLogs(), true, true);
  private final ConsoleView.Snapshot busy =
      new ConsoleView.Snapshot(
          "UP", 1, 0, 0, List.of(new ConsoleView.Run("build", "RUNNING", "10:00:00", "me")));

  private List<AttributedString> draw(
      final ConsoleView v, final ConsoleView.Snapshot s, final long frameMs) {
    return v.render(s, UP, 80, 24, frameMs);
  }

  @Test
  void theWelcomeBoxEndsOnItsOwn() {
    assertThat(text(draw(view, ConsoleView.Snapshot.NONE, 0))).contains("Welcome to Yukta 1.0");
    // an answer from the server ends it once it has been up for 0.9 s
    assertThat(text(draw(view, busy, 500))).contains("Welcome to Yukta");
    assertThat(text(draw(view, busy, 1000))).contains("YUKTA").doesNotContain("Welcome");
    // a server that never answers cannot hold it for more than 3 s
    final var silent = new ConsoleView("1.0", "http://h:9", new ConsoleLogs(), true, true);
    draw(silent, ConsoleView.Snapshot.NONE, 0);
    assertThat(text(draw(silent, ConsoleView.Snapshot.NONE, 3000))).doesNotContain("Welcome");
  }

  @Test
  void anyKeyEndsTheWelcomeBoxAndIsStillApplied() {
    draw(view, ConsoleView.Snapshot.NONE, 0);
    view.onKey("q");
    assertThat(view.quit()).isTrue();
    assertThat(text(draw(view, ConsoleView.Snapshot.NONE, 100))).doesNotContain("Welcome");
  }

  @Test
  void aShortTerminalGetsNoWelcomeBox() {
    final var rows = view.render(ConsoleView.Snapshot.NONE, UP, 80, 8, 0);
    assertThat(text(rows)).doesNotContain("Welcome");
  }

  @Test
  void withoutMotionNothingMovesAndThereIsNoWelcomeBox() {
    final var still = new ConsoleView("1.0", "http://h:9", new ConsoleLogs(), true, false);
    final String a = text(draw(still, busy, 0));
    assertThat(text(draw(still, busy, 900))).isEqualTo(a);
    assertThat(a).doesNotContain("Welcome").contains("* Orchestrating 1 run…").contains("*");
  }

  @Test
  void aStatusChangeFlashesForAMoment() {
    final var queued = new ConsoleView.Snapshot("UP", 0, 1, 0, List.of(run("QUEUED")));
    draw(view, queued, -5000);
    draw(view, busy, 1000); // QUEUED -> RUNNING
    assertThat(roleOfBlank(draw(view, busy, 1200), 10)).isEqualTo(Role.FLASH);
    assertThat(roleOfBlank(draw(view, busy, 1700), 10)).isNotEqualTo(Role.FLASH);
  }

  @Test
  void aPageChangeSlidesIn() {
    draw(view, busy, -5000);
    view.onKey("?");
    final String start = body(draw(view, busy, 0)); // first frame: shifted right
    final String later = body(draw(view, busy, 250));
    assertThat(start).isNotEqualTo(later);
    assertThat(body(draw(view, busy, 300))).isEqualTo(later);
  }

  @Test
  void theHeaderStatusAndElapsedTime() {
    draw(view, busy, -90_000);
    assertThat(text(draw(view, busy, 0))).contains("Orchestrating 1 run…").contains("(1m30s ·");
    final var down = new ConsoleView.Snapshot("DOWN", 0, 0, 0, List.of());
    assertThat(text(draw(view, down, 100))).contains("Server unreachable", "DOWN");
    assertThat(text(view.render(busy, UP, 20, 24, 100))).doesNotContain("up 0h03m");
  }

  @Test
  void emptyAndOddRuns() {
    final var none = new ConsoleView.Snapshot("UP", 0, 0, 0, List.of());
    view.onKey("x"); // end the welcome box
    assertThat(text(draw(view, none, 5000))).contains("no runs yet", "Idle");
    final var odd =
        new ConsoleView.Snapshot(
            "UP",
            0,
            0,
            0,
            List.of(
                new ConsoleView.Run(
                    "a-very-long-workflow-name-that-cannot-fit", "TIMED_OUT", "t", "u"),
                new ConsoleView.Run("b", "CANCELLED", "t", "u")));
    final String screen = text(draw(view, odd, 5000));
    assertThat(screen).contains("a-very-long-workflow-na ").contains("✗").contains("‖");
  }

  @Test
  void theThemeHelpers() {
    assertThat(ConsoleTheme.meter(0, 8, 4)).isEqualTo("    ");
    assertThat(ConsoleTheme.meter(8, 8, 4)).isEqualTo("████");
    assertThat(ConsoleTheme.meter(99, 8, 4)).isEqualTo("████");
    assertThat(ConsoleTheme.meter(1, 8, 4)).isEqualTo("▌   ");
    assertThat(ConsoleTheme.sparkline(List.of(0, 0))).isEqualTo("▁▁");
    assertThat(ConsoleTheme.sparkline(List.of(0, 7, 14))).isEqualTo("▁▅█");
    assertThat(ConsoleTheme.gradient(0, 0)).isEqualTo(Role.G0);
    assertThat(ConsoleTheme.gradient(0, 8)).isEqualTo(Role.G7);
    assertThat(ConsoleTheme.gradient(0, 16)).isEqualTo(Role.G0);
    assertThat(ConsoleTheme.shimmer(5, 10, 3)).isEqualTo(Role.SHIM);
    assertThat(ConsoleTheme.shimmer(5, 10, 0)).isEqualTo(Role.TEXT);
    assertThat(ConsoleTheme.star(0)).isEqualTo("·");
    assertThat(ConsoleTheme.braille(10)).isEqualTo(ConsoleTheme.braille(0));
  }

  @Test
  void cellsOverlayAndShade() {
    final var base = new Cells(6, 2);
    base.put(0, 0, "ab", Role.BOLD);
    final var top = new Cells(6, 2);
    top.put(0, 0, "xy", Role.OK);
    base.overlay(top, 1, 0, 0);
    base.shadeBlanks(1, Role.ZEBRA);
    assertThat(base.charAt(0, 0)).isEqualTo('a');
    assertThat(base.charAt(0, 1)).isEqualTo('x');
    assertThat(base.roleAt(0, 2)).isEqualTo(Role.OK);
    assertThat(base.roleAt(1, 3)).isEqualTo(Role.ZEBRA);
    base.put(5, 0, "off", Role.TEXT); // off the grid: ignored
  }

  private static String body(final List<AttributedString> lines) {
    return text(lines.subList(4, lines.size() - 1));
  }

  private static ConsoleView.Run run(final String status) {
    return new ConsoleView.Run("build", status, "10:00:00", "me");
  }

  /** The role of the first blank cell right of the row's text, on screen row {@code row}. */
  private static Role roleOfBlank(final List<AttributedString> lines, final int row) {
    final AttributedString line = lines.get(row);
    final var flash = Role.FLASH.style();
    return line.styleAt(60).equals(flash) ? Role.FLASH : Role.TEXT;
  }
}
