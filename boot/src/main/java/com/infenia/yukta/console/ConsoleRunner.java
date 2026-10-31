// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.console;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.NonBlockingReader;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The interactive console: a full-screen dashboard on the terminal, fed by the server's own REST
 * API. Runs only in run mode {@code console}; q stops the server. The terminal is restored on exit
 * and on any failure.
 */
@Component
@ConditionalOnProperty(name = "yukta.run-mode", havingValue = "console")
@SuppressWarnings({
  "PMD.SystemPrintln",
  "PMD.AvoidCatchingGenericException",
  "PMD.DoNotTerminateVM",
  "PMD.CloseResource",
  "PMD.ConfusingTernary",
  "PMD.AvoidLiteralsInIfCondition",
  "PMD.AvoidInstantiatingObjectsInLoops"
})
public class ConsoleRunner implements ApplicationListener<ApplicationReadyEvent> {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private static final int TICK_MS = 500;

  @Override
  public void onApplicationEvent(final ApplicationReadyEvent event) {
    final ConfigurableApplicationContext ctx = event.getApplicationContext();
    final String port = ctx.getEnvironment().getProperty("local.server.port", "9858");
    final String key = ctx.getEnvironment().getProperty("yukta.api-key", "");
    final String version =
        ctx.getBeanProvider(BuildProperties.class).stream()
            .findFirst()
            .map(BuildProperties::getVersion)
            .orElse("dev");
    final ConsoleView view =
        new ConsoleView(version, "http://127.0.0.1:" + port, ConsoleLogs.install());
    Thread.ofPlatform()
        .name("yukta-console")
        .daemon(true)
        .start(
            () -> {
              int code = 0;
              try {
                loop(view, "http://127.0.0.1:" + port, key);
              } catch (Exception | LinkageError e) {
                System.err.println("console failed: " + e);
                code = 1;
              }
              final int exit = SpringApplication.exit(ctx, () -> 0);
              System.exit(code != 0 ? code : exit);
            });
  }

  private static void loop(final ConsoleView view, final String base, final String key)
      throws IOException {
    final Instant started = Instant.now();
    ConsoleView.Snapshot snap = ConsoleView.Snapshot.NONE;
    Instant lastPoll = Instant.EPOCH;
    try (Terminal term = TerminalBuilder.builder().system(true).build()) {
      final var saved = term.enterRawMode();
      final PrintWriter w = term.writer();
      // the terminal is put back even if the JVM is killed
      final Thread restore = new Thread(() -> w.print("\033[?25h\033[?1049l"));
      Runtime.getRuntime().addShutdownHook(restore);
      w.print("\033[?1049h\033[?25l");
      try {
        final NonBlockingReader in = term.reader();
        while (!view.quit()) {
          if (Duration.between(lastPoll, Instant.now()).toMillis() >= 1000) {
            snap = poll(base, key, view.wantsManyRuns() ? 50 : 8);
            lastPoll = Instant.now();
          }
          final var size = term.getSize();
          final List<String> lines =
              view.render(
                  snap,
                  Duration.between(started, Instant.now()),
                  size.getColumns(),
                  size.getRows());
          final StringBuilder sb = new StringBuilder("\033[H");
          for (final String l : lines) {
            sb.append(l).append("\033[K\r\n");
          }
          w.print(sb.toString().replaceAll("\r\n$", "") + "\033[J");
          w.flush();
          final String k = key(in);
          if (k != null) {
            view.onKey(k);
          }
        }
      } finally {
        Runtime.getRuntime().removeShutdownHook(restore);
        w.print("\033[?25h\033[?1049l");
        w.flush();
        term.setAttributes(saved);
        ConsoleLogs.restore();
      }
    }
  }

  /** Waits up to one tick for a key; null on timeout. */
  private static String key(final NonBlockingReader in) throws IOException {
    final int c = in.read(TICK_MS);
    if (c < 0) {
      return null;
    }
    if (c != 27) {
      return String.valueOf((char) c);
    }
    if (in.peek(50) != '[') {
      return null;
    }
    in.read();
    return switch (in.read(50)) {
      case 'A' -> "UP";
      case 'B' -> "DOWN";
      case '5' -> skipTilde(in, "PGUP");
      case '6' -> skipTilde(in, "PGDN");
      default -> null;
    };
  }

  private static String skipTilde(final NonBlockingReader in, final String name)
      throws IOException {
    in.read(50);
    return name;
  }

  private static ConsoleView.Snapshot poll(final String base, final String key, final int limit) {
    try {
      final JsonNode counts = get(base + "/api/executions/counts", key).path("data");
      final JsonNode runs = get(base + "/api/executions?limit=" + limit, key).path("data");
      final List<ConsoleView.Run> rows = new ArrayList<>();
      for (final JsonNode r : runs) {
        rows.add(
            new ConsoleView.Run(
                r.path("workflowId").asString(""),
                r.path("status").asString(""),
                r.path("startTime").asString("").replace('T', ' '),
                r.path("initiator").asString("")));
      }
      final String health = get(base + "/actuator/health", key).path("status").asString("?");
      return new ConsoleView.Snapshot(
          health,
          counts.path("running").asInt(0),
          counts.path("queued").asInt(0),
          counts.path("failed").asInt(0),
          rows);
    } catch (IOException | RuntimeException e) {
      return new ConsoleView.Snapshot("DOWN", 0, 0, 0, List.of());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return ConsoleView.Snapshot.NONE;
    }
  }

  private static JsonNode get(final String url, final String key)
      throws IOException, InterruptedException {
    final HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(2));
    if (!key.isBlank()) {
      b.header("Authorization", "Bearer " + key);
    }
    return JSON.readTree(HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString()).body());
  }
}
