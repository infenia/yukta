// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.console;

import ch.qos.logback.classic.Level;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;

/** The console's state, key handling and screen layout; no terminal here, so it is testable. */
@SuppressWarnings({
  "PMD.TooManyMethods",
  "PMD.CommentDefaultAccessModifier",
  "PMD.FieldDeclarationsShouldBeAtStartOfClass",
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.CyclomaticComplexity",
  "PMD.NPathComplexity"
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

  private static final AttributedStyle BOLD = AttributedStyle.BOLD;
  private static final AttributedStyle DIM = AttributedStyle.DEFAULT.faint();
  private static final AttributedStyle RED =
      AttributedStyle.DEFAULT.foreground(AttributedStyle.RED);
  private static final AttributedStyle GREEN =
      AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN);
  private static final AttributedStyle YELLOW =
      AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW);
  private static final AttributedStyle CYAN =
      AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN);
  private static final AttributedStyle PLAIN = AttributedStyle.DEFAULT;

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
  private final boolean color;

  private Page page = Page.DASHBOARD;
  private int levelIndex = 2; // INFO
  private int scroll; // lines up from the newest; 0 follows the tail
  private boolean quit;

  /** Whether the environment asks for plain text: NO_COLOR is set (any value) or TERM is dumb. */
  /* default */ static boolean colorWanted(final java.util.Map<String, String> env) {
    final String term = env.get("TERM");
    return !env.containsKey("NO_COLOR") && term != null && !"dumb".equals(term);
  }

  /* default */ ConsoleView(
      final String version, final String url, final ConsoleLogs logs, final boolean color) {
    this.color = color;
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

  /** Applies a key: a character, or one of the names UP DOWN PGUP PGDN HOME END. */
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
      case "G", "END" -> scroll = 0;
      case "HOME" -> scroll = Integer.MAX_VALUE / 2;
      default -> {
        // ignored
      }
    }
  }

  private void toggle(final Page target) {
    page = page == target ? Page.DASHBOARD : target;
    scroll = 0;
  }

  /** The screen as {@code height} lines of at most {@code width} columns. */
  /* default */ List<AttributedString> render(
      final Snapshot s, final Duration uptime, final int cols, final int rows) {
    // a terminal can report no size (0x0) before it is sized: draw for 80x24 then
    final int width = cols > 0 ? cols : 80;
    final int height = Math.max(rows > 0 ? rows : 24, 5);
    final List<AttributedString> out = new ArrayList<>();
    out.add(
        cat(
            text("Yukta " + version, BOLD),
            text("  " + url + "  up " + fmt(uptime) + "  health "),
            text(s.health(), "UP".equals(s.health()) ? GREEN : RED)));
    out.add(
        cat(
            text("runs: running "),
            text(String.valueOf(s.running()), CYAN),
            text("  queued "),
            text(String.valueOf(s.queued()), s.queued() > 0 ? YELLOW : PLAIN),
            text("  failed(24h) "),
            text(String.valueOf(s.failed()), s.failed() > 0 ? RED : PLAIN)));
    out.add(text("-".repeat(width)));
    final int body = height - out.size() - 1;
    switch (page) {
      case HELP -> out.addAll(help());
      case RUNS -> out.addAll(runs(s, body));
      case LOGS -> out.addAll(logLines(body));
      default -> {
        final int runRows = Math.min(5, Math.max(1, body / 3));
        out.addAll(runs(s, runRows));
        out.add(text("-".repeat(width)));
        out.addAll(logLines(height - out.size() - 1));
      }
    }
    while (out.size() < height - 1) {
      out.add(AttributedString.EMPTY);
    }
    final List<AttributedString> framed = new ArrayList<>(out.subList(0, height - 1));
    framed.add(
        text("q quit  l logs  r runs  ? help  f level>=" + level() + "  ↑↓ scroll  m mouse", DIM));
    return framed.stream().map(l -> color ? clip(l, width) : plain(clip(l, width))).toList();
  }

  private static AttributedString text(final String text) {
    return new AttributedString(clean(text));
  }

  private static AttributedString text(final String text, final AttributedStyle style) {
    return new AttributedString(clean(text), style);
  }

  private static AttributedString cat(final AttributedString... parts) {
    return AttributedString.join(AttributedString.EMPTY, parts);
  }

  private static AttributedString plain(final AttributedString line) {
    return new AttributedString(line.toString());
  }

  /** Log text can carry escape sequences or tabs; they would move the cursor, so they go. */
  private static String clean(final String text) {
    return text.replaceAll("\033\\[[0-9;?]*[a-zA-Z]", "").replaceAll("[\\p{Cntrl}]", " ");
  }

  private static List<AttributedString> runs(final Snapshot s, final int rows) {
    final List<AttributedString> out = new ArrayList<>();
    out.add(
        text(String.format("%-28s %-10s %-20s %s", "WORKFLOW", "STATUS", "STARTED", "BY"), BOLD));
    for (final Run r : s.runs().stream().limit(Math.max(0, rows - 1)).toList()) {
      out.add(
          cat(
              text(String.format("%-28s ", r.workflow())),
              text(String.format("%-10s", r.status()), statusColor(r.status())),
              text(String.format(" %-20s %s", r.started(), r.initiator()))));
    }
    if (s.runs().isEmpty()) {
      out.add(text("no runs yet", DIM));
    }
    return out;
  }

  private static AttributedStyle statusColor(final String status) {
    return switch (status) {
      case "RUNNING" -> CYAN;
      case "SUCCESS" -> GREEN;
      case "FAILURE", "TIMED_OUT" -> RED;
      case "QUEUED", "PAUSED" -> YELLOW;
      default -> PLAIN;
    };
  }

  private List<AttributedString> logLines(final int rows) {
    if (rows <= 0) {
      return List.of();
    }
    final List<ConsoleLogs.Line> all = logs.snapshot(level());
    final int end = Math.max(0, all.size() - Math.min(scroll, Math.max(0, all.size() - 1)));
    final int start = Math.max(0, end - rows);
    return all.subList(start, end).stream().map(ConsoleView::colour).toList();
  }

  private static AttributedString colour(final ConsoleLogs.Line l) {
    if (l.level().isGreaterOrEqual(Level.ERROR)) {
      return text(l.text(), RED);
    }
    if (l.level().isGreaterOrEqual(Level.WARN)) {
      return text(l.text(), YELLOW);
    }
    return l.level().isGreaterOrEqual(Level.INFO) ? text(l.text()) : text(l.text(), DIM);
  }

  private static List<AttributedString> help() {
    return Stream.of(
            "q   quit the server",
            "l   full logs (toggle)",
            "r   runs (toggle)",
            "f   cycle the log level filter",
            "↑ ↓ PgUp PgDn Home   scroll the logs, G or End to follow the tail",
            "m   mouse wheel scrolling (toggle; off when the terminal has no mouse)",
            "?   this help")
        .map(ConsoleView::text)
        .toList();
  }

  private static String fmt(final Duration d) {
    return String.format("%dh%02dm%02ds", d.toHours(), d.toMinutesPart(), d.toSecondsPart());
  }

  /** Cuts a line to {@code width} terminal columns (wide characters count two). */
  /* default */ static AttributedString clip(final AttributedString line, final int width) {
    return line.columnLength() <= width ? line : line.columnSubSequence(0, width);
  }
}
