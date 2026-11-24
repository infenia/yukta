// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.console;

import ch.qos.logback.classic.Level;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

/**
 * Runs the real console on your terminal with fake runs and logs, with no server and no native
 * build: {@code yk gradle :boot:consolePreviewArgs}, then {@code java
 * @boot/build/console-preview.args [--slow] [--down]}. {@code --slow} makes every poll take five
 * seconds (keys must still answer), {@code --down} reports a dead server. {@code NO_COLOR} and
 * {@code YUKTA_CONSOLE_ANIMATION=off} apply as in the real console.
 */
public final class ConsolePreview {

  private static final String[] WORKFLOWS = {
    "nightly-build", "deploy-staging", "lint-and-test", "data-sync", "quality-gate",
    "release-notes", "backup-db", "docs-publish", "index-search", "cache-warmup"
  };

  private ConsolePreview() {}

  /** Opens the system terminal and runs the dashboard until q. */
  public static void main(final String[] args) throws IOException {
    final List<String> flags = List.of(args);
    final Map<String, String> env = System.getenv();
    final ConsoleLogs logs = new ConsoleLogs();
    final ConsoleView view =
        new ConsoleView(
            "0.0.0-preview",
            "http://127.0.0.1:9858",
            logs,
            ConsoleView.colorWanted(env),
            ConsoleView.motionWanted(env));
    final AtomicInteger tick = new AtomicInteger();
    try (Terminal term = TerminalBuilder.builder().system(true).build()) {
      ConsoleRunner.loop(
          term,
          view,
          limit -> {
            if (flags.contains("--slow")) {
              sleep(5000);
            }
            return flags.contains("--down")
                ? new ConsoleView.Snapshot("DOWN", 0, 0, 0, List.of())
                : snapshot(tick.getAndIncrement(), limit, logs);
          });
    }
  }

  /** Runs move queued, running, then finished, so the spinners, flashes and meters all show. */
  private static ConsoleView.Snapshot snapshot(
      final int n, final int limit, final ConsoleLogs logs) {
    final List<ConsoleView.Run> runs = new ArrayList<>();
    int running = 0;
    int queued = 0;
    int failed = 0;
    for (int i = 0; i < Math.min(limit, WORKFLOWS.length); i++) {
      final String status = statusOf(Math.floorMod(n + i * 2, 14));
      running += "RUNNING".equals(status) ? 1 : 0;
      queued += "QUEUED".equals(status) ? 1 : 0;
      failed += "FAILURE".equals(status) ? 1 : 0;
      runs.add(
          new ConsoleView.Run(WORKFLOWS[i], status, "10:%02d:%02d".formatted(i, n % 60), "arun"));
    }
    final Level[] levels = {Level.DEBUG, Level.INFO, Level.INFO, Level.WARN, Level.ERROR};
    final Level level = levels[n % levels.length];
    logs.add(level, "10:43:%02d.000 %-5s Preview : tick %d".formatted(n % 60, level, n));
    return new ConsoleView.Snapshot("UP", running, queued, failed, runs);
  }

  private static String statusOf(final int phase) {
    return phase < 2 ? "QUEUED" : phase < 9 ? "RUNNING" : phase < 12 ? "SUCCESS" : "FAILURE";
  }

  private static void sleep(final long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
