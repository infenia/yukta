// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.console;

import static com.infenia.yukta.console.ConsoleScreens.text;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import com.infenia.yukta.console.ConsolePainter.Model;
import com.infenia.yukta.console.ConsolePainter.Series;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConsolePainterTest {

  private static final Series SERIES = new Series(List.of(1, 2), List.of(0), List.of(3));
  private static final String URL = "http://h:8080";
  private static final ConsoleView.Run RUN_ONE =
      new ConsoleView.Run("build", "RUNNING", "10:00:00", "me");

  private static Model model(
      final ConsoleView.Snapshot snap,
      final ConsoleView.Page page,
      final long busy,
      final Level level) {
    return new Model(
        snap,
        page,
        Duration.ofMinutes(125),
        "1.2",
        URL,
        level,
        rows ->
            List.of(
                new ConsoleLogs.Line(Level.ERROR, "boom"),
                new ConsoleLogs.Line(Level.WARN, "careful"),
                new ConsoleLogs.Line(Level.INFO, "fine"),
                new ConsoleLogs.Line(Level.DEBUG, "detail")),
        SERIES,
        busy,
        run -> "RUNNING".equals(run.status()),
        3);
  }

  private static String draw(final ConsolePainter p, final Model m) {
    return text(p.screen(m).lines(true));
  }

  @Test
  void smallDashboardWithEveryStatusAndMotion() {
    final var runs =
        List.of(
            new ConsoleView.Run("a", "RUNNING", "10:00:00", "me"),
            new ConsoleView.Run("b", "FAILURE", "10:00:01", "me"),
            new ConsoleView.Run("c", "TIMED_OUT", "10:00:02", "me"),
            new ConsoleView.Run("d", "SUCCESS", "10:00:03", "me"),
            new ConsoleView.Run("e", "QUEUED", "10:00:04", "me"),
            new ConsoleView.Run("f", "CANCELLED", "10:00:05", "me"));
    final var snap = new ConsoleView.Snapshot("UP", 1, 1, 2, runs);
    final var p = new ConsolePainter(40, 12, 1234L, true);
    assertThat(draw(p, model(snap, ConsoleView.Page.DASHBOARD, 5, Level.INFO)))
        .contains("running 1", "YUKTA");
  }

  @Test
  void wideDashboardWithoutMotionShowsMetersAndSparklines() {
    final var snap = new ConsoleView.Snapshot("UP", 3, 0, 1, List.of(RUN_ONE));
    final var p = new ConsolePainter(100, 30, 0L, false);
    final String out = draw(p, model(snap, ConsoleView.Page.DASHBOARD, 125, Level.WARN));
    assertThat(out).contains("Running", "Queued", "Failed", "2m05s", "Orchestrating");
  }

  @Test
  void singleRunIsSingularAndIdleIsShownWhenNothingRuns() {
    final var one = new ConsoleView.Snapshot("UP", 1, 0, 0, List.of());
    assertThat(
            draw(
                new ConsolePainter(80, 24, 0L, false),
                model(one, ConsoleView.Page.DASHBOARD, 42, Level.ALL)))
        .contains("run…", "42s");
    final var idle = new ConsoleView.Snapshot("UP", 0, 4, 0, List.of());
    assertThat(
            draw(
                new ConsolePainter(80, 24, 0L, true),
                model(idle, ConsoleView.Page.DASHBOARD, 0, Level.ALL)))
        .contains("Idle", "4 queued");
  }

  @Test
  void healthDotBlinksWithMotionOnOddFrames() {
    final var snap = new ConsoleView.Snapshot("UP", 0, 0, 0, List.of());
    final String odd =
        draw(
            new ConsolePainter(80, 24, 500L, true),
            model(snap, ConsoleView.Page.DASHBOARD, 0, Level.ALL));
    assertThat(odd).contains("◉ UP");
    final String even =
        draw(
            new ConsolePainter(80, 24, 0L, true),
            model(snap, ConsoleView.Page.DASHBOARD, 0, Level.ALL));
    assertThat(even).contains("● UP");
  }

  @Test
  void logPaneOfZeroRowsDrawsNothing() {
    final var snap = new ConsoleView.Snapshot("UP", 0, 0, 0, List.of());
    final String out =
        draw(
            new ConsolePainter(80, 4, 0L, false), model(snap, ConsoleView.Page.LOGS, 0, Level.ALL));
    assertThat(out).doesNotContain("boom");
  }

  @Test
  void serverDownShowsTheRetryLine() {
    final var down = new ConsoleView.Snapshot("DOWN", 0, 0, 0, List.of());
    final String out =
        draw(
            new ConsolePainter(80, 24, 0L, false),
            model(down, ConsoleView.Page.DASHBOARD, 0, Level.ALL));
    assertThat(out).contains("Server unreachable", "No data", "retrying");
  }

  @Test
  void runsAndLogsAndHelpPagesRender() {
    final var snap = new ConsoleView.Snapshot("UP", 1, 0, 0, List.of(RUN_ONE));
    final var p = new ConsolePainter(80, 24, 0L, true);
    assertThat(draw(p, model(snap, ConsoleView.Page.RUNS, 0, Level.ALL))).contains("build");
    assertThat(draw(p, model(snap, ConsoleView.Page.LOGS, 0, Level.ALL)))
        .contains("boom", "careful", "fine", "detail");
    assertThat(draw(p, model(snap, ConsoleView.Page.HELP, 0, Level.ALL)))
        .contains("quit the server");
  }

  @Test
  void splashSmallAndBig() {
    final var small = new ConsolePainter(40, 10, 0L, false).splash("1.2", URL, 1200L);
    assertThat(text(small.lines(false))).contains("YUKTA", URL);
    final var big = new ConsolePainter(80, 24, 0L, false).splash("1.2", URL, 3000L);
    assertThat(text(big.lines(false)))
        .contains("Welcome to Yukta 1.2", "workflow orchestration", "ready");
  }
}
