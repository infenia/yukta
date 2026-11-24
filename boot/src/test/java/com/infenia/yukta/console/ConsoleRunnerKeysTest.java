// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.console;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;

/** Drives the real key loop through a fake terminal: no native build, no real tty. */
class ConsoleRunnerKeysTest {

  private final ConsoleView view =
      new ConsoleView("1.2", "http://h:1", new ConsoleLogs(), false, false);

  @Test
  void keysWorkWhileTheServerIsSlow() throws Exception {
    final PipedOutputStream keys = new PipedOutputStream();
    try (Terminal term =
        TerminalBuilder.builder()
            .streams(new PipedInputStream(keys), new ByteArrayOutputStream())
            .type("xterm-256color")
            .build()) {
      final CompletableFuture<Void> run =
          CompletableFuture.runAsync(() -> {
            try {
              ConsoleRunner.loop(
                  term,
                  view,
                  limit -> {
                    sleep(5000); // a hung server must not hold up q
                    return ConsoleView.Snapshot.NONE;
                  });
            } catch (java.io.IOException e) {
              throw new IllegalStateException(e);
            }
          });
      send(keys, "l");
      Awaitility.await()
          .atMost(2, TimeUnit.SECONDS)
          .until(() -> view.page() == ConsoleView.Page.LOGS);
      send(keys, "\033[<0;5;5M\033[A\033[Bq"); // mouse (SGR), up, down, then q
      run.get(2, TimeUnit.SECONDS);
      assertThat(view.quit()).isTrue();
    }
  }

  @Test
  void aMouseClickIsNotTakenForTheMouseKey() throws Exception {
    final PipedOutputStream keys = new PipedOutputStream();
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (Terminal term =
        TerminalBuilder.builder()
            .streams(new PipedInputStream(keys), out)
            .type("xterm-256color")
            .build()) {
      // press ends in M, release ends in m: a stray m would switch mouse tracking off
      send(keys, "\033[<0;5;5M\033[<0;5;5m\033[<64;5;5Mq");
      ConsoleRunner.loop(term, view, limit -> ConsoleView.Snapshot.NONE);
      term.flush();
    }
    final String screen = out.toString(StandardCharsets.UTF_8);
    assertThat(screen.split("\033\\[\\?1000l", -1).length - 1).isEqualTo(1);
  }

  private static void send(final PipedOutputStream out, final String s) throws Exception {
    out.write(s.getBytes(StandardCharsets.UTF_8));
    out.flush();
  }

  private static void sleep(final long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
