// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.console;

import ch.qos.logback.classic.Level;
import com.infenia.yukta.console.ConsoleTheme.Role;
import java.time.Duration;
import java.util.List;
import java.util.function.IntFunction;
import java.util.function.Predicate;

/**
 * Draws one frame of the console onto {@link Cells}. Pure: the same model, size and frame always
 * give the same cells, so the screens can be compared with the approved mockup cell for cell.
 */
@SuppressWarnings({"PMD.TooManyMethods", "PMD.CognitiveComplexity"})
/* default */ final class ConsolePainter {

  private static final String UP = "UP";

  /** The row of the tab strip. */
  /* default */ static final int TAB_ROW = 2;

  private static final int TOP = 4;
  private static final String[][] HELP = {
    {"q", "quit the server"},
    {"d", "dashboard"},
    {"l", "full logs (toggle)"},
    {"r", "runs (toggle)"},
    {"f", "cycle the log level filter"},
    {"↑ ↓ PgUp PgDn", "scroll, G or End to follow"},
    {"← →", "previous / next tab"},
    {"m", "mouse: wheel scrolls, tabs click; off lets you select text"},
    {"?", "this help"}
  };

  private final int width;
  private final int height;
  private final long frame;
  private final boolean motion;

  /** The sparkline history: the last samples of each counter, oldest first. */
  /* default */ record Series(List<Integer> running, List<Integer> queued, List<Integer> failed) {}

  /** What a frame shows; the view owns the state and hands it over. */
  /* default */ record Model(
      ConsoleView.Snapshot snap,
      ConsoleView.Page page,
      Duration uptime,
      String version,
      String url,
      Level level,
      IntFunction<List<ConsoleLogs.Line>> logs,
      Series series,
      long busySeconds,
      Predicate<ConsoleView.Run> flashed,
      int slide) {}

  /* default */ ConsolePainter(
      final int width, final int height, final long frameMs, final boolean motion) {
    this.width = width;
    this.height = height;
    this.frame = motion ? Math.floorDiv(frameMs, 100L) : 0;
    this.motion = motion;
  }

  /** The dashboard: header, status line, tabs, the page body and the key bar. */
  /* default */ Cells screen(final Model m) {
    final Cells g = new Cells(width, height);
    final String health = m.snap().health();
    header(g, m, health);
    statusLine(g, m, health);
    tabs(g, m.page());
    g.put(3, 0, "─".repeat(width), Role.SLATE);
    g.overlay(body(m, health), motion ? m.slide() : 0, TOP, height - 2);
    keybar(g, m.level());
    return g;
  }

  private void header(final Cells g, final Model m, final String health) {
    g.put(0, 0, "◉─●", i -> i == 1 ? Role.SLATE : i == 0 ? Role.BRAND : Role.ACCENT);
    g.put(0, 4, "YUKTA", i -> motion ? ConsoleTheme.gradient(frame, i) : Role.BOLD);
    final String port = m.url().substring(m.url().lastIndexOf(':'));
    final String rest = " " + m.version() + " │ " + port + " │ ";
    g.put(0, 9, rest, Role.DIM);
    final boolean up = UP.equals(health);
    final String dot = up ? (Math.floorMod(frame / 5, 2L) == 0 ? "●" : "◉") : "✗";
    g.put(0, 9 + rest.length(), dot + " " + health, up ? Role.OK : Role.FAIL);
    final String uptime =
        String.format(" │ up %dh%02dm", m.uptime().toHours(), m.uptime().toMinutesPart());
    final int at = 9 + rest.length() + 2 + health.length();
    if (at + uptime.length() <= width) {
      g.put(0, at, uptime, Role.DIM);
    }
  }

  private void statusLine(final Cells g, final Model m, final String health) {
    final ConsoleView.Snapshot s = m.snap();
    if (UP.equals(health)) {
      if (s.running() == 0) {
        g.put(1, 0, "· Idle", Role.DIM);
        g.put(1, 7, " " + s.queued() + " queued", Role.DIM);
      } else {
        final String phrase =
            "Orchestrating " + s.running() + (s.running() > 1 ? " runs…" : " run…");
        g.put(1, 0, motion ? ConsoleTheme.star(frame) : "*", Role.ACCENT);
        g.put(
            1,
            2,
            phrase,
            i -> motion ? ConsoleTheme.shimmer(frame, phrase.length(), i) : Role.TEXT);
        g.put(
            1,
            3 + phrase.length(),
            "(" + elapsed(m.busySeconds()) + " · " + s.queued() + " queued)",
            Role.DIM);
      }
    } else {
      g.put(1, 0, "✗ Server unreachable", Role.FAIL);
      g.put(1, 21, " retrying…", Role.DIM);
    }
  }

  private static String elapsed(final long seconds) {
    return seconds < 60 ? seconds + "s" : String.format("%dm%02ds", seconds / 60, seconds % 60);
  }

  private static void tabs(final Cells g, final ConsoleView.Page page) {
    int col = 0;
    for (final ConsoleView.Page p : ConsoleView.Page.values()) {
      final String label = " " + p.label() + " ";
      g.put(TAB_ROW, col, label, p == page ? Role.TAB : Role.DIM);
      col += label.length() + 1;
    }
  }

  /** The tab drawn at column {@code at}, or null between tabs. */
  /* default */ static ConsoleView.Page tabAt(final int at) {
    int col = 0;
    for (final ConsoleView.Page p : ConsoleView.Page.values()) {
      final int end = col + p.label().length() + 2;
      if (at >= col && at < end) {
        return p;
      }
      col = end + 1;
    }
    return null;
  }

  private void keybar(final Cells g, final Level level) {
    final String[][] items = {
      {"q", "Quit"},
      {"d", "Dash"},
      {"l", "Logs"},
      {"r", "Runs"},
      {"?", "Help"},
      {"f", level.toString()},
      {"↑↓", "Scroll"},
      {"m", "Mouse"}
    };
    int col = 0;
    for (final String[] item : items) {
      final int w = item[0].length() + 2 + item[1].length() + 2;
      if (col + w > width) {
        break;
      }
      g.put(height - 1, col, " " + item[0] + " ", Role.CHIP);
      g.put(height - 1, col + item[0].length() + 2, " " + item[1], Role.DIM);
      col += w;
    }
  }

  private Cells body(final Model m, final String health) {
    final Cells g = new Cells(width, height);
    final int bottom = height - 2;
    switch (m.page()) {
      case RUNS ->
          runsTable(
              g,
              TOP,
              m.snap().runs(),
              Math.min(m.snap().runs().size(), bottom - TOP - 1),
              m.flashed());
      case LOGS -> logPane(g, TOP, bottom - TOP + 1, m);
      case HELP -> {
        for (int i = 0; i < HELP.length; i++) {
          g.put(TOP + i, 0, " " + HELP[i][0] + " ", Role.CHIP);
          g.put(TOP + i, HELP[i][0].length() + 3, " " + HELP[i][1], Role.TEXT);
        }
      }
      default -> dashboard(g, m, UP.equals(health), bottom);
    }
    return g;
  }

  private void dashboard(final Cells g, final Model m, final boolean up, final int bottom) {
    final ConsoleView.Snapshot s = m.snap();
    int r = TOP;
    if (up) {
      if (height < 20 || width < 60) {
        g.put(
            r,
            0,
            "running " + s.running() + "  queued " + s.queued() + "  failed " + s.failed(),
            Role.TEXT);
        r += 1;
      } else {
        meter(g, r, "Running", s.running(), Role.RUN, m.series().running());
        meter(g, r + 1, "Queued", s.queued(), Role.WARN, m.series().queued());
        meter(g, r + 2, "Failed", s.failed(), Role.FAIL, m.series().failed());
        r += 4;
      }
    } else {
      g.put(r, 0, "No data: the server is not answering", Role.DIM);
      r += 2;
    }
    g.put(r, 0, "─".repeat(width), Role.SLATE);
    r += 1;
    if (up) {
      final int n = Math.min(5, Math.max(2, (bottom - r) / 3));
      runsTable(g, r, s.runs(), n, m.flashed());
      r += n + 2;
    }
    if (r < bottom - 1) {
      g.put(r - 1, 0, "─".repeat(width), Role.SLATE);
      logPane(g, r, bottom - r + 1, m);
    }
  }

  private void meter(
      final Cells g,
      final int row,
      final String name,
      final int value,
      final Role role,
      final List<Integer> samples) {
    g.put(row, 0, String.format("%-8s", name), Role.DIM);
    g.put(row, 8, "[", Role.SLATE);
    g.put(row, 9, ConsoleTheme.meter(value, Math.max(8, value), 18), role);
    g.put(row, 27, "]", Role.SLATE);
    g.put(row, 29, String.format("%2d", value), Role.BOLD);
    g.put(row, 33, ConsoleTheme.sparkline(samples), role);
  }

  private void runsTable(
      final Cells g,
      final int row,
      final List<ConsoleView.Run> runs,
      final int count,
      final Predicate<ConsoleView.Run> flashed) {
    g.put(row, 0, "  WORKFLOW", Role.BOLD);
    g.put(row, 26, "STATUS", Role.BOLD);
    g.put(row, 38, "STARTED", Role.BOLD);
    g.put(row, 50, "BY", Role.BOLD);
    if (runs.isEmpty()) {
      g.put(row + 1, 0, "no runs yet", Role.DIM);
    }
    for (int i = 0; i < Math.min(count, runs.size()); i++) {
      final ConsoleView.Run run = runs.get(i);
      final int y = row + 1 + i;
      final Role role = statusRole(run.status());
      g.put(y, 0, glyph(run.status()), role);
      g.put(y, 2, ConsoleView.clean(String.format("%-24.23s", run.workflow())), Role.TEXT);
      g.put(y, 26, String.format("%-10s", run.status()), role);
      g.put(y, 38, ConsoleView.clean(run.started()), Role.TEXT);
      g.put(y, 50, ConsoleView.clean(run.initiator()), Role.DIM);
      if (motion && flashed.test(run)) {
        g.shadeBlanks(y, Role.FLASH);
      } else if (motion && i % 2 != 0) {
        g.shadeBlanks(y, Role.ZEBRA);
      }
    }
  }

  private static Role statusRole(final String status) {
    return switch (status) {
      case "RUNNING" -> Role.RUN;
      case "SUCCESS" -> Role.OK;
      case "FAILURE", "TIMED_OUT" -> Role.FAIL;
      default -> Role.WARN;
    };
  }

  private String glyph(final String status) {
    return switch (status) {
      case "RUNNING" -> motion ? ConsoleTheme.braille(frame) : "*";
      case "SUCCESS" -> "●";
      case "FAILURE", "TIMED_OUT" -> "✗";
      case "QUEUED" -> "◌";
      default -> "‖";
    };
  }

  private void logPane(final Cells g, final int row, final int rows, final Model m) {
    if (rows <= 0) {
      return;
    }
    final List<ConsoleLogs.Line> lines = m.logs().apply(rows);
    for (int i = 0; i < lines.size(); i++) {
      final ConsoleLogs.Line l = lines.get(i);
      g.put(row + i, 0, ConsoleView.clean(l.text()), logRole(l.level()));
    }
  }

  private static Role logRole(final Level level) {
    if (level.isGreaterOrEqual(Level.ERROR)) {
      return Role.FAIL;
    }
    if (level.isGreaterOrEqual(Level.WARN)) {
      return Role.WARN;
    }
    return level.isGreaterOrEqual(Level.INFO) ? Role.TEXT : Role.DIM;
  }

  /** The welcome box shown while the first answer from the server is awaited. */
  /* default */ Cells splash(final String version, final String url, final long frameMs) {
    final Cells g = new Cells(width, height);
    final boolean big = width >= 64;
    final int boxW = big ? 60 : width - 2;
    final int boxH = big ? 14 : 6;
    final int x0 = (width - boxW) / 2;
    final int y0 = Math.max(0, (height - boxH) / 2);
    g.put(y0, x0, "╭" + "─".repeat(boxW - 2) + "╮", Role.SLATE);
    for (int i = 1; i < boxH - 1; i++) {
      g.put(y0 + i, x0, "│", Role.SLATE);
      g.put(y0 + i, x0 + boxW - 1, "│", Role.SLATE);
    }
    g.put(y0 + boxH - 1, x0, "╰" + "─".repeat(boxW - 2) + "╯", Role.SLATE);
    g.put(y0 + 1, x0 + 2, ConsoleTheme.star(frame), Role.ACCENT);
    g.put(y0 + 1, x0 + 4, "Welcome to Yukta " + version, Role.BOLD);
    if (big) {
      g.put(y0 + 3, x0 + 2, "◉─●", i -> i == 1 ? Role.SLATE : i == 0 ? Role.BRAND : Role.ACCENT);
      g.put(y0 + 4, x0 + 2, "│ ╲", Role.SLATE);
      g.put(y0 + 5, x0 + 2, "●──◉", i -> i == 0 ? Role.ACCENT : i == 3 ? Role.BRAND : Role.SLATE);
      final String[] banner = ConsoleBanner.LINES;
      for (int i = 0; i < banner.length; i++) {
        final int line = i;
        g.put(y0 + 3 + i, x0 + 8, banner[i], j -> ConsoleTheme.gradient(frame, j + line));
      }
      g.put(y0 + 10, x0 + 2, "workflow orchestration", Role.DIM);
    } else {
      g.put(y0 + 2, x0 + 2, "YUKTA", i -> ConsoleTheme.gradient(frame, i));
    }
    g.put(y0 + boxH - 3, x0 + 2, url, Role.TEXT);
    final int n = 12;
    final double p = Math.min(1.0, Math.floorMod(frameMs, 3200L) / 2400.0);
    final int on = (int) Math.round(p * n);
    g.put(y0 + boxH - 2, x0 + 2, "▰".repeat(on) + "▱".repeat(n - on), Role.BRAND);
    g.put(y0 + boxH - 2, x0 + 3 + n, p < 1 ? "starting" : "ready", Role.DIM);
    return g;
  }
}
