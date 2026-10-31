// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.console;

import ch.qos.logback.classic.Level;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** The console's state, key handling and screen layout; no terminal here, so it is testable. */
@SuppressWarnings({
  "PMD.TooManyMethods",
  "PMD.CommentDefaultAccessModifier",
  "PMD.FieldDeclarationsShouldBeAtStartOfClass",
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.CyclomaticComplexity",
  "PMD.AvoidLiteralsInIfCondition",
  "PMD.AvoidReassigningLoopVariables"
})
/* default */ final class ConsoleView {

  /** What the screen shows. */
  /* default */ enum Page {
    DASHBOARD,
    RUNS,
    LOGS,
    HELP
  }

  /** Levels the {@code f} key cycles through. */
  private static final List<Level> LEVELS =
      List.of(Level.ALL, Level.DEBUG, Level.INFO, Level.WARN, Level.ERROR);

  private static final String RESET = "\033[0m";
  private static final String BOLD = "\033[1m";
  private static final String DIM = "\033[2m";

  /** One row of the runs table. */
  /* default */ record Run(String workflow, String status, String started, String initiator) {}

  /** What the server last reported. */
  /* default */ record Snapshot(
      String health, int running, int queued, int failed, List<Run> runs) {
    static final Snapshot NONE = new Snapshot("?", 0, 0, 0, List.of());
  }

  private final String version;
  private final String url;
  private final ConsoleLogs logs;

  private Page page = Page.DASHBOARD;
  private int levelIndex = 2; // INFO
  private int scroll; // lines up from the newest; 0 follows the tail
  private boolean quit;

  /* default */ ConsoleView(final String version, final String url, final ConsoleLogs logs) {
    this.version = version;
    this.url = url;
    this.logs = logs;
  }

  /* default */ boolean quit() {
    return quit;
  }

  /* default */ Page page() {
    return page;
  }

  /* default */ Level level() {
    return LEVELS.get(levelIndex);
  }

  /** Whether the runs page wants a longer list than the dashboard. */
  /* default */ boolean wantsManyRuns() {
    return page == Page.RUNS;
  }

  /** Applies a key: a character, or one of the names UP DOWN PGUP PGDN. */
  /* default */ void onKey(final String key) {
    switch (key) {
      case "q" -> quit = true;
      case "l" -> toggle(Page.LOGS);
      case "r" -> toggle(Page.RUNS);
      case "?" -> toggle(Page.HELP);
      case "f" -> levelIndex = (levelIndex + 1) % LEVELS.size();
      case "UP" -> scroll++;
      case "DOWN" -> scroll = Math.max(0, scroll - 1);
      case "PGUP" -> scroll += 10;
      case "PGDN" -> scroll = Math.max(0, scroll - 10);
      case "G" -> scroll = 0;
      default -> {
        // ignored
      }
    }
  }

  private void toggle(final Page target) {
    page = page == target ? Page.DASHBOARD : target;
    scroll = 0;
  }

  /** The screen as {@code height} lines of at most {@code width} visible characters. */
  /* default */ List<String> render(
      final Snapshot s, final Duration uptime, final int width, final int height) {
    final List<String> out = new ArrayList<>();
    out.add(
        BOLD
            + "Yukta "
            + version
            + RESET
            + "  "
            + url
            + "  up "
            + fmt(uptime)
            + "  health "
            + s.health());
    out.add(
        "runs: running " + s.running() + "  queued " + s.queued() + "  failed(24h) " + s.failed());
    out.add("-".repeat(Math.max(0, width)));
    final int body = height - out.size() - 1;
    switch (page) {
      case HELP -> out.addAll(help());
      case RUNS -> out.addAll(runs(s, body));
      case LOGS -> out.addAll(logLines(body));
      default -> {
        final int runRows = Math.min(5, Math.max(1, body / 3));
        out.addAll(runs(s, runRows));
        out.add("-".repeat(Math.max(0, width)));
        out.addAll(logLines(height - out.size() - 1));
      }
    }
    while (out.size() < height - 1) {
      out.add("");
    }
    final List<String> framed = new ArrayList<>(out.subList(0, height - 1));
    framed.add(DIM + "q quit  l logs  r runs  ? help  f level>=" + level() + "  ↑↓ scroll" + RESET);
    return framed.stream().map(l -> clip(l, width)).toList();
  }

  private static List<String> runs(final Snapshot s, final int rows) {
    final List<String> out = new ArrayList<>();
    out.add(
        BOLD
            + String.format("%-28s %-10s %-20s %s", "WORKFLOW", "STATUS", "STARTED", "BY")
            + RESET);
    for (final Run r : s.runs().stream().limit(Math.max(0, rows - 1)).toList()) {
      out.add(
          String.format(
              "%-28s %-10s %-20s %s", r.workflow(), r.status(), r.started(), r.initiator()));
    }
    if (s.runs().isEmpty()) {
      out.add(DIM + "no runs yet" + RESET);
    }
    return out;
  }

  private List<String> logLines(final int rows) {
    if (rows <= 0) {
      return List.of();
    }
    final List<ConsoleLogs.Line> all = logs.snapshot(level());
    final int end = Math.max(0, all.size() - Math.min(scroll, Math.max(0, all.size() - 1)));
    final int start = Math.max(0, end - rows);
    return all.subList(start, end).stream().map(ConsoleView::colour).toList();
  }

  private static String colour(final ConsoleLogs.Line l) {
    if (l.level().isGreaterOrEqual(Level.ERROR)) {
      return "\033[31m" + l.text() + RESET;
    }
    return l.level().isGreaterOrEqual(Level.WARN) ? "\033[33m" + l.text() + RESET : l.text();
  }

  private static List<String> help() {
    return List.of(
        "q   quit the server",
        "l   full logs (toggle)",
        "r   runs (toggle)",
        "f   cycle the log level filter",
        "↑ ↓ PgUp PgDn   scroll the logs, G to follow the tail",
        "?   this help");
  }

  private static String fmt(final Duration d) {
    return String.format("%dh%02dm%02ds", d.toHours(), d.toMinutesPart(), d.toSecondsPart());
  }

  /** Cuts a line to {@code width} visible characters (escape sequences do not count). */
  /* default */ static String clip(final String line, final int width) {
    final StringBuilder sb = new StringBuilder();
    int visible = 0;
    for (int i = 0; i < line.length(); i++) {
      final char c = line.charAt(i);
      if (c == '\033') {
        final int m = line.indexOf('m', i);
        sb.append(line, i, m + 1);
        i = m;
      } else if (visible < width) {
        sb.append(c);
        visible++;
      }
    }
    return sb.append(RESET).toString();
  }
}
