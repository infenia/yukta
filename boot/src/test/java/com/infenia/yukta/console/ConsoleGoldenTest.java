// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.console;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import com.infenia.yukta.console.ConsoleTheme.Role;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jline.utils.AttributedString;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Compares the console with the approved mockup (docs: the console design artifact), cell for cell:
 * {@code src/test/resources/console/<screen>-<cols>x<rows>-t<ms>[-plain].txt} holds the characters
 * and {@code .roles} one role letter per cell.
 */
class ConsoleGoldenTest {

  private static final Pattern NAME = Pattern.compile("(\\w+)-(\\d+)x(\\d+)-t(\\d+)(-plain)?");
  private static final String ROLE_LETTERS = ".dbasofwrBCTzFS01234567";

  private static final List<ConsoleView.Run> RUNS =
      List.of(
          run("nightly-build", "RUNNING", "10:41:07", "ci-bot"),
          run("deploy-staging", "RUNNING", "10:42:30", "arun"),
          run("lint-and-test", "RUNNING", "10:43:02", "arun"),
          run("data-sync", "QUEUED", "10:43:15", "cron"),
          run("quality-gate", "QUEUED", "10:43:20", "mcp"),
          run("release-notes", "SUCCESS", "10:38:11", "arun"),
          run("backup-db", "FAILURE", "10:30:00", "cron"),
          run("docs-publish", "SUCCESS", "10:21:44", "ci-bot"),
          run("index-search", "PAUSED", "10:12:08", "arun"));

  // the sparkline history the mockup draws, oldest first; the last sample is the current count
  private static final int[] RUNNING = {1, 1, 2, 3, 4, 6, 7, 5, 3, 2, 2, 3, 4, 3};
  private static final int[] QUEUED = {0, 1, 1, 2, 3, 3, 2, 1, 1, 2, 3, 2, 2, 2};
  private static final int[] FAILED = {0, 0, 0, 1, 1, 1, 1, 0, 0, 0, 1, 1, 1, 1};

  private static ConsoleView.Run run(
      final String workflow, final String status, final String started, final String by) {
    return new ConsoleView.Run(workflow, status, started, by);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "splash-80x24-t0",
        "splash-80x24-t1200",
        "splash-120x40-t0",
        "splash-40x12-t0",
        "dashboard-80x24-t0",
        "dashboard-80x24-t300",
        "dashboard-80x24-t0-plain",
        "dashboard-120x40-t0",
        "dashboard-40x12-t0",
        "idle-80x24-t0",
        "down-80x24-t0",
        "runs-80x24-t0",
        "logs-80x24-t0",
        "help-80x24-t0"
      })
  void matchesTheApprovedMockup(final String name) throws IOException {
    final Matcher m = NAME.matcher(name);
    assertThat(m.matches()).isTrue();
    final String screen = m.group(1);
    final int cols = Integer.parseInt(m.group(2));
    final int rows = Integer.parseInt(m.group(3));
    final long t = Long.parseLong(m.group(4));
    final boolean plain = m.group(5) != null;

    final List<AttributedString> actual = draw(screen, cols, rows, t, plain);

    final List<String> text = resource(name + ".txt");
    final List<String> roles = resource(name + ".roles");
    final List<String> diffs = new ArrayList<>();
    assertThat(actual).hasSize(rows);
    for (int r = 0; r < rows; r++) {
      final AttributedString line = actual.get(r);
      for (int c = 0; c < cols; c++) {
        final char got = line.charAt(c);
        final char want = text.get(r).charAt(c);
        final char gotRole = plain ? '.' : letter(line, c);
        final char wantRole = plain ? '.' : roles.get(r).charAt(c);
        if (got != want || gotRole != wantRole) {
          diffs.add(
              "row %d col %d: expected '%c' role %c, got '%c' role %c"
                  .formatted(r, c, want, wantRole, got, gotRole));
        }
      }
    }
    assertThat(diffs).as(name).isEmpty();
  }

  private static List<AttributedString> draw(
      final String screen, final int cols, final int rows, final long t, final boolean plain) {
    final ConsoleLogs logs = new ConsoleLogs();
    logs.add(Level.INFO, "10:43:20.502 INFO  RunService : run 7f3a started by mcp");
    logs.add(Level.INFO, "10:43:21.017 INFO  Node : lint-and-test/compile finished in 4.2s");
    logs.add(Level.WARN, "10:43:22.140 WARN  Retry : data-sync attempt 2 of 3");
    logs.add(Level.INFO, "10:43:23.900 INFO  Node : deploy-staging/push finished in 1.8s");
    logs.add(Level.ERROR, "10:43:24.310 ERROR Postgres : connection reset, retrying");
    logs.add(Level.INFO, "10:43:25.002 INFO  RunService : run 91bc queued");
    logs.add(Level.INFO, "10:43:26.114 INFO  Node : nightly-build/test finished in 31.0s");
    final ConsoleView view =
        new ConsoleView("0.1.0", "http://127.0.0.1:9858", logs, !plain, !plain);
    final Duration uptime = Duration.ofMinutes(12);
    if ("splash".equals(screen)) {
      return view.render(ConsoleView.Snapshot.NONE, uptime, cols, rows, t);
    }
    switch (screen) {
      case "runs" -> view.onKey("r");
      case "logs" -> view.onKey("l");
      case "help" -> view.onKey("?");
      default -> {
        // the dashboard
      }
    }
    final ConsoleView.Snapshot main =
        switch (screen) {
          case "idle" -> new ConsoleView.Snapshot("UP", 0, 0, 1, RUNS.subList(5, 9));
          case "down" -> new ConsoleView.Snapshot("DOWN", 0, 0, 0, List.of());
          default -> new ConsoleView.Snapshot("UP", 3, 2, 1, RUNS);
        };
    // twelve seconds of history: one snapshot a second, ending with the one drawn
    for (int i = 0; i < RUNNING.length - 1; i++) {
      view.render(
          new ConsoleView.Snapshot("UP", RUNNING[i], QUEUED[i], FAILED[i], main.runs()),
          uptime,
          cols,
          rows,
          -12_000);
    }
    return view.render(main, uptime, cols, rows, t);
  }

  private static char letter(final AttributedString line, final int col) {
    for (final Role role : Role.values()) {
      if (role.style().equals(line.styleAt(col))) {
        return ROLE_LETTERS.charAt(role.ordinal());
      }
    }
    return '?';
  }

  private static List<String> resource(final String file) throws IOException {
    try (InputStream in = ConsoleGoldenTest.class.getResourceAsStream("/console/" + file)) {
      assertThat(in).as(file).isNotNull();
      return List.of(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n", -1));
    }
  }
}
