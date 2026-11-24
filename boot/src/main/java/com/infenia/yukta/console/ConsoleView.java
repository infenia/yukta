// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.console;

import ch.qos.logback.classic.Level;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jline.terminal.Size;
import org.jline.utils.AttributedString;

/** The console's state and key handling; drawing is {@link ConsolePainter}. No terminal here. */
@SuppressWarnings({
  "PMD.TooManyMethods",
  "PMD.CommentDefaultAccessModifier",
  "PMD.FieldDeclarationsShouldBeAtStartOfClass",
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.CyclomaticComplexity"
})
/* default */ final class ConsoleView {

  /** What the screen shows. */
  /* default */ enum Page {
    DASHBOARD("Dashboard"),
    RUNS("Runs"),
    LOGS("Logs"),
    HELP("Help");

    private final String label;

    Page(final String label) {
      this.label = label;
    }

    /* default */ String label() {
      return label;
    }
  }

  /** Levels the {@code f} key cycles through. */
  private static final List<Level> LEVELS =
      List.of(Level.ALL, Level.DEBUG, Level.INFO, Level.WARN, Level.ERROR);

  private static final int SAMPLES = 14;
  private static final long SPLASH_MIN_MS = 900;
  private static final long SPLASH_MAX_MS = 3000;
  private static final long FLASH_MS = 600;
  private static final double SLIDE_MS = 200;
  private static final int SLIDE_COLUMNS = 8;
  private static final int SPLASH_MIN_ROWS = 12;
  private static final int MAX_TRACKED_RUNS = 500;

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
  private final boolean motion;

  private final AtomicReference<Page> page = new AtomicReference<>(Page.DASHBOARD);
  private final AtomicInteger levelIndex = new AtomicInteger(2); // INFO
  // lines up from the newest; 0 follows the tail
  private final AtomicInteger scroll = new AtomicInteger();
  private final AtomicBoolean quit = new AtomicBoolean();

  // what the frames remember: only the render loop touches these, keys only set splashDismissed
  private final AtomicReference<Long> firstFrame = new AtomicReference<>();
  private final AtomicBoolean splashDismissed = new AtomicBoolean();
  private final AtomicBoolean seenSnapshot = new AtomicBoolean();
  private final AtomicReference<Snapshot> lastSnapshot = new AtomicReference<>(Snapshot.NONE);
  private final Deque<Integer> running = new ArrayDeque<>();
  private final Deque<Integer> queued = new ArrayDeque<>();
  private final Deque<Integer> failed = new ArrayDeque<>();
  private final AtomicBoolean busy = new AtomicBoolean();
  private final AtomicReference<Long> busySince = new AtomicReference<>(0L);
  private final AtomicBoolean pageChanged = new AtomicBoolean();
  private final AtomicBoolean sliding = new AtomicBoolean();
  private final AtomicReference<Long> slideStart = new AtomicReference<>(0L);
  private final Map<String, String> statusOf = new ConcurrentHashMap<>();
  private final Map<String, Long> changedAt = new ConcurrentHashMap<>();

  // a fresh pty reports 0x0 and JLine would then allocate a negative array: use 80x24
  /* default */ static Size fit(final Size size) {
    return size.getRows() > 0 && size.getColumns() > 0 ? size : new Size(80, 24);
  }

  /** Whether the environment asks for colour: NO_COLOR is not set (any value) and TERM not dumb. */
  /* default */ static boolean colorWanted(final Map<String, String> env) {
    final String term = env.get("TERM");
    return !env.containsKey("NO_COLOR") && term != null && !"dumb".equals(term);
  }

  /** Whether spinners, the splash and the other motion are wanted: colour, and not switched off. */
  /* default */ static boolean motionWanted(final Map<String, String> env) {
    return colorWanted(env) && !"off".equalsIgnoreCase(env.get("YUKTA_CONSOLE_ANIMATION"));
  }

  /* default */ ConsoleView(
      final String version,
      final String url,
      final ConsoleLogs logs,
      final boolean color,
      final boolean motion) {
    this.color = color;
    this.motion = motion;
    this.version = version;
    this.url = url;
    this.logs = logs;
  }

  /* default */ boolean quit() {
    return quit.get();
  }

  /* default */ Page page() {
    return page.get();
  }

  /* default */ Level level() {
    return LEVELS.get(levelIndex.get());
  }

  /** Whether the runs page wants a longer list than the dashboard. */
  /* default */ boolean wantsManyRuns() {
    return page.get() == Page.RUNS;
  }

  /** Applies a key: a character, or one of the names UP DOWN PGUP PGDN HOME END. */
  /* default */ void onKey(final String key) {
    splashDismissed.set(true); // any key ends the welcome box, and is still applied
    switch (key) {
      case "q" -> quit.set(true);
      case "d" -> show(Page.DASHBOARD);
      case "LEFT" -> show(Page.values()[Math.floorMod(page().ordinal() - 1, Page.values().length)]);
      case "RIGHT" -> show(Page.values()[(page().ordinal() + 1) % Page.values().length]);
      case "l" -> toggle(Page.LOGS);
      case "r" -> toggle(Page.RUNS);
      case "?" -> toggle(Page.HELP);
      case "f" -> levelIndex.updateAndGet(i -> (i + 1) % LEVELS.size());
      case "UP" -> scroll.incrementAndGet();
      case "DOWN" -> scroll.updateAndGet(v -> Math.max(0, v - 1));
      case "PGUP" -> scroll.addAndGet(10);
      case "PGDN" -> scroll.updateAndGet(v -> Math.max(0, v - 10));
      case "G", "END" -> scroll.set(0);
      case "HOME" -> scroll.set(Integer.MAX_VALUE / 2);
      default -> {
        // ignored
      }
    }
  }

  private void toggle(final Page target) {
    show(page.get() == target ? Page.DASHBOARD : target);
  }

  private void show(final Page target) {
    if (page.getAndSet(target) != target) {
      scroll.set(0);
      pageChanged.set(true);
    }
  }

  /** A left click on the cell {@code col}, {@code row} (from 0): a click on a tab opens it. */
  /* default */ void onClick(final int col, final int row) {
    splashDismissed.set(true);
    final Page tab = row == ConsolePainter.TAB_ROW ? ConsolePainter.tabAt(col) : null;
    if (tab != null) {
      show(tab);
    }
  }

  /**
   * The screen as {@code rows} lines of {@code cols} columns, at {@code frameMs} milliseconds into
   * the run (animation is a pure function of it).
   */
  /* default */ List<AttributedString> render(
      final Snapshot s, final Duration uptime, final int cols, final int rows, final long frameMs) {
    // a terminal can report no size (0x0) before it is sized: draw for 80x24 then
    final int width = cols > 0 ? cols : 80;
    final int height = Math.max(rows > 0 ? rows : 24, 5);
    observe(s, frameMs);
    final ConsolePainter painter = new ConsolePainter(width, height, frameMs, motion);
    return (splashing(frameMs, height)
            ? painter.splash(version, url, frameMs)
            : painter.screen(model(s, uptime, frameMs)))
        .lines(color);
  }

  private void observe(final Snapshot s, final long frameMs) {
    firstFrame.compareAndSet(null, frameMs);
    // each poll makes a new snapshot object: compareAndSet tests for that same object (identity)
    if (!lastSnapshot.compareAndSet(s, s)) {
      lastSnapshot.set(s);
      if (s != Snapshot.NONE) {
        seenSnapshot.set(true);
        sample(running, s.running());
        sample(queued, s.queued());
        sample(failed, s.failed());
        trackStatuses(s, frameMs);
      }
    }
    final boolean active = s.running() > 0;
    if (active && !busy.get()) {
      busySince.set(frameMs);
    }
    busy.set(active);
    if (pageChanged.getAndSet(false)) {
      sliding.set(true);
      slideStart.set(frameMs);
    }
  }

  private static void sample(final Deque<Integer> series, final int value) {
    if (series.size() == SAMPLES) {
      series.removeFirst();
    }
    series.addLast(value);
  }

  private void trackStatuses(final Snapshot s, final long frameMs) {
    if (statusOf.size() > MAX_TRACKED_RUNS) {
      statusOf.clear();
      changedAt.clear();
    }
    for (final Run r : s.runs()) {
      final String previous = statusOf.put(runKey(r), r.status());
      if (previous != null && !previous.equals(r.status())) {
        changedAt.put(runKey(r), frameMs);
      }
    }
  }

  private static String runKey(final Run r) {
    return r.workflow() + '|' + r.started();
  }

  private boolean splashing(final long frameMs, final int height) {
    final long shown = frameMs - firstFrame.get();
    return motion
        && height >= SPLASH_MIN_ROWS
        && !splashDismissed.get()
        && shown < SPLASH_MAX_MS
        && (!seenSnapshot.get() || shown < SPLASH_MIN_MS);
  }

  private ConsolePainter.Model model(final Snapshot s, final Duration uptime, final long frameMs) {
    int slide = 0;
    if (sliding.get()) {
      final double left = 1 - (frameMs - slideStart.get()) / SLIDE_MS;
      slide = left > 0 ? (int) Math.round(left * SLIDE_COLUMNS) : 0;
      sliding.set(left > 0);
    }
    return new ConsolePainter.Model(
        s,
        page.get(),
        uptime,
        version,
        url,
        level(),
        this::logLines,
        new ConsolePainter.Series(List.copyOf(running), List.copyOf(queued), List.copyOf(failed)),
        busy.get() ? (frameMs - busySince.get()) / 1000 : 0,
        r -> {
          final Long at = changedAt.get(runKey(r));
          return at != null && frameMs - at < FLASH_MS;
        },
        slide);
  }

  private List<ConsoleLogs.Line> logLines(final int rows) {
    final List<ConsoleLogs.Line> all = logs.snapshot(level());
    final int end = Math.max(0, all.size() - Math.min(scroll.get(), Math.max(0, all.size() - 1)));
    final int start = Math.max(0, end - rows);
    return all.subList(start, end);
  }

  /** Log text can carry escape sequences or tabs; they would move the cursor, so they go. */
  /* default */ static String clean(final String text) {
    return text.replaceAll("\033\\[[0-9;?]*[a-zA-Z]", "").replaceAll("[\\p{Cntrl}]", " ");
  }
}
